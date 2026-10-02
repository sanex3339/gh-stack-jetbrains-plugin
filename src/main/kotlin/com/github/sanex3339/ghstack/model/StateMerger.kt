package com.github.sanex3339.ghstack.model

/**
 * Combines the stack file (all stacks, cheap, possibly stale PR data) with the `view --json`
 * overlay (current stack only, live). The overlay is applied only when it describes exactly the
 * same branches as the file's stack, so a stale overlay never resurrects an old composition.
 */
object StateMerger {
    fun merge(file: StackFile?, overlay: ViewSnapshot?, currentBranch: String?): List<StackUi> {
        if (file == null) return listOfNotNull(overlay?.let { fromOverlay(null, it, currentBranch) })
        val overlayIndex = overlay?.let { view ->
            file.stacks.indexOfFirst { s ->
                s.trunk == view.trunk && s.branches.map { it.name } == view.branches.map { it.name } &&
                    // After switching to another stack the old overlay still matches its own stack; don't apply it.
                    (currentBranch == null || s.hasBranch(currentBranch) || s.trunk == currentBranch)
            }
        } ?: -1
        val currentIndex = if (overlayIndex >= 0) overlayIndex else currentBranch?.let { b -> file.stacks.indexOfFirst { it.hasBranch(b) } } ?: -1
        return file.stacks.mapIndexed { index, stack ->
            if (index == overlayIndex && overlay != null) {
                fromOverlay(stack, overlay, currentBranch)
            } else {
                fromFile(stack, currentBranch, isCurrent = index == currentIndex)
            }
        }
    }

    private fun fromFile(stack: LocalStack, currentBranch: String?, isCurrent: Boolean) = StackUi(
        id = stack.id,
        number = stack.number,
        trunk = stack.trunk,
        branches = stack.branches.map { b ->
            BranchUi(
                name = b.name,
                isCurrent = b.name == currentBranch,
                status = when {
                    b.merged -> BranchStatus.MERGED
                    b.pr != null -> BranchStatus.OPEN
                    else -> BranchStatus.NOT_SUBMITTED
                },
                pr = b.pr?.let { PrUi(it.number, it.url, if (b.merged) PrState.MERGED else PrState.OPEN) },
            )
        },
        isCurrent = isCurrent,
    )

    /** [currentBranch] (read from HEAD) wins over the overlay's, which may predate a checkout. */
    private fun fromOverlay(stack: LocalStack?, view: ViewSnapshot, currentBranch: String?) = StackUi(
        id = stack?.id,
        number = stack?.number,
        trunk = view.trunk,
        branches = view.branches.map { b ->
            BranchUi(
                name = b.name,
                isCurrent = b.name == (currentBranch ?: view.currentBranch),
                status = when {
                    b.isMerged -> BranchStatus.MERGED
                    b.isQueued -> BranchStatus.QUEUED
                    b.needsRebase -> BranchStatus.NEEDS_REBASE
                    b.pr != null -> BranchStatus.OPEN
                    else -> BranchStatus.NOT_SUBMITTED
                },
                pr = b.pr?.let { PrUi(it.number, it.url, it.state) },
            )
        },
        isCurrent = true,
    )
}
