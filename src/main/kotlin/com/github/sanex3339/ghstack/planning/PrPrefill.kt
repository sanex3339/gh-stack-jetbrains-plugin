package com.github.sanex3339.ghstack.planning

data class CommitMessage(val subject: String, val body: String)

/** Pre-fills new PR titles the way `gh stack submit --auto` would. */
object PrPrefill {
    /** `git log --format=` value: record separator, subject, unit separator, body. */
    const val LOG_FORMAT = "%x1e%s%x1f%b"

    fun parseLog(output: String): List<CommitMessage> =
        output.split('\u001e').filter { it.isNotBlank() }.map { record ->
            CommitMessage(
                subject = record.substringBefore('\u001f').trim(),
                body = record.substringAfter('\u001f', missingDelimiterValue = "").trim(),
            )
        }

    fun prefill(branch: String, commits: List<CommitMessage>): Pair<String, String> {
        val single = commits.singleOrNull()
        return if (single != null && single.subject.isNotBlank()) single.subject to single.body else humanize(branch) to ""
    }

    fun humanize(branch: String): String = branch.map { if (it == '-' || it == '_') ' ' else it }.joinToString("")
}
