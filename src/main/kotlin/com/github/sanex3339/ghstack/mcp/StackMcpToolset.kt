package com.github.sanex3339.ghstack.mcp

import com.github.sanex3339.ghstack.ide.EntryStatus
import com.github.sanex3339.ghstack.ide.GhStackOperations
import com.github.sanex3339.ghstack.ide.OperationScope
import com.github.sanex3339.ghstack.ide.StackStateService
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.model.RemoveMode
import com.github.sanex3339.ghstack.ops.DivergenceChoice
import com.github.sanex3339.ghstack.ops.MoveChangesWorkflow
import com.github.sanex3339.ghstack.ops.MoveOutcome
import com.github.sanex3339.ghstack.ops.PrDraft
import com.github.sanex3339.ghstack.ops.Prompts
import com.github.sanex3339.ghstack.ops.PushOutcome
import com.github.sanex3339.ghstack.ops.RebaseChoice
import com.github.sanex3339.ghstack.ops.RemovalOptions
import com.github.sanex3339.ghstack.ops.RemovalOutcome
import com.github.sanex3339.ghstack.ops.RemoveBranchWorkflow
import com.github.sanex3339.ghstack.ops.RepoSnapshot
import com.github.sanex3339.ghstack.ops.StackPusher
import com.github.sanex3339.ghstack.ops.SubmitOutcome
import com.github.sanex3339.ghstack.ops.SubmitRequest
import com.github.sanex3339.ghstack.ops.SubmitWorkflow
import com.github.sanex3339.ghstack.ops.SyncOutcome
import com.github.sanex3339.ghstack.ops.SyncWorkflow
import com.github.sanex3339.ghstack.ops.UndoWorkflow
import com.github.sanex3339.ghstack.ops.InsertBranchWorkflow
import com.github.sanex3339.ghstack.ops.WorkflowAbort
import com.github.sanex3339.ghstack.ops.orAbort
import com.github.sanex3339.ghstack.planning.InsertCheck
import com.github.sanex3339.ghstack.planning.InsertDirection
import com.github.sanex3339.ghstack.planning.InsertPlanner
import com.github.sanex3339.ghstack.planning.RemovalCheck
import com.github.sanex3339.ghstack.planning.RemovePlanner
import com.github.sanex3339.ghstack.state.BranchBadges
import com.github.sanex3339.ghstack.state.RepoState
import com.github.sanex3339.ghstack.ui.GhStackCommands
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/**
 * Stacked PRs tools for agents connected to the IDE's MCP server. They run the same workflows as the UI
 * (batched pushes, recreating the GitHub stack after a restructure, rollback on failure), show up in the
 * activity log, and never open dialogs.
 */
@Suppress("FunctionName", "unused")
class StackMcpToolset : McpToolset {
    @McpTool(name = "stack_view")
    @McpDescription(
        description = """
        Shows the current stacked pull request stack (gh stack) of the project's git repository: every layer from top to
        bottom with its branch, pull request number and URL, merge status (draft, review required, checks failing,
        approved…) and which lower layer blocks merging it, plus any rebase/conflict in progress and other local stacks.
        Call this before and after changing the stack.
        """,
    )
    suspend fun stack_view(): String {
        val project = currentCoroutineContext().project
        return withContext(Dispatchers.IO) {
            val service = StackStateService.getInstance(project)
            val root = service.activeRoot() ?: throw McpExpectedError("The project has no git repository")
            service.refreshNow(root)
            describe(service.state(root))
        }
    }

    @McpTool(name = "stack_checkout")
    @McpDescription(description = "Checks out a branch of a stack (gh stack checkout). Uncommitted changes are carried over when git allows it.")
    suspend fun stack_checkout(@McpDescription(description = "Branch name, PR number, PR URL or stack number") target: String): String =
        run("Check out $target") {
            ensurePushRemote()
            if (stack("checkout", target).ok) success("Switched to $target")
        }

    @McpTool(name = "stack_insert_branch")
    @McpDescription(
        description = """
        Inserts a new empty branch into the current stack right below or above a layer, re-registers the stack and checks
        the new branch out. Commit changes on it, then call stack_submit to open its pull request (the GitHub stack is
        recreated because its order changed). Inserting above the top layer is the same as adding a layer.
        """,
    )
    suspend fun stack_insert_branch(
        @McpDescription(description = "Name of the new branch") name: String,
        @McpDescription(description = "\"below\" (default) or \"above\" the target layer") direction: String = "below",
        @McpDescription(description = "Layer to insert next to; defaults to the current branch") target: String? = null,
    ): String = run("Insert $name", undoable = true) {
        val stack = state.stacks.firstOrNull { s -> target?.let { s.branch(it) != null } ?: s.isCurrent }
            ?: throw WorkflowAbort("Check out a branch of a stack first, or pass a target layer")
        val layer = target ?: state.currentBranch ?: throw WorkflowAbort("No branch is checked out")
        val existing = GhStackCommands.localBranches(project, root)
        val insertDirection = if (direction.equals("above", ignoreCase = true)) InsertDirection.ABOVE else InsertDirection.BELOW
        when (val check = InsertPlanner.plan(stack, layer, insertDirection, name, existing, state.operation)) {
            is InsertCheck.Invalid -> throw WorkflowAbort(check.message)
            InsertCheck.NeedsRebase -> throw WorkflowAbort("Some layers need a rebase first: call stack_rebase, then insert again")
            is InsertCheck.Ready -> {
                InsertBranchWorkflow(cli, gitDir).run(check.plan, state.currentBranch)
                success("Created and checked out ${check.plan.newBranch}")
            }
        }
    }

    @McpTool(name = "stack_remove_branch")
    @McpDescription(
        description = """
        Removes a layer from the current stack. mode "drop" removes its commits from the layers above (they are rebased),
        "fold_down" / "fold_up" keep its commits in the layer below / above. A stack on GitHub is recreated without it.
        Stops on a rebase conflict; resolve it and call stack_rebase_continue.
        """,
    )
    suspend fun stack_remove_branch(
        @McpDescription(description = "Layer to remove") branch: String,
        @McpDescription(description = "\"drop\" (default), \"fold_down\" or \"fold_up\"") mode: String = "drop",
        @McpDescription(description = "Also close its pull request") close_pr: Boolean = true,
        @McpDescription(description = "Also delete the local branch") delete_branch: Boolean = false,
    ): String = run("Remove $branch", undoable = true) {
        val stack = state.stacks.firstOrNull { it.branch(branch) != null } ?: throw WorkflowAbort("\"$branch\" isn't in a local stack")
        val removeMode = when (mode.lowercase()) {
            "fold_down", "fold-down" -> RemoveMode.FOLD_DOWN
            "fold_up", "fold-up" -> RemoveMode.FOLD_UP
            else -> RemoveMode.DROP
        }
        when (val check = RemovePlanner.plan(stack, branch, removeMode, state.operation)) {
            is RemovalCheck.Invalid -> throw WorkflowAbort(check.message)
            RemovalCheck.NeedsRebase -> throw WorkflowAbort("Some layers need a rebase first: call stack_rebase, then remove again")
            is RemovalCheck.Ready -> {
                val snapshot = RepoSnapshot(stack, state.currentBranch.orEmpty(), state.repository, state.operation)
                when (val outcome = RemoveBranchWorkflow(cli, gitDir).start(check.plan, RemovalOptions(close_pr, delete_branch), snapshot)) {
                    is RemovalOutcome.Done -> {
                        success("Removed $branch" + if (outcome.githubUpdated) " locally and on GitHub" else " locally")
                        outcome.warnings.forEach { warn(it) }
                    }
                    is RemovalOutcome.StoppedOnConflict ->
                        warn("Stopped on a conflict while rebasing ${outcome.branch}", "Resolve the conflicted files, `git add` them, then call stack_rebase_continue")
                }
            }
        }
    }

    @McpTool(name = "stack_move_changes")
    @McpDescription(
        description = """
        Moves uncommitted changes of the given files into another layer of the current stack: commits them there with the
        message, rebases the layers above so they include the commit, and returns to the current branch.
        """,
    )
    suspend fun stack_move_changes(
        @McpDescription(description = "Repository-relative paths of the changed files to move") paths: List<String>,
        @McpDescription(description = "Layer (branch) to commit them on") target: String,
        @McpDescription(description = "Commit message") message: String,
    ): String = run("Move changes to $target", undoable = true) {
        val current = state.currentBranch ?: throw WorkflowAbort("No branch is checked out")
        when (MoveChangesWorkflow(cli).run(paths, target, message, current)) {
            MoveOutcome.Moved -> success("Committed ${paths.size} file(s) on $target and rebased the layers above it")
            is MoveOutcome.StoppedOnConflict ->
                warn("Committed on $target, but rebasing the layers above stopped on a conflict", "Resolve it, `git add` the files, then call stack_rebase_continue")
        }
    }

    @McpTool(name = "stack_rebase")
    @McpDescription(description = "Cascading rebase of the current stack (gh stack rebase). scope: \"stack\" (default), \"upstack\" (current branch to top) or \"downstack\" (trunk to current branch).")
    suspend fun stack_rebase(@McpDescription(description = "\"stack\", \"upstack\" or \"downstack\"") scope: String = "stack"): String =
        run("Rebase $scope", undoable = true) {
            ensurePushRemote()
            val args = when (scope.lowercase()) {
                "upstack" -> arrayOf("rebase", "--upstack")
                "downstack" -> arrayOf("rebase", "--downstack")
                else -> arrayOf("rebase")
            }
            if (stack(*args).ok) success("Rebased")
        }

    @McpTool(name = "stack_rebase_continue")
    @McpDescription(description = "Continues a stack rebase or branch removal that stopped on a conflict. Resolve and `git add` the conflicted files first.")
    suspend fun stack_rebase_continue(): String = run("Continue rebase") {
        when (state.operation) {
            is OperationState.RemovalStopped -> when (val outcome = RemoveBranchWorkflow(cli, gitDir).resume()) {
                is RemovalOutcome.Done -> success("Removal finished")
                is RemovalOutcome.StoppedOnConflict -> warn("Stopped on another conflict in ${outcome.branch}")
            }
            else -> if (stack("rebase", "--continue").ok) success("Rebase finished")
        }
    }

    @McpTool(name = "stack_rebase_abort")
    @McpDescription(description = "Aborts a stopped stack rebase or branch removal and puts every branch back where it was.")
    suspend fun stack_rebase_abort(): String = run("Abort rebase") {
        when (state.operation) {
            is OperationState.RemovalStopped -> RemoveBranchWorkflow(cli, gitDir).abort()
            else -> stack("rebase", "--abort").orAbort("Aborting the rebase")
        }
        success("Aborted; every branch is back where it was")
    }

    @McpTool(name = "stack_push")
    @McpDescription(description = "Pushes every unmerged layer of the current stack with --force-with-lease (in batches when the repository limits branches per push). Doesn't create pull requests.")
    suspend fun stack_push(): String = run("Push") {
        ensurePushRemote()
        when (val outcome = StackPusher(cli).push()) {
            is PushOutcome.Pushed -> success(outcome.batchSize?.let { "Pushed in batches of $it" } ?: "Pushed")
            is PushOutcome.Failed -> report(outcome.result, "gh stack push")
        }
    }

    @McpTool(name = "stack_submit")
    @McpDescription(
        description = """
        Publishes the current stack: rebases if needed, pushes, opens pull requests for layers without one (titles from
        their commit, editable later with `gh pr edit`), recreates the stack on GitHub when its order changed, and links
        everything (gh stack submit).
        """,
    )
    suspend fun stack_submit(
        @McpDescription(description = "Create new pull requests as drafts") draft: Boolean = false,
        @McpDescription(description = "Also mark existing pull requests ready for review") mark_ready: Boolean = false,
    ): String = run("Submit", prompts = AgentPrompts(draft = draft, markReady = mark_ready)) {
        ensurePushRemote()
        when (SubmitWorkflow(cli, gitDir, prompts, draft).run(snapshot())) {
            SubmitOutcome.SUBMITTED -> success("Stack submitted")
            SubmitOutcome.STOPPED_ON_CONFLICT -> warn("Rebase stopped on a conflict", "Resolve it, then call stack_rebase_continue and stack_submit again")
            SubmitOutcome.STACKS_UNAVAILABLE -> error("Stacked PRs aren't enabled for this repository")
            SubmitOutcome.CANCELLED -> error("Submit was cancelled")
        }
    }

    @McpTool(name = "stack_sync")
    @McpDescription(description = "Syncs the current stack with GitHub: fetch, rebase onto the updated trunk, push, refresh pull request state (gh stack sync). prune deletes local branches of merged pull requests.")
    suspend fun stack_sync(@McpDescription(description = "Delete local branches of merged pull requests") prune: Boolean = false): String =
        run("Sync", undoable = true) {
            ensurePushRemote()
            when (SyncWorkflow(cli, gitDir, prompts).run(prune, snapshot())) {
                SyncOutcome.SYNCED, SyncOutcome.SYNCED_PUSHED_IN_BATCHES -> success("Synced")
                SyncOutcome.CONFLICT -> warn("Sync hit a conflict and changed nothing", "Call stack_rebase to resolve the conflicts one layer at a time")
                SyncOutcome.STACKS_UNAVAILABLE -> error("Stacked PRs aren't enabled for this repository")
                SyncOutcome.OPEN_TERMINAL, SyncOutcome.CANCELLED ->
                    error("The stack changed both locally and on GitHub", "Ask the user to resolve the divergence from the Stacked PRs tool window")
            }
        }

    @McpTool(name = "stack_mark_ready")
    @McpDescription(description = "Marks draft pull requests ready for review (drafts block merging the stack). Without numbers, marks every draft in the current stack.")
    suspend fun stack_mark_ready(@McpDescription(description = "Pull request numbers; empty for all drafts in the stack") pr_numbers: List<Int> = emptyList()): String =
        run("Mark ready for review") {
            val numbers = pr_numbers.ifEmpty { state.currentStack?.draftPrNumbers.orEmpty() }
            if (numbers.isEmpty()) {
                info("No draft pull requests")
            } else {
                numbers.forEach { cli.gh("pr", "ready", it.toString()).orAbort("Marking #$it ready for review") }
                success("Marked ${numbers.joinToString(", ") { "#$it" }} ready for review")
            }
        }

    @McpTool(name = "stack_undo")
    @McpDescription(description = "Undoes the last local stack operation (rebase, insert, remove, move, sync): restores branch positions and the stack. Changes already pushed to GitHub stay.")
    suspend fun stack_undo(): String = run("Undo") {
        UndoWorkflow(cli, gitDir).undo()
        success("Restored the stack to before the last operation")
    }

    private suspend fun run(
        title: String,
        undoable: Boolean = false,
        prompts: Prompts = AgentPrompts(),
        body: OperationScope.() -> Unit,
    ): String {
        val project = currentCoroutineContext().project
        val job = currentCoroutineContext().job
        return withContext(Dispatchers.IO) {
            val ops = GhStackOperations.getInstance(project)
            val result = try {
                ops.runAndWait(title, prompts, undoable, isCancelled = { job.isCancelled }, body)
            } catch (e: WorkflowAbort) {
                throw McpExpectedError(e.message.orEmpty())
            }
            val service = StackStateService.getInstance(project)
            service.activeRoot()?.let(service::refreshNow)
            val summary = buildString {
                appendLine("$title: ${result.status.name.lowercase()}")
                appendLine(result.text.trim())
                appendLine()
                append(service.activeState()?.let(::describe) ?: "")
            }
            if (result.status == EntryStatus.FAILED) throw McpExpectedError(summary)
            summary
        }
    }

    private fun describe(state: RepoState): String = buildString {
        val stack = state.currentStack
        if (stack == null) {
            appendLine("The current branch (${state.currentBranch ?: "detached HEAD"}) isn't part of a stack.")
        } else {
            appendLine("${stack.title} on ${stack.trunk}; current branch: ${state.currentBranch ?: "detached HEAD"}")
            appendLine("Layers, top to bottom:")
            stack.branches.asReversed().forEach { branch ->
                val pr = branch.pr?.let { " #${it.number}${it.url?.let { url -> " $url" } ?: ""}" }.orEmpty()
                val title = branch.details?.title?.takeIf { it.isNotBlank() }?.let { " \"$it\"" }.orEmpty()
                val badges = BranchBadges.of(branch, stack).joinToString("; ") { it.text }
                appendLine("- ${branch.name}$pr$title: $badges${if (branch.isCurrent) " (current)" else ""}")
            }
            appendLine("- ${stack.trunk} (trunk)")
        }
        when (val operation = state.operation) {
            is OperationState.RebaseConflict ->
                appendLine("A stack rebase is stopped on ${operation.branch ?: "a branch"}; conflicted files: ${state.conflictedFiles.ifEmpty { listOf("none") }.joinToString(", ")}")
            is OperationState.RemovalStopped ->
                appendLine("Removing ${operation.removed} is stopped on ${operation.branch ?: "a branch"}; conflicted files: ${state.conflictedFiles.ifEmpty { listOf("none") }.joinToString(", ")}")
            is OperationState.ModifyInterrupted -> appendLine("A gh stack modify session was interrupted (${operation.phase})")
            OperationState.ModifyPendingSubmit -> appendLine("The stack was restructured locally; call stack_submit to update GitHub")
            OperationState.None -> Unit
        }
        val others = state.stacks.filter { !it.isCurrent }
        if (others.isNotEmpty()) appendLine("Other local stacks: " + others.joinToString("; ") { s -> "${s.title} (${s.activeBranches.joinToString(", ") { it.name }})" })
    }
}

/** Non-interactive answers for agent runs: no dialogs, conservative defaults. */
private class AgentPrompts(private val draft: Boolean = false, private val markReady: Boolean = false) : Prompts {
    override fun confirm(title: String, message: String, yesText: String) = true
    override fun chooseRebaseBeforeSubmit(branchCount: Int) = RebaseChoice.REBASE
    override fun editPrDrafts(drafts: List<PrDraft>) = SubmitRequest(drafts.map { it.copy(draft = draft) }, markReady)
    override fun chooseDivergenceResolution(details: String) = DivergenceChoice.CANCEL
    override fun chooseRemote(remotes: List<String>): String? =
        throw WorkflowAbort("This repository has several remotes (${remotes.joinToString()}); ask the user to run `git config remote.pushDefault <remote>`")
}
