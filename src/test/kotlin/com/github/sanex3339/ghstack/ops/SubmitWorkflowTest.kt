package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.model.BranchStatus.NEEDS_REBASE
import com.github.sanex3339.ghstack.model.BranchStatus.NOT_SUBMITTED
import com.github.sanex3339.ghstack.model.BranchStatus.OPEN
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.model.RepoCoordinates
import com.github.sanex3339.ghstack.model.StackUi
import com.github.sanex3339.ghstack.testutil.FakePrompts
import com.github.sanex3339.ghstack.testutil.FakeStackCli
import com.github.sanex3339.ghstack.testutil.Stacks.stack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files

class SubmitWorkflowTest {
    private val repo = RepoCoordinates("github.com", "octo", "app")
    private val gitDir = Files.createTempDirectory("ghstack-gitdir")
    private val cli = FakeStackCli()
    private val prompts = FakePrompts()

    /** auth=#100, api-models (new, inserted), api=#101 — stack #7 on GitHub holds [100, 101]. */
    private val inserted = stack("main", "auth" to OPEN, "api-models" to NOT_SUBMITTED, "api" to OPEN, current = "api-models", number = 7, id = "1201")

    private fun run(stack: StackUi, repository: RepoCoordinates? = repo, operation: OperationState = OperationState.None) =
        SubmitWorkflow(cli, gitDir, prompts, draftByDefault = true).run(RepoSnapshot(stack, stack.branches.first { it.isCurrent }.name, repository, operation))

    private fun remoteStack(vararg open: Int) =
        """{"number":7,"pull_requests":[${open.joinToString(",") { "{\"number\":$it,\"state\":\"open\"}" }}]}"""

    @Test
    fun `nothing new and no GitHub coordinates just submits`() {
        val outcome = run(stack("main", "auth" to OPEN, "api" to OPEN, current = "api"), repository = null)
        assertEquals(SubmitOutcome.SUBMITTED, outcome)
        assertEquals(listOf("stack submit --auto"), cli.calls)
        assertEquals(emptyList<String>(), prompts.asked)
    }

    @Test
    fun `publishing an inserted branch creates its PR and recreates the stack on GitHub`() {
        cli.on("git log", stdout = "\u001eAdd models\u001fWhy\n")
        cli.on("gh pr create", stdout = "https://github.com/octo/app/pull/102\n")
        cli.on("gh api --hostname github.com repos/octo/app/stacks/7", stdout = remoteStack(100, 101))

        assertEquals(SubmitOutcome.SUBMITTED, run(inserted))

        assertEquals(listOf(PrDraft("api-models", "auth", "Add models", "Why", draft = true)), prompts.shownDrafts)
        assertEquals(listOf("drafts", "confirm:Recreate Stack on GitHub"), prompts.asked)
        assertEquals(
            listOf(
                "git log --format=%x1e%s%x1f%b auth..api-models",
                "stack push",
                "gh pr create --head api-models --base auth --title Add models --body Why --draft",
                "gh api --hostname github.com repos/octo/app/stacks/7",
                "gh api --hostname github.com -X POST repos/octo/app/stacks/7/unstack",
                "git branch --show-current",
                "stack unstack --local",
                "stack init --base main auth api-models api",
                "git branch --show-current",
                "git checkout api-models",
                "stack submit --auto",
            ),
            cli.calls,
        )
    }

    @Test
    fun `mark ready adds --open`() {
        prompts.editDrafts = { SubmitRequest(it, markReady = true) }
        cli.on("gh pr create", stdout = "https://github.com/octo/app/pull/102\n")
        run(stack("main", "auth" to OPEN, "api" to NOT_SUBMITTED, current = "api"), repository = null)
        assertEquals("stack submit --auto --open", cli.calls.last())
    }

    @Test
    fun `declining the recreate stops before submitting`() {
        prompts.confirmAnswer = false
        cli.on("gh pr create", stdout = "https://github.com/octo/app/pull/102\n")
        cli.on("gh api --hostname github.com repos/octo/app/stacks/7", stdout = remoteStack(100, 101))
        assertEquals(SubmitOutcome.CANCELLED, run(inserted))
        assertFalse(cli.calls.any { it.startsWith("stack submit") || it.contains("/unstack") })
    }

    @Test
    fun `a PR on GitHub that local tracking lacks aborts instead of dropping it`() {
        cli.on("gh pr create", stdout = "https://github.com/octo/app/pull/102\n")
        cli.on("gh api --hostname github.com repos/octo/app/stacks/7", stdout = remoteStack(100, 101, 150))
        val error = assertThrows<WorkflowAbort> { run(inserted) }
        assertTrue(error.message!!.contains("Sync"), error.message)
        assertFalse(cli.calls.any { it.startsWith("stack submit") || it.contains("/unstack") })
    }

    @Test
    fun `a rebase conflict before submitting stops the workflow`() {
        cli.on("stack rebase", exitCode = 3, stderr = "⚠ Rebasing api onto auth — conflict")
        val outcome = run(stack("main", "auth" to OPEN, "api" to NEEDS_REBASE, current = "api"))
        assertEquals(SubmitOutcome.STOPPED_ON_CONFLICT, outcome)
        assertEquals(listOf("stack rebase"), cli.calls)
        assertEquals(listOf("rebase:1"), prompts.asked)
    }

    @Test
    fun `cancelling the rebase question or the PR dialog does nothing`() {
        prompts.rebaseChoice = RebaseChoice.CANCEL
        assertEquals(SubmitOutcome.CANCELLED, run(stack("main", "auth" to OPEN, "api" to NEEDS_REBASE, current = "api")))
        assertEquals(emptyList<String>(), cli.calls)

        prompts.editDrafts = { null }
        assertEquals(SubmitOutcome.CANCELLED, run(inserted))
        assertFalse(cli.calls.any { it == "stack push" })
    }

    @Test
    fun `pending modify skips the recreate check`() {
        val outcome = run(stack("main", "auth" to OPEN, "api" to OPEN, current = "api", number = 7), operation = OperationState.ModifyPendingSubmit)
        assertEquals(SubmitOutcome.SUBMITTED, outcome)
        assertEquals(listOf("stack submit --auto"), cli.calls)
    }

    @Test
    fun `stacked PRs unavailable is reported`() {
        cli.on("stack submit", exitCode = 9, stderr = "✗ Stacked PRs are not enabled")
        assertEquals(SubmitOutcome.STACKS_UNAVAILABLE, run(stack("main", "auth" to OPEN, current = "auth"), repository = null))
    }
}
