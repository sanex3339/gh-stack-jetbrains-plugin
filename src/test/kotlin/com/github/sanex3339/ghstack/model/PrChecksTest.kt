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
    fun `the run that finished last wins, whatever order the runs were created in`() {
        // A job that waits on another job gets its check run only when its workflow run is cancelled, so ids and
        // run numbers can't order them; GitHub's own "latest" is the run that completed last.
        val json = response(
            rollup = "FAILURE",
            contexts = listOf(
                run(id = 50, runId = 1, workflow = "Deploy", name = "preview", conclusion = "FAILURE", completed = "2026-10-02T20:20:27Z"),
                run(id = 40, runId = 2, workflow = "Deploy", name = "preview", conclusion = "SUCCESS", completed = "2026-10-02T20:20:28Z"),
                run(id = 60, runId = 4, workflow = "Tests", name = "result", conclusion = "FAILURE", completed = "2026-10-02T20:21:59Z"),
                run(id = 55, runId = 3, workflow = "Tests", name = "result", conclusion = "SUCCESS", completed = "2026-10-02T20:22:38Z"),
            ),
        )
        assertEquals(ChecksState.PASSING, PrDetailsQuery.parse(json).getValue(7).checks)
    }

    @Test
    fun `a re-run replaces the earlier attempt`() {
        val json = response(
            rollup = "FAILURE",
            contexts = listOf(
                run(id = 10, runId = 1, workflow = "Tests", name = "unit", conclusion = "FAILURE"),
                run(id = 60, runId = 1, workflow = "Tests", name = "unit", conclusion = "SUCCESS"),
            ),
        )
        assertEquals(ChecksState.PASSING, PrDetailsQuery.parse(json).getValue(7).checks)
    }

    @Test
    fun `a cancelled check fails the pull request only when it is required`() {
        fun checks(required: Boolean) = PrDetailsQuery.parse(
            response(
                rollup = "FAILURE",
                contexts = listOf(
                    run(id = 10, workflow = "Lint", name = "lint", conclusion = "SUCCESS"),
                    run(id = 20, workflow = "Deploy", name = "preview", conclusion = "CANCELLED", required = required),
                ),
            ),
        ).getValue(7)
        assertEquals(ChecksState.PASSING, checks(required = false).checks)
        assertEquals(ChecksState.FAILING, checks(required = true).checks)
        assertEquals(listOf("preview"), checks(required = true).failingChecks)
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
                contexts = listOf(run(id = 10, workflow = "Lint", name = "lint", conclusion = "FAILURE")),
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
            contexts = listOf(run(id = 10, workflow = "Lint", name = "lint", conclusion = "FAILURE")),
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

    private fun run(
        id: Long,
        workflow: String,
        name: String,
        conclusion: String?,
        status: String = "COMPLETED",
        event: String = "pull_request",
        runId: Long = id,
        completed: String? = if (status == "COMPLETED") "2026-10-02T20:%02d:%02dZ".format(id / 60, id % 60) else null,
        required: Boolean = false,
    ) = """{"__typename":"CheckRun","databaseId":$id,"name":"$name","status":"$status","conclusion":${conclusion?.let { "\"$it\"" } ?: "null"},""" +
        """"completedAt":${completed?.let { "\"$it\"" } ?: "null"},"isRequired":$required,""" +
        """"checkSuite":{"workflowRun":{"databaseId":$runId,"event":"$event","workflow":{"name":"$workflow"}}}}"""

    private fun contexts(contexts: List<String>, nextCursor: String?) =
        """{"pageInfo":{"hasNextPage":${nextCursor != null},"endCursor":${nextCursor?.let { "\"$it\"" } ?: "null"}},"nodes":[${contexts.joinToString(",")}]}"""

    private fun response(rollup: String, contexts: List<String>, nextCursor: String? = null) =
        """{"data":{"repository":{"pr7":{"number":7,"title":"T","isDraft":false,"reviewDecision":"APPROVED","mergeable":"MERGEABLE",""" +
            """"commits":{"nodes":[{"commit":{"statusCheckRollup":{"state":"$rollup","contexts":${contexts(contexts, nextCursor)}}}}]}}}}}"""

    private fun checksPage(contexts: List<String>, nextCursor: String? = null) =
        """{"data":{"repository":{"pullRequest":{"commits":{"nodes":[{"commit":{"statusCheckRollup":{"contexts":${contexts(contexts, nextCursor)}}}}]}}}}}"""
}
