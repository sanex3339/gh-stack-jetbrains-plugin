package com.github.sanex3339.ghstack.state

import com.github.sanex3339.ghstack.model.OperationState

/** Text of the status bar widget and main toolbar switcher, e.g. `⧉ api 2/4 ⚠`; `null` hides them. */
object StatusText {
    fun of(state: RepoState?): String? {
        if (state == null) return null
        val operation = state.operation
        if (operation is OperationState.RebaseConflict) return "⧉ rebase stopped" + (operation.branch?.let { " on $it" } ?: "") + " ⚠"
        val stack = state.currentStack ?: return null
        val branch = state.currentBranch ?: return null
        val (position, total) = stack.positionOf(branch) ?: return null
        return "⧉ $branch $position/$total" + if (stack.needsRebase) " ⚠" else ""
    }
}
