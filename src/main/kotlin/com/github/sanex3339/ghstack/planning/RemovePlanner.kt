package com.github.sanex3339.ghstack.planning

import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.model.RemoveMode
import com.github.sanex3339.ghstack.model.StackUi

/** Removing [removed] from a stack; lists are bottom → top and contain only unmerged branches. */
data class RemovalPlan(
    val removed: String,
    val mode: RemoveMode,
    val trunk: String,
    /** The branch [removed] is based on: the active branch below it, or the trunk. */
    val parent: String,
    val above: List<String>,
    val newOrder: List<String>,
    val pr: Int?,
)

sealed interface RemovalCheck {
    data class Ready(val plan: RemovalPlan) : RemovalCheck

    /** Removing rewrites history relative to parents, so the stack must be linear first. */
    data object NeedsRebase : RemovalCheck

    data class Invalid(val message: String) : RemovalCheck
}

object RemovePlanner {
    fun availableModes(stack: StackUi, target: String): Set<RemoveMode> {
        val active = stack.activeBranches.map { it.name }
        val index = active.indexOf(target)
        if (index < 0 || active.size < 2) return emptySet()
        return buildSet {
            add(RemoveMode.DROP)
            if (index > 0) add(RemoveMode.FOLD_DOWN)
            if (index < active.lastIndex) add(RemoveMode.FOLD_UP)
        }
    }

    fun plan(stack: StackUi, target: String, mode: RemoveMode, operation: OperationState): RemovalCheck {
        if (operation != OperationState.None) return RemovalCheck.Invalid("Finish or abort the operation in progress first")
        val active = stack.activeBranches.map { it.name }
        val index = active.indexOf(target)
        if (index < 0) return RemovalCheck.Invalid("\"$target\" isn't an unmerged branch of this stack")
        if (active.size < 2) return RemovalCheck.Invalid("\"$target\" is the only branch in the stack. Use Unstack instead.")
        if (mode == RemoveMode.FOLD_DOWN && index == 0) return RemovalCheck.Invalid("There's no branch below \"$target\" to fold it into")
        if (mode == RemoveMode.FOLD_UP && index == active.lastIndex) return RemovalCheck.Invalid("There's no branch above \"$target\" to fold it into")
        if (stack.needsRebase) return RemovalCheck.NeedsRebase
        return RemovalCheck.Ready(
            RemovalPlan(
                removed = target,
                mode = mode,
                trunk = stack.trunk,
                parent = if (index == 0) stack.trunk else active[index - 1],
                above = active.subList(index + 1, active.size),
                newOrder = active - target,
                pr = stack.branch(target)?.pr?.number,
            ),
        )
    }
}
