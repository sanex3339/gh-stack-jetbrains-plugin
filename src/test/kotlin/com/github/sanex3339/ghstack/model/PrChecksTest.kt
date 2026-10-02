package com.github.sanex3339.ghstack.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Workflow re-runs and concurrency cancellations leave old check runs on the commit; only the newest counts. */
class PrChecksTest {
    private val repo = RepoCoordinates("github.com", "octo", "app")

    @Test
    fun `a check cancelled by a newer run of the same workflow doesn't fail the pull request`() {
        val json = response(
            rollup = "FAILURE",
            contexts = listOf(
                run(id = 10, workflow = "Lint", name = "lint", conclusion = "CANCELLED"),
                run(id = 20, workflow = "Lint", name = "lint", conclusion = "SUCCESS"),
                run(id = 11, workflow = "Tests", name = "unit", conclusion = "CANCELLED"),
                run(id = 21, workflow = "Tests", name = "unit", conclusion = "SKIPPED"),
            ),
        )
        val details = PrDetailsQuery.parse(json).getValue(7)
        assertEquals(ChecksState.PASSING, details.checks)
        assertEquals(emptyList<String>(), details.failingChecks)
    }

    @Test
    fun `the newest run wins even when it is the failing one`() {
        val json = response(
            rollup = "FAILURE",
            contexts = listOf(
                run(id = 30, workflow = "Lint", name = "lint", conclusion = "FAILURE"),
                run(id = 20, workflow = "Lint", name = "lint", conclusion = "SUCCESS"),
            ),
        )
        val details = PrDetailsQuery.parse(json).getValue(7)
        assertEquals(ChecksState.FAILING, details.checks)
        assertEquals(listOf("lint"), details.failingChecks)
    }

    @Test
    fun `a queued re-run makes the check pending`() {
        val json = response(
            rollup = "FAILURE",
            contexts = listOf(
                run(id = 20, workflow = "Lint", name = "lint", conclusion = "FAILURE"),
                run(id = 40, workflow = "Lint", name = "lint", conclusion = null, status = "QUEUED"),
            ),
        )
        val details = PrDetailsQuery.parse(json).getValue(7)
        assertEquals(ChecksState.PENDING, details.checks)
        assertEquals(listOf("lint"), details.pendingChecks)
    }

    @Test
    fun `jobs with the same name in different workflows or events are separate checks`() {
        val json = response(
            rollup = "FAILURE",
            contexts = listOf(
                run(id = 10, workflow = "Backend", name = "build", conclusion = "FAILURE"),
                run(id = 20, workflow = "Frontend", name = "build", conclusion = "SUCCESS"),
                run(id = 11, workflow = "Frontend", name = "test", conclusion = "FAILURE", event = "push"),
                run(id = 21, workflow = "Frontend", name = "test", conclusion = "SUCCESS", event = "pull_request"),
            ),
        )
        val details = PrDetailsQuery.parse(json).getValue(7)
        assertEquals(ChecksState.FAILING, details.checks)
        assertEquals(listOf("build", "test"), details.failingChecks)
    }

    @Test
    fun `reads every page of checks before deciding`() {
        val queries = mutableListOf<String>()
        val pages = listOf(
            response(
                rollup = "FAILURE",
                contexts = listOf(run(id = 10, workflow = "Lint", name = "lint", conclusion = "CANCELLED")),
                nextCursor = "Y3Vyc29yOjE=",
            ),
            checksPage(contexts = listOf(run(id = 20, workflow = "Lint", name = "lint", conclusion = "SUCCESS"))),
        )
        val details = PrDetailsQuery.fetch(repo, listOf(7)) { query -> queries += query; pages[queries.size - 1] }!!
        assertEquals(ChecksState.PASSING, details.getValue(7).checks)
        assertEquals(2, queries.size)
        assertTrue("pullRequest(number: 7)" in queries[1], queries[1])
        assertTrue("""contexts(first: 100, after: "Y3Vyc29yOjE=")""" in queries[1], queries[1])
    }

    @Test
    fun `falls back to GitHub's summary when a later page can't be read`() {
        val first = response(
            rollup = "FAILURE",
            contexts = listOf(run(id = 10, workflow = "Lint", name = "lint", conclusion = "CANCELLED")),
            nextCursor = "Y3Vyc29yOjE=",
        )
        var calls = 0
        val details = PrDetailsQuery.fetch(repo, listOf(7)) { calls++; if (calls == 1) first else null }!!
        assertEquals(ChecksState.FAILING, details.getValue(7).checks)
    }

    @Test
    fun `no answer from GitHub gives no details`() {
        assertEquals(null, PrDetailsQuery.fetch(repo, listOf(7)) { null })
    }

    private fun run(id: Long, workflow: String, name: String, conclusion: String?, status: String = "COMPLETED", event: String = "pull_request") =
        """{"__typename":"CheckRun","databaseId":$id,"name":"$name","status":"$status","conclusion":${conclusion?.let { "\"$it\"" } ?: "null"},""" +
            """"checkSuite":{"workflowRun":{"event":"$event","workflow":{"name":"$workflow"}}}}"""

    private fun contexts(contexts: List<String>, nextCursor: String?) =
        """{"pageInfo":{"hasNextPage":${nextCursor != null},"endCursor":${nextCursor?.let { "\"$it\"" } ?: "null"}},"nodes":[${contexts.joinToString(",")}]}"""

    private fun response(rollup: String, contexts: List<String>, nextCursor: String? = null) =
        """{"data":{"repository":{"pr7":{"number":7,"title":"T","isDraft":false,"reviewDecision":"APPROVED","mergeable":"MERGEABLE",""" +
            """"commits":{"nodes":[{"commit":{"statusCheckRollup":{"state":"$rollup","contexts":${contexts(contexts, nextCursor)}}}}]}}}}}"""

    private fun checksPage(contexts: List<String>, nextCursor: String? = null) =
        """{"data":{"repository":{"pullRequest":{"commits":{"nodes":[{"commit":{"statusCheckRollup":{"contexts":${contexts(contexts, nextCursor)}}}}]}}}}}"""
}
