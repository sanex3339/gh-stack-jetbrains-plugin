package com.github.sanex3339.ghstack.state

import com.github.sanex3339.ghstack.model.CheckOutcome
import com.github.sanex3339.ghstack.model.CheckRunInfo

/**
 * What the checks list shows, like the pull request page's checks box: failing checks first (required ones on top),
 * then running and cancelled ones; passed and skipped checks are only counted, since a repo can have hundreds.
 */
data class ChecksSummary(val title: String, val listed: List<CheckRunInfo>, val others: String) {
    companion object {
        fun of(checks: List<CheckRunInfo>): ChecksSummary {
            fun count(outcome: CheckOutcome) = checks.count { it.outcome == outcome }
            val failing = checks.filter { it.outcome == CheckOutcome.FAILING }.sortedByDescending { it.required }
            val title = when {
                failing.isNotEmpty() -> plural(failing.size, "failing check")
                count(CheckOutcome.RUNNING) > 0 -> plural(count(CheckOutcome.RUNNING), "check") + " running"
                count(CheckOutcome.CANCELLED) > 0 -> plural(count(CheckOutcome.CANCELLED), "cancelled check")
                checks.isNotEmpty() -> "All checks passed"
                else -> "No checks"
            }
            val others = listOf(count(CheckOutcome.PASSED) to "passed", count(CheckOutcome.SKIPPED) to "skipped")
                .filter { it.first > 0 }.joinToString(", ") { "${it.first} ${it.second}" }
            val listed = failing + checks.filter { it.outcome == CheckOutcome.RUNNING } + checks.filter { it.outcome == CheckOutcome.CANCELLED }
            return ChecksSummary(title, listed, others)
        }

        /** `Run tests / unit · required`; the event only when it isn't the usual `pull_request`. */
        fun label(check: CheckRunInfo): String =
            check.title + (check.event?.takeIf { it != "pull_request" }?.let { " ($it)" } ?: "") + if (check.required) " · required" else ""

        /** GitHub offers "Re-run failed jobs" once the whole workflow run is done. */
        fun canRerun(check: CheckRunInfo): Boolean = check.outcome == CheckOutcome.FAILING && check.runId != null && check.runFinished

        private fun plural(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"
    }
}
