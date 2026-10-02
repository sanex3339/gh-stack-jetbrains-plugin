package com.github.sanex3339.ghstack.planning

import com.github.sanex3339.ghstack.cli.CliResult
import com.github.sanex3339.ghstack.model.BranchStatus.MERGED
import com.github.sanex3339.ghstack.model.BranchStatus.NOT_SUBMITTED
import com.github.sanex3339.ghstack.model.BranchStatus.OPEN
import com.github.sanex3339.ghstack.testutil.Stacks.stack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubmitPlannerTest {
    @Test
    fun `new PR targets use the nearest active parent`() {
        val s = stack("main", "models" to MERGED, "auth" to NOT_SUBMITTED, "api" to OPEN, "ext" to NOT_SUBMITTED)
        assertEquals(listOf(NewPrTarget("auth", "main"), NewPrTarget("ext", "api")), SubmitPlanner.newPrTargets(s))
    }

    @Test
    fun `no recreate without a remote stack, when equal, or for a pure append`() {
        assertEquals(RecreateDecision.NOT_NEEDED, SubmitPlanner.recreateDecision(null, listOf(1, 2), false))
        assertEquals(RecreateDecision.NOT_NEEDED, SubmitPlanner.recreateDecision(emptyList(), listOf(1, 2), false))
        assertEquals(RecreateDecision.NOT_NEEDED, SubmitPlanner.recreateDecision(listOf(1, 2), listOf(1, 2), false))
        assertEquals(RecreateDecision.NOT_NEEDED, SubmitPlanner.recreateDecision(listOf(1, 2), listOf(1, 2, 3), false))
    }

    @Test
    fun `an inserted PR in the middle requires a recreate`() {
        assertEquals(RecreateDecision.RECREATE, SubmitPlanner.recreateDecision(listOf(43, 44), listOf(43, 46, 44), false))
    }

    @Test
    fun `a PR on GitHub that local tracking lacks blocks the recreate`() {
        assertEquals(RecreateDecision.REMOTE_HAS_UNKNOWN_PRS, SubmitPlanner.recreateDecision(listOf(43, 44, 50), listOf(43, 46, 44), false))
    }

    @Test
    fun `pending modify lets gh stack submit recreate on its own`() {
        assertEquals(RecreateDecision.NOT_NEEDED, SubmitPlanner.recreateDecision(listOf(43, 44), listOf(44, 43), true))
    }

    @Test
    fun `sync aborted is detected only on exit 0`() {
        assertTrue(SyncOutput.isAborted(CliResult(0, "", "⚠ Local and remote stacks have diverged\nSync aborted")))
        assertFalse(SyncOutput.isAborted(CliResult(0, "", "✓ Stack synced")))
        assertFalse(SyncOutput.isAborted(CliResult(3, "", "Sync aborted")))
    }
}
