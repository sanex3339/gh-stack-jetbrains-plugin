package com.github.sanex3339.ghstack.state

import com.github.sanex3339.ghstack.cli.CliStatus
import com.github.sanex3339.ghstack.model.BranchStatus
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.testutil.Stacks.stack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

class StackAutoPullTest {
    private val ready = RepoState.initial(Path.of("/repo")).copy(
        gitDir = Path.of("/repo/.git"),
        cliStatus = CliStatus.Ready("gh", "git", "1.0"),
        stacks = listOf(stack("main", "auth" to BranchStatus.OPEN, "api" to BranchStatus.OPEN).copy(isCurrent = false)),
        currentBranch = "colleague-feature",
    )

    private fun lookUp(state: RepoState = ready, readable: Boolean = true, busy: Boolean = false, tracked: Set<String> = emptySet()) =
        StackAutoPull.branchToLookUp(state, stackFileReadable = readable, busy = busy, trackedBefore = tracked)

    @Test
    fun `a branch no local stack tracks is looked up`() {
        assertEquals("colleague-feature", lookUp())
    }

    @Test
    fun `branches and trunks of local stacks are not`() {
        assertNull(lookUp(ready.copy(currentBranch = "api")))
        assertNull(lookUp(ready.copy(currentBranch = "main")))
    }

    @Test
    fun `nothing is looked up mid-operation, mid-rebase, or without gh`() {
        assertNull(lookUp(busy = true))
        assertNull(lookUp(ready.copy(operation = OperationState.RebaseConflict("api"))))
        assertNull(lookUp(ready.copy(gitRebaseInProgress = true)))
        assertNull(lookUp(ready.copy(cliStatus = null)))
        assertNull(lookUp(readable = false))
        assertNull(lookUp(ready.copy(currentBranch = null)))
    }

    @Test
    fun `a branch a local stack tracked earlier stays out (removed or unstacked on purpose)`() {
        assertNull(lookUp(tracked = setOf("colleague-feature")))
    }
}
