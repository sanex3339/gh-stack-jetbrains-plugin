package com.github.sanex3339.ghstack.state

import com.github.sanex3339.ghstack.cli.CliStatus
import com.github.sanex3339.ghstack.model.BranchStatus.NOT_SUBMITTED
import com.github.sanex3339.ghstack.model.BranchStatus.OPEN
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.testutil.Stacks.stack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

class BannersTest {
    private val ready = CliStatus.Ready("/gh", "/git", "0.1.1")
    private val base = RepoState.initial(Path.of("/repo")).copy(
        cliStatus = ready,
        gitDir = Path.of("/repo/.git"),
        stacks = listOf(stack("main", "auth" to OPEN, "api" to OPEN, current = "api")),
        currentBranch = "api",
    )

    @Test
    fun `nothing to say for a healthy stack`() {
        assertNull(Banners.of(base))
        assertNull(Banners.of(null))
    }

    @Test
    fun `cli problems come first`() {
        assertEquals(Banner.GhMissing, Banners.of(base.copy(cliStatus = CliStatus.GhNotFound, operation = OperationState.RebaseConflict("api"))))
        assertEquals(Banner.ExtensionMissing, Banners.of(base.copy(cliStatus = CliStatus.ExtensionMissing("/gh"))))
        assertEquals(Banner.NotAuthenticated, Banners.of(base.copy(cliStatus = CliStatus.NotAuthenticated("/gh"))))
        assertEquals(Banner.GitMissing, Banners.of(base.copy(cliStatus = CliStatus.GitNotFound)))
    }

    @Test
    fun `in-progress operations`() {
        assertEquals(Banner.RebaseConflict("api"), Banners.of(base.copy(operation = OperationState.RebaseConflict("api"))))
        assertEquals(Banner.ModifyInterrupted("conflict"), Banners.of(base.copy(operation = OperationState.ModifyInterrupted("conflict"))))
        assertEquals(Banner.ModifyPendingSubmit, Banners.of(base.copy(operation = OperationState.ModifyPendingSubmit)))
    }

    @Test
    fun `unsubmitted branches, unavailable stacks and fallback`() {
        val withNew = base.copy(stacks = listOf(stack("main", "auth" to OPEN, "x" to NOT_SUBMITTED, "api" to OPEN, current = "x")), currentBranch = "x")
        assertEquals(Banner.Unsubmitted(1), Banners.of(withNew))
        assertEquals(Banner.StacksUnavailable, Banners.of(withNew.copy(stacksUnavailable = true)))
        assertEquals(Banner.FallbackMode, Banners.of(base.copy(fallbackMode = true)))
    }
}
