package com.github.sanex3339.ghstack.state

import com.github.sanex3339.ghstack.model.CheckOutcome
import com.github.sanex3339.ghstack.model.CheckOutcome.CANCELLED
import com.github.sanex3339.ghstack.model.CheckOutcome.FAILING
import com.github.sanex3339.ghstack.model.CheckOutcome.PASSED
import com.github.sanex3339.ghstack.model.CheckOutcome.RUNNING
import com.github.sanex3339.ghstack.model.CheckOutcome.SKIPPED
import com.github.sanex3339.ghstack.model.CheckRunInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChecksSummaryTest {
    private fun check(name: String, outcome: CheckOutcome, required: Boolean = false) =
        CheckRunInfo(name, "CI", "pull_request", outcome, "https://ci/$name", required)

    @Test
    fun `failing checks come first, required ones on top, then running and cancelled, the rest counted`() {
        val summary = ChecksSummary.of(
            listOf(
                check("lint", PASSED),
                check("e2e", RUNNING),
                check("docs", SKIPPED),
                check("unit", FAILING),
                check("result", FAILING, required = true),
                check("deploy", CANCELLED),
                check("format", PASSED),
            ),
        )
        assertEquals("2 failing checks", summary.title)
        assertEquals(listOf("result", "unit", "e2e", "deploy"), summary.listed.map { it.name })
        assertEquals("2 passed, 1 skipped", summary.others)
    }

    @Test
    fun `titles follow what is going on`() {
        assertEquals("1 failing check", ChecksSummary.of(listOf(check("unit", FAILING))).title)
        assertEquals("3 checks running", ChecksSummary.of(List(3) { check("e$it", RUNNING) }).title)
        assertEquals("All checks passed", ChecksSummary.of(listOf(check("lint", PASSED), check("docs", SKIPPED))).title)
        assertEquals("No checks", ChecksSummary.of(emptyList()).title)
        assertEquals("", ChecksSummary.of(listOf(check("unit", FAILING))).others)
    }

    @Test
    fun `failed jobs can be re-run once their workflow run has finished`() {
        val finished = CheckRunInfo("unit", "CI", "pull_request", FAILING, null, false, runId = 5, runFinished = true)
        assertEquals(true, ChecksSummary.canRerun(finished))
        assertEquals(false, ChecksSummary.canRerun(finished.copy(runFinished = false)), "the run is still going")
        assertEquals(false, ChecksSummary.canRerun(finished.copy(runId = null)), "not a GitHub Actions job")
        assertEquals(false, ChecksSummary.canRerun(finished.copy(outcome = PASSED)))
    }

    @Test
    fun `rows read like the pull request page`() {
        assertEquals("CI / unit · required", ChecksSummary.label(check("unit", FAILING, required = true)))
        assertEquals("CI / unit (push)", ChecksSummary.label(CheckRunInfo("unit", "CI", "push", FAILING, null, false)))
        assertEquals("ci/legacy", ChecksSummary.label(CheckRunInfo("ci/legacy", null, null, FAILING, null, false)))
    }
}
