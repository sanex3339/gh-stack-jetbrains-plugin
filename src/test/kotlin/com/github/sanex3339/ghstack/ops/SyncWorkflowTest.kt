package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.model.BranchStatus.OPEN
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.model.RepoCoordinates
import com.github.sanex3339.ghstack.testutil.FakePrompts
import com.github.sanex3339.ghstack.testutil.FakeStackCli
import com.github.sanex3339.ghstack.testutil.Stacks.stack
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

class SyncWorkflowTest {
    private val gitDir = Files.createTempDirectory("ghstack-gitdir")
    private val cli = FakeStackCli()
    private val prompts = FakePrompts()
    private val snapshot = RepoSnapshot(
        stack("main", "auth" to OPEN, "api" to OPEN, current = "api", number = 7, id = "1201"),
        "api",
        RepoCoordinates("github.com", "octo", "app"),
        OperationState.None,
    )
    private val diverged = "⚠ The stack on GitHub has diverged from your local stack\nSync aborted"

    private fun run(prune: Boolean = false) = SyncWorkflow(cli, gitDir, prompts).run(prune, snapshot)

    @Test
    fun `plain sync and prune`() {
        assertEquals(SyncOutcome.SYNCED, run())
        assertEquals(SyncOutcome.SYNCED, run(prune = true))
        assertEquals(listOf("stack sync", "stack sync --prune"), cli.calls)
        assertEquals(emptyList<String>(), prompts.asked)
    }

    @Test
    fun `sync aborted on divergence is not reported as synced`() {
        cli.on("stack sync", stderr = diverged)
        assertEquals(SyncOutcome.CANCELLED, run())
        assertEquals(listOf("diverged"), prompts.asked)
    }

    @Test
    fun `use GitHub's version re-checks out the stack by number`() {
        cli.on("stack sync", stderr = diverged)
        prompts.divergenceChoice = DivergenceChoice.USE_REMOTE
        assertEquals(SyncOutcome.SYNCED, run())
        assertEquals(listOf("stack sync", "stack unstack --local", "stack checkout 7"), cli.calls)
    }

    @Test
    fun `a failed checkout from GitHub restores local tracking`() {
        val file = gitDir.resolve(StackFileStore.STACK_FILE)
        file.writeText("""{"schemaVersion":1,"stacks":[]}""")
        val before = StackFileStore.read(gitDir)
        cli.on("stack sync", stderr = diverged)
        cli.onRun("stack unstack --local") { file.deleteIfExists() }
        cli.on("stack checkout", exitCode = 1, stderr = "✗ boom")
        prompts.divergenceChoice = DivergenceChoice.USE_REMOTE
        assertThrows<WorkflowAbort> { run() }
        assertArrayEquals(before, StackFileStore.read(gitDir))
    }

    @Test
    fun `keep mine recreates the GitHub stack from local state`() {
        cli.on("stack sync", stderr = diverged)
        cli.on("gh api --hostname github.com repos/octo/app/stacks/7", stdout = """{"number":7,"pull_requests":[{"number":101,"state":"open"},{"number":100,"state":"open"}]}""")
        prompts.divergenceChoice = DivergenceChoice.KEEP_LOCAL
        assertEquals(SyncOutcome.SYNCED, run())
        assertEquals(
            listOf(
                "stack sync",
                "gh api --hostname github.com repos/octo/app/stacks/7",
                "gh api --hostname github.com -X POST repos/octo/app/stacks/7/unstack",
                "git branch --show-current",
                "stack unstack --local",
                "stack init --base main auth api",
                "git branch --show-current",
                "git checkout api",
                "stack submit --auto",
            ),
            cli.calls,
        )
    }

    @Test
    fun `a push swallowed by a ruleset is redone in batches`() {
        cli.on("stack sync", stderr = "⚠ Push failed: failed to run git: remote: - Pushes can not update more than 5 branches or tags.\n✓ Stack synced")
        cli.on("stack view --json", stdout = """{"trunk":"main","currentBranch":"api","branches":[{"name":"auth"},{"name":"api"}]}""")
        assertEquals(SyncOutcome.SYNCED_PUSHED_IN_BATCHES, run())
        assertEquals(1, cli.calls.count { it.startsWith("git push origin") }, cli.calls.toString())
    }

    @Test
    fun `other swallowed push failures are not reported as synced`() {
        cli.on("stack sync", stderr = "⚠ Push failed — branches may need force push after rebase\n✓ Branches synced")
        assertThrows<WorkflowAbort> { run() }
    }

    @Test
    fun `conflicts and terminal choice are reported`() {
        cli.on("stack sync", exitCode = 3, stderr = "✗ conflict")
        assertEquals(SyncOutcome.CONFLICT, run())
        cli.on("stack sync", stderr = diverged)
        prompts.divergenceChoice = DivergenceChoice.OPEN_TERMINAL
        assertEquals(SyncOutcome.OPEN_TERMINAL, run())
    }
}
