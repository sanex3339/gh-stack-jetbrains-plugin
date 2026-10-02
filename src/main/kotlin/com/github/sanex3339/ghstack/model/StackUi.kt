package com.github.sanex3339.ghstack.model

enum class BranchStatus { MERGED, QUEUED, NEEDS_REBASE, OPEN, NOT_SUBMITTED }

data class PrUi(val number: Int, val url: String?, val state: PrState)

data class BranchUi(
    val name: String,
    val isCurrent: Boolean,
    val status: BranchStatus,
    val pr: PrUi?,
    /** Set for the current stack once GitHub answered; see [MergeReadiness]. */
    val details: PrDetails? = null,
    val blocker: MergeBlocker? = null,
    /** The lowest layer below that blocks merging this one, e.g. "#123". */
    val blockedBy: String? = null,
)

/** A stack prepared for display. [branches] are ordered bottom → top, like the CLI; the UI reverses them. */
data class StackUi(
    val id: String?,
    val number: Int?,
    val trunk: String,
    val branches: List<BranchUi>,
    val isCurrent: Boolean,
) {
    /** Branches that still matter for navigation, rebases and PRs (everything not merged). */
    val activeBranches: List<BranchUi> get() = branches.filter { it.status != BranchStatus.MERGED }

    val topBranch: BranchUi? get() = activeBranches.lastOrNull()

    val title: String get() = number?.let { "Stack #$it" } ?: "Local stack"

    /** Stable identity across refreshes, used to remember tree expansion. */
    val key: String get() = number?.let { "#$it" } ?: "$trunk:${branches.firstOrNull()?.name.orEmpty()}"

    val needsRebase: Boolean get() = activeBranches.any { it.status == BranchStatus.NEEDS_REBASE }

    val unsubmittedCount: Int get() = activeBranches.count { it.status == BranchStatus.NOT_SUBMITTED }

    /** Open pull requests GitHub reports as drafts (known only once details were fetched). */
    val draftPrNumbers: List<Int> get() = activeBranches.filter { it.details?.isDraft == true }.mapNotNull { it.pr?.number }

    fun branch(name: String): BranchUi? = branches.firstOrNull { it.name == name }

    /** The branch [name] is based on: the nearest active branch below it, or the trunk. */
    fun activeParentOf(name: String): String {
        val active = activeBranches
        val index = active.indexOfFirst { it.name == name }
        return if (index <= 0) trunk else active[index - 1].name
    }

    /** 1-based position among active branches counted from the bottom, and the number of active branches. */
    fun positionOf(name: String): Pair<Int, Int>? {
        val index = activeBranches.indexOfFirst { it.name == name }
        return if (index < 0) null else (index + 1) to activeBranches.size
    }
}
