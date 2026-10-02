package com.github.sanex3339.ghstack.state

import com.github.sanex3339.ghstack.cli.CliStatus
import com.github.sanex3339.ghstack.model.BranchStatus.MERGED
import com.github.sanex3339.ghstack.model.BranchStatus.NEEDS_REBASE
import com.github.sanex3339.ghstack.model.BranchStatus.OPEN
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.testutil.Stacks.stack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

class StatusTextTest {
    private val base = RepoState.initial(Path.of("/repo")).copy(cliStatus = CliStatus.Ready("/gh", "/git", null))

    @Test
    fun `position counts unmerged branches from the bottom`() {
        val state = base.copy(stacks = listOf(stack("main", "m" to MERGED, "auth" to OPEN, "api" to OPEN, "ui" to OPEN, current = "api")), currentBranch = "api")
        assertEquals("⧉ api 2/3", StatusText.of(state))
    }

    @Test
    fun `warns when a rebase is needed or stopped`() {
        val state = base.copy(stacks = listOf(stack("main", "auth" to OPEN, "api" to NEEDS_REBASE, current = "api")), currentBranch = "api")
        assertEquals("⧉ api 2/2 ⚠", StatusText.of(state))
        assertEquals("⧉ rebase stopped on api ⚠", StatusText.of(state.copy(currentBranch = null, operation = OperationState.RebaseConflict("api"))))
        assertEquals("⧉ removing api: rebase stopped ⚠", StatusText.of(state.copy(currentBranch = null, operation = OperationState.RemovalStopped("api", "ui"))))
    }

    @Test
    fun `hidden outside a stack`() {
        assertNull(StatusText.of(base.copy(currentBranch = "main")))
        assertNull(StatusText.of(null))
    }
}
