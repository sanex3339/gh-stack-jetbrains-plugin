package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.model.BranchStatus.OPEN
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.model.RemoveMode
import com.github.sanex3339.ghstack.model.RepoCoordinates
import com.github.sanex3339.ghstack.planning.RemovalCheck
import com.github.sanex3339.ghstack.planning.RemovePlanner
import com.github.sanex3339.ghstack.testutil.FakeStackCli
import com.github.sanex3339.ghstack.testutil.Stacks.stack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** The GitHub side of a removal, with a scripted CLI. */
class RemoveBranchWorkflowTest {
    private val cli = FakeStackCli()
    private val gitDir = Files.createTempDirectory("ghstack-gitdir")

    // auth=#100, api=#101, ui=#102 in stack #7 on GitHub
    private val stack = stack("main", "auth" to OPEN, "api" to OPEN, "ui" to OPEN, current = "ui", number = 7, id = "1201")
    private val snapshot = RepoSnapshot(stack, "ui", RepoCoordinates("github.com", "octo", "app"), OperationState.None)

    private fun fold(options: RemovalOptions): RemovalOutcome {
        cli.on("gh api --hostname github.com repos/octo/app/stacks/7", stdout = """{"number":7,"pull_requests":[{"number":100,"state":"open"},{"number":101,"state":"open"},{"number":102,"state":"open"}]}""")
        val plan = (RemovePlanner.plan(stack, "api", RemoveMode.FOLD_UP, OperationState.None) as RemovalCheck.Ready).plan
        return RemoveBranchWorkflow(cli, gitDir).start(plan, options, snapshot)
    }

    @Test
    fun `recreates the GitHub stack without the branch and closes its PR`() {
        val outcome = fold(RemovalOptions(closePr = true, deleteBranch = false))
        assertEquals(RemovalOutcome.Done(githubUpdated = true, warnings = emptyList()), outcome)

        val init = cli.calls.indexOf("stack init --base main auth ui")
        val unstack = cli.calls.indexOf("gh api --hostname github.com -X POST repos/octo/app/stacks/7/unstack")
        val close = cli.calls.indexOf("gh pr close 101")
        val submit = cli.calls.indexOf("stack submit --auto")
        assertTrue(init in 0 until unstack, cli.calls.toString())
        assertTrue(unstack < close && close < submit, cli.calls.toString())
    }

    @Test
    fun `follow-up failures are reported as warnings`() {
        cli.on("gh pr close", exitCode = 1, stderr = "✗ boom")
        cli.on("stack submit", exitCode = 4, stderr = "✗ API down")
        val outcome = fold(RemovalOptions(closePr = true, deleteBranch = false)) as RemovalOutcome.Done
        assertEquals(false, outcome.githubUpdated)
        assertEquals(2, outcome.warnings.size, outcome.warnings.toString())
    }
}
