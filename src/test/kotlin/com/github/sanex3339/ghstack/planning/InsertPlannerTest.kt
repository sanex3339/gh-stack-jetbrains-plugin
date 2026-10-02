package com.github.sanex3339.ghstack.planning

import com.github.sanex3339.ghstack.model.BranchStatus.MERGED
import com.github.sanex3339.ghstack.model.BranchStatus.NEEDS_REBASE
import com.github.sanex3339.ghstack.model.BranchStatus.OPEN
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.testutil.Stacks.stack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class InsertPlannerTest {
    private val stack = stack("main", "models" to MERGED, "auth" to OPEN, "api" to OPEN, "ui" to OPEN, current = "ui")
    private val existing = setOf("main", "models", "auth", "api", "ui")

    private fun plan(target: String, direction: InsertDirection, name: String = "new", operation: OperationState = OperationState.None) =
        InsertPlanner.plan(stack, target, direction, name, existing, operation)

    @Test
    fun `below the top re-inits with the new branch under it`() {
        assertEquals(
            InsertCheck.Ready(InsertPlan.Reinit("ui", "main", "api", "new", listOf("auth", "api", "new", "ui"))),
            plan("ui", InsertDirection.BELOW),
        )
    }

    @Test
    fun `below the lowest active branch is based on the trunk and drops merged branches`() {
        assertEquals(
            InsertCheck.Ready(InsertPlan.Reinit("auth", "main", "main", "new", listOf("new", "auth", "api", "ui"))),
            plan("auth", InsertDirection.BELOW),
        )
    }

    @Test
    fun `above a middle branch is based on it`() {
        assertEquals(
            InsertCheck.Ready(InsertPlan.Reinit("auth", "main", "auth", "new", listOf("auth", "new", "api", "ui"))),
            plan("auth", InsertDirection.ABOVE),
        )
    }

    @Test
    fun `above the top is a plain add`() {
        assertEquals(InsertCheck.Ready(InsertPlan.AddOnTop("ui", "new")), plan("ui", InsertDirection.ABOVE))
    }

    @Test
    fun `rejects bad input`() {
        assertInstanceOf(InsertCheck.Invalid::class.java, plan("ui", InsertDirection.BELOW, name = "api"))
        assertInstanceOf(InsertCheck.Invalid::class.java, plan("ui", InsertDirection.BELOW, name = "bad name"))
        assertInstanceOf(InsertCheck.Invalid::class.java, plan("models", InsertDirection.BELOW))
        assertInstanceOf(InsertCheck.Invalid::class.java, plan("nope", InsertDirection.BELOW))
        assertInstanceOf(InsertCheck.Invalid::class.java, plan("ui", InsertDirection.BELOW, operation = OperationState.RebaseConflict("api")))
    }

    @Test
    fun `a stack that needs a rebase must be rebased before a re-init, but add still works`() {
        val dirty = stack("main", "auth" to OPEN, "api" to NEEDS_REBASE, current = "api")
        val names = setOf("main", "auth", "api")
        assertEquals(InsertCheck.NeedsRebase, InsertPlanner.plan(dirty, "api", InsertDirection.BELOW, "new", names, OperationState.None))
        assertEquals(
            InsertCheck.Ready(InsertPlan.AddOnTop("api", "new")),
            InsertPlanner.plan(dirty, "api", InsertDirection.ABOVE, "new", names, OperationState.None),
        )
    }
}
