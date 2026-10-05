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
    fun `checks cancelled because another run of the workflow replaced them don't count, even before that run reaches them`() {
        // The older run's required result job was cancelled; the newer run is still testing and creates its result job later.
        val json = response(
            rollup = "FAILURE",
            contexts = listOf(
                run(id = 50, runId = 1, workflow = "Run tests", name = "app-db-tests-result", conclusion = "CANCELLED", required = true),
                run(id = 51, runId = 1, workflow = "Run tests", name = "unit", conclusion = "CANCELLED"),
                run(id = 60, runId = 2, workflow = "Run tests", name = "unit", conclusion = null, status = "IN_PROGRESS"),
            ),
        )
        val details = PrDetailsQuery.parse(json).getValue(7)
        assertEquals(ChecksState.PENDING, details.checks)
        assertEquals(emptyList<String>(), details.failingChecks)
        assertEquals(listOf("unit"), details.pendingChecks)
    }

    @Test
    fun `a newer run cancelled before it started doesn't hide the older run's result`() {
        val json = response(
            rollup = "FAILURE",
            contexts = listOf(
                run(id = 50, runId = 1, workflow = "Backport", name = "decide", conclusion = "SUCCESS", completed = "2026-10-05T13:41:07Z", required = true),
                run(id = 40, runId = 2, workflow = "Backport", name = "decide", conclusion = "CANCELLED", completed = "2026-10-05T13:40:31Z", required = true),
                // Created when its run was cancelled, after the newer run had skipped it.
                run(id = 70, runId = 3, workflow = "Deploy", name = "preview", conclusion = "CANCELLED", completed = "2026-10-02T20:20:27Z"),
                run(id = 65, runId = 4, workflow = "Deploy", name = "preview", conclusion = "SKIPPED", completed = "2026-10-02T20:20:28Z"),
            ),
        )
        assertEquals(ChecksState.PASSING, PrDetailsQuery.parse(json).getValue(7).checks)
    }

    @Test
    fun `a real failure in an older run still counts until a later run of the check finishes`() {
        val json = response(
            rollup = "FAILURE",
            contexts = listOf(
                run(id = 10, runId = 1, workflow = "Tests", name = "unit", conclusion = "FAILURE"),
                run(id = 11, runId = 1, workflow = "Tests", name = "lint", conclusion = "SUCCESS"),
                run(id = 20, runId = 2, workflow = "Tests", name = "lint", conclusion = "SUCCESS"),
            ),
        )
        assertEquals(listOf("unit"), PrDetailsQuery.parse(json).getValue(7).failingChecks)
    }

    @Test
    fun `keeps each counted check with its workflow, outcome, job link and whether it's required`() {
        val json = response(
            rollup = "FAILURE",
            contexts = listOf(
                run(id = 10, runId = 1, workflow = "Run tests", name = "sdk-tests / SDK component tests", conclusion = "FAILURE", required = true, runDone = false),
                run(id = 11, runId = 1, workflow = "Run tests", name = "e2e", conclusion = null, status = "IN_PROGRESS", runDone = false),
                run(id = 12, runId = 1, workflow = "Run tests", name = "lint", conclusion = "SUCCESS", runDone = false),
                run(id = 13, runId = 1, workflow = "Run tests", name = "docs", conclusion = "SKIPPED", runDone = false),
                run(id = 30, runId = 3, workflow = "Lint", name = "format", conclusion = "FAILURE"),
                run(id = 20, runId = 2, workflow = "Run tests", name = "old", conclusion = "CANCELLED"),
                """{"__typename":"StatusContext","context":"ci/legacy","state":"ERROR","targetUrl":"https://ci.example.com/7","isRequired":false}""",
            ),
        )
        val checks = PrDetailsQuery.parse(json).getValue(7).checkRuns
        assertEquals(
            listOf(
                CheckRunInfo("sdk-tests / SDK component tests", "Run tests", "pull_request", CheckOutcome.FAILING, "https://github.com/octo/app/actions/runs/1/job/10", required = true, runId = 1),
                CheckRunInfo("e2e", "Run tests", "pull_request", CheckOutcome.RUNNING, "https://github.com/octo/app/actions/runs/1/job/11", required = false, runId = 1),
                CheckRunInfo("lint", "Run tests", "pull_request", CheckOutcome.PASSED, "https://github.com/octo/app/actions/runs/1/job/12", required = false, runId = 1),
                CheckRunInfo("docs", "Run tests", "pull_request", CheckOutcome.SKIPPED, "https://github.com/octo/app/actions/runs/1/job/13", required = false, runId = 1),
                CheckRunInfo("format", "Lint", "pull_request", CheckOutcome.FAILING, "https://github.com/octo/app/actions/runs/3/job/30", required = false, runId = 3, runFinished = true),
                CheckRunInfo("ci/legacy", null, null, CheckOutcome.FAILING, "https://ci.example.com/7", required = false),
            ),
            checks,
            "the cancelled run another run replaced isn't listed",
        )
        assertEquals("Run tests / sdk-tests / SDK component tests", checks.first().title)
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
        runDone: Boolean = true,
    ) = """{"__typename":"CheckRun","databaseId":$id,"name":"$name","status":"$status","conclusion":${conclusion?.let { "\"$it\"" } ?: "null"},""" +
        """"completedAt":${completed?.let { "\"$it\"" } ?: "null"},"isRequired":$required,""" +
        """"detailsUrl":"https://github.com/octo/app/actions/runs/$runId/job/$id",""" +
        """"checkSuite":{"status":"${if (runDone) "COMPLETED" else "IN_PROGRESS"}","workflowRun":{"databaseId":$runId,"event":"$event","workflow":{"name":"$workflow"}}}}"""

    private fun contexts(contexts: List<String>, nextCursor: String?) =
        """{"pageInfo":{"hasNextPage":${nextCursor != null},"endCursor":${nextCursor?.let { "\"$it\"" } ?: "null"}},"nodes":[${contexts.joinToString(",")}]}"""

    private fun response(rollup: String, contexts: List<String>, nextCursor: String? = null) =
        """{"data":{"repository":{"pr7":{"number":7,"title":"T","isDraft":false,"reviewDecision":"APPROVED","mergeable":"MERGEABLE",""" +
            """"commits":{"nodes":[{"commit":{"statusCheckRollup":{"state":"$rollup","contexts":${contexts(contexts, nextCursor)}}}}]}}}}}"""

    private fun checksPage(contexts: List<String>, nextCursor: String? = null) =
        """{"data":{"repository":{"pullRequest":{"commits":{"nodes":[{"commit":{"statusCheckRollup":{"contexts":${contexts(contexts, nextCursor)}}}}]}}}}}"""
}
