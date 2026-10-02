package com.github.sanex3339.ghstack.planning

import com.github.sanex3339.ghstack.model.BranchStatus.MERGED
import com.github.sanex3339.ghstack.model.BranchStatus.NEEDS_REBASE
import com.github.sanex3339.ghstack.model.BranchStatus.OPEN
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.model.RemoveMode.DROP
import com.github.sanex3339.ghstack.model.RemoveMode.FOLD_DOWN
import com.github.sanex3339.ghstack.model.RemoveMode.FOLD_UP
import com.github.sanex3339.ghstack.testutil.Stacks.stack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class RemovePlannerTest {
    // models=#100 (merged), auth=#101, api=#102, ui=#103
    private val stack = stack("main", "models" to MERGED, "auth" to OPEN, "api" to OPEN, "ui" to OPEN, current = "ui")

    @Test
    fun `modes depend on neighbours`() {
        assertEquals(setOf(DROP, FOLD_UP), RemovePlanner.availableModes(stack, "auth"))
        assertEquals(setOf(DROP, FOLD_DOWN, FOLD_UP), RemovePlanner.availableModes(stack, "api"))
        assertEquals(setOf(DROP, FOLD_DOWN), RemovePlanner.availableModes(stack, "ui"))
        assertEquals(emptySet<Any>(), RemovePlanner.availableModes(stack, "models"))
        assertEquals(emptySet<Any>(), RemovePlanner.availableModes(stack("main", "only" to OPEN), "only"))
    }

    @Test
    fun `plans a middle drop`() {
        assertEquals(
            RemovalCheck.Ready(RemovalPlan("api", DROP, "main", parent = "auth", above = listOf("ui"), newOrder = listOf("auth", "ui"), pr = 102)),
            RemovePlanner.plan(stack, "api", DROP, OperationState.None),
        )
    }

    @Test
    fun `the lowest branch is based on the trunk`() {
        val check = RemovePlanner.plan(stack, "auth", FOLD_UP, OperationState.None) as RemovalCheck.Ready
        assertEquals("main", check.plan.parent)
        assertEquals(listOf("api", "ui"), check.plan.above)
        assertEquals(listOf("api", "ui"), check.plan.newOrder)
    }

    @Test
    fun `rejects impossible removals`() {
        assertInstanceOf(RemovalCheck.Invalid::class.java, RemovePlanner.plan(stack, "auth", FOLD_DOWN, OperationState.None))
        assertInstanceOf(RemovalCheck.Invalid::class.java, RemovePlanner.plan(stack, "ui", FOLD_UP, OperationState.None))
        assertInstanceOf(RemovalCheck.Invalid::class.java, RemovePlanner.plan(stack, "models", DROP, OperationState.None))
        assertInstanceOf(RemovalCheck.Invalid::class.java, RemovePlanner.plan(stack("main", "only" to OPEN), "only", DROP, OperationState.None))
        assertInstanceOf(RemovalCheck.Invalid::class.java, RemovePlanner.plan(stack, "api", DROP, OperationState.RebaseConflict("x")))
    }

    @Test
    fun `a stack that needs a rebase must be rebased first`() {
        val dirty = stack("main", "auth" to OPEN, "api" to NEEDS_REBASE, current = "api")
        assertEquals(RemovalCheck.NeedsRebase, RemovePlanner.plan(dirty, "auth", DROP, OperationState.None))
    }
}
