package com.github.sanex3339.ghstack.state

import com.github.sanex3339.ghstack.model.BranchStatus
import com.github.sanex3339.ghstack.model.BranchUi
import com.github.sanex3339.ghstack.model.ChecksState
import com.github.sanex3339.ghstack.model.ReviewState
import com.github.sanex3339.ghstack.model.StackUi

enum class Tone { POSITIVE, NEUTRAL, WARNING, NEGATIVE }

/** Where clicking a badge takes you. */
sealed interface BadgeLink {
    data class Url(val url: String) : BadgeLink
    data class Branch(val name: String) : BadgeLink
}

/** [compact] is used where space is tight (the tree); [text] spells it out (menus, tooltips). */
data class Badge(val text: String, val tone: Tone, val compact: String = text, val link: BadgeLink? = null)

/** The short status labels shown after a branch in the tree and the switcher. */
object BranchBadges {
    fun of(branch: BranchUi, stack: StackUi? = null): List<Badge> = buildList {
        addAll(statusBadges(branch))
        branch.blockedBy?.let { blocker ->
            val target = stack?.branches?.firstOrNull { b -> b.pr?.let { "#${it.number}" } == blocker || b.name == blocker }
            add(Badge("blocked by $blocker", Tone.WARNING, compact = "⛔ $blocker", link = target?.let { BadgeLink.Branch(it.name) }))
        }
    }

    private fun statusBadges(branch: BranchUi): List<Badge> = when (branch.status) {
        BranchStatus.MERGED -> listOf(Badge("merged", Tone.NEUTRAL))
        BranchStatus.QUEUED -> listOf(Badge("queued", Tone.WARNING))
        BranchStatus.NOT_SUBMITTED -> listOf(Badge("not submitted", Tone.NEUTRAL))
        BranchStatus.NEEDS_REBASE -> listOf(Badge("needs rebase", Tone.WARNING))
        BranchStatus.OPEN -> openBadges(branch)
    }

    /** Review and checks get a label each, so an approved pull request still says so while its checks run. */
    private fun openBadges(branch: BranchUi): List<Badge> {
        val url = branch.pr?.url
        val prUrl = url?.let(BadgeLink::Url)
        val details = branch.details ?: return listOf(Badge("open", Tone.NEUTRAL, link = prUrl))
        val checksUrl = url?.let { BadgeLink.Url("$it/checks") }
        val badges = buildList {
            if (details.isDraft) add(Badge("draft", Tone.NEUTRAL, link = prUrl))
            if (details.conflicting) add(Badge("conflicts", Tone.NEGATIVE, link = prUrl))
            // GitHub doesn't ask for reviews on drafts, so "needs review" there is noise.
            if (!details.isDraft) {
                when (details.review) {
                    ReviewState.APPROVED -> add(Badge("approved", Tone.POSITIVE, compact = "✓ approved", link = prUrl))
                    ReviewState.CHANGES_REQUESTED -> add(Badge("changes requested", Tone.NEGATIVE, compact = "changes req.", link = prUrl))
                    ReviewState.REVIEW_REQUIRED -> add(Badge("review required", Tone.WARNING, compact = "needs review", link = prUrl))
                    ReviewState.NONE -> {}
                }
            }
            when (details.checks) {
                ChecksState.FAILING -> add(Badge("checks failing", Tone.NEGATIVE, compact = "✗ checks", link = checksUrl))
                ChecksState.PENDING -> add(Badge("checks running", Tone.WARNING, compact = "● checks", link = checksUrl))
                ChecksState.PASSING, ChecksState.NONE -> {}
            }
        }
        return badges.ifEmpty { listOf(Badge("ready", Tone.POSITIVE, link = prUrl)) }
    }

    /** HTML tooltip with everything the badges abbreviate. */
    fun tooltip(branch: BranchUi, stack: StackUi): String {
        val details = branch.details
        val lines = mutableListOf<String>()
        lines += "<b>${escape(details?.title?.takeIf { it.isNotBlank() } ?: branch.name)}</b>"
        lines += escape(branch.name) + (branch.pr?.let { " · #${it.number}" } ?: "")
        lines += "Status: " + statusBadges(branch).joinToString(", ") { it.text }
        if (details != null) {
            lines += "Review: " + when (details.review) {
                ReviewState.APPROVED -> "approved"
                ReviewState.CHANGES_REQUESTED -> "changes requested"
                ReviewState.REVIEW_REQUIRED -> "required"
                ReviewState.NONE -> "not required"
            }
            lines += "Checks: " + when {
                details.failingChecks.isNotEmpty() -> "✗ failing: " + escape(details.failingChecks.joinToString(", "))
                details.pendingChecks.isNotEmpty() -> "● running: " + escape(details.pendingChecks.joinToString(", "))
                details.checks == ChecksState.PASSING -> "✓ passing"
                details.checks == ChecksState.FAILING -> "✗ failing"
                details.checks == ChecksState.PENDING -> "● running"
                else -> "none"
            }
            if (details.conflicting) lines += "Conflicts with its base branch"
        }
        branch.blockedBy?.let { blocker ->
            val reason = stack.branches.firstOrNull { b -> b.pr?.let { "#${it.number}" } == blocker || b.name == blocker }?.blocker?.label
            lines += "Blocked by $blocker" + (reason?.let { " ($it)" } ?: "") + ": the stack merges from the bottom"
        }
        return "<html>" + lines.joinToString("<br>") + "</html>"
    }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
