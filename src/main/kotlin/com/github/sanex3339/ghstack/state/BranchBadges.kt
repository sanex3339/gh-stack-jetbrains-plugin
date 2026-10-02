package com.github.sanex3339.ghstack.state

import com.github.sanex3339.ghstack.model.BranchStatus
import com.github.sanex3339.ghstack.model.BranchUi
import com.github.sanex3339.ghstack.model.MergeBlocker
import com.github.sanex3339.ghstack.model.ReviewState

enum class Tone { POSITIVE, NEUTRAL, WARNING, NEGATIVE }

data class Badge(val text: String, val tone: Tone)

/** The short status labels shown after a branch in the tree and the switcher. */
object BranchBadges {
    fun of(branch: BranchUi): List<Badge> = buildList {
        add(
            when (branch.status) {
                BranchStatus.MERGED -> Badge("merged", Tone.NEUTRAL)
                BranchStatus.QUEUED -> Badge("queued", Tone.WARNING)
                BranchStatus.NOT_SUBMITTED -> Badge("not submitted", Tone.NEUTRAL)
                BranchStatus.NEEDS_REBASE -> Badge("needs rebase", Tone.WARNING)
                BranchStatus.OPEN -> openBadge(branch)
            },
        )
        branch.blockedBy?.let { add(Badge("blocked by $it", Tone.WARNING)) }
    }

    private fun openBadge(branch: BranchUi): Badge {
        val details = branch.details ?: return Badge("open", Tone.NEUTRAL)
        return when (branch.blocker) {
            MergeBlocker.DRAFT -> Badge("draft", Tone.NEUTRAL)
            MergeBlocker.CONFLICTS, MergeBlocker.CHECKS_FAILING, MergeBlocker.CHANGES_REQUESTED -> Badge(branch.blocker.label, Tone.NEGATIVE)
            MergeBlocker.REVIEW_REQUIRED, MergeBlocker.CHECKS_PENDING -> Badge(branch.blocker.label, Tone.WARNING)
            null -> Badge(if (details.review == ReviewState.APPROVED) "approved" else "ready", Tone.POSITIVE)
            MergeBlocker.NOT_SUBMITTED, MergeBlocker.NEEDS_REBASE -> Badge(branch.blocker.label, Tone.WARNING)
        }
    }
}
