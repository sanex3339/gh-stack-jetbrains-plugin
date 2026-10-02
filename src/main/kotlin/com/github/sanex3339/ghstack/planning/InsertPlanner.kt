package com.github.sanex3339.ghstack.planning

import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.model.StackUi

enum class InsertDirection { BELOW, ABOVE }

sealed interface InsertPlan {
    val newBranch: String

    /** Inserting above the top branch is a plain `gh stack add` run from the top. */
    data class AddOnTop(val top: String, override val newBranch: String) : InsertPlan

    /** Create [newBranch] at [parent]'s tip, then re-register the stack as [newOrder] (bottom → top). */
    data class Reinit(
        val target: String,
        val trunk: String,
        val parent: String,
        override val newBranch: String,
        val newOrder: List<String>,
    ) : InsertPlan
}

sealed interface InsertCheck {
    data class Ready(val plan: InsertPlan) : InsertCheck

    /** A re-init needs a linear stack; the user should rebase first. */
    data object NeedsRebase : InsertCheck

    data class Invalid(val message: String) : InsertCheck
}

object InsertPlanner {
    fun plan(
        stack: StackUi,
        target: String,
        direction: InsertDirection,
        newBranch: String,
        existingBranches: Set<String>,
        operation: OperationState,
    ): InsertCheck {
        if (operation != OperationState.None) return InsertCheck.Invalid("Finish or abort the rebase or modify in progress first")
        BranchNames.validationError(newBranch)?.let { return InsertCheck.Invalid(it) }
        if (newBranch in existingBranches) return InsertCheck.Invalid("Branch \"$newBranch\" already exists")
        val active = stack.activeBranches.map { it.name }
        val index = active.indexOf(target)
        if (index < 0) return InsertCheck.Invalid("\"$target\" isn't an unmerged branch of this stack")

        val plan: InsertPlan = when (direction) {
            InsertDirection.BELOW -> InsertPlan.Reinit(
                target = target,
                trunk = stack.trunk,
                parent = if (index == 0) stack.trunk else active[index - 1],
                newBranch = newBranch,
                newOrder = active.toMutableList().apply { add(index, newBranch) },
            )
            InsertDirection.ABOVE -> if (index == active.lastIndex) {
                InsertPlan.AddOnTop(target, newBranch)
            } else {
                InsertPlan.Reinit(
                    target = target,
                    trunk = stack.trunk,
                    parent = target,
                    newBranch = newBranch,
                    newOrder = active.toMutableList().apply { add(index + 1, newBranch) },
                )
            }
        }
        if (plan is InsertPlan.Reinit && stack.needsRebase) return InsertCheck.NeedsRebase
        return InsertCheck.Ready(plan)
    }
}
