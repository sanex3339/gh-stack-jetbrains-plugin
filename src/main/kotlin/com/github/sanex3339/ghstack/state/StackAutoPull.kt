package com.github.sanex3339.ghstack.state

import com.github.sanex3339.ghstack.model.OperationState

/** When to ask GitHub whether the checked-out branch belongs to a stack that no local stack tracks yet. */
object StackAutoPull {
    /**
     * The branch to look up, or `null`. Never mid-operation (inserts and removals pass through untracked branches),
     * and never for a branch a local stack tracked earlier: it was removed or unstacked on purpose.
     */
    fun branchToLookUp(state: RepoState, stackFileReadable: Boolean, busy: Boolean, trackedBefore: Set<String>): String? {
        val branch = state.currentBranch ?: return null
        if (!state.ready || !stackFileReadable || busy) return null
        if (state.operation != OperationState.None || state.gitRebaseInProgress) return null
        if (state.stacks.any { it.trunk == branch || it.branch(branch) != null }) return null
        if (branch in trackedBefore) return null
        return branch
    }
}
