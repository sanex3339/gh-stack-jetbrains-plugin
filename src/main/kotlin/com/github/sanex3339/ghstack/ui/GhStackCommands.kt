package com.github.sanex3339.ghstack.ui

import com.github.sanex3339.ghstack.cli.CliStatus
import com.github.sanex3339.ghstack.cli.CommandRequest
import com.github.sanex3339.ghstack.ide.GhStackConsole
import com.github.sanex3339.ghstack.ide.GhStackNotifier
import com.github.sanex3339.ghstack.ide.GhStackOperations
import com.github.sanex3339.ghstack.ide.IdeEnvironment
import com.github.sanex3339.ghstack.ide.StackStateService
import com.github.sanex3339.ghstack.model.BranchStatus
import com.github.sanex3339.ghstack.model.StackUi
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.model.RemoveMode
import com.github.sanex3339.ghstack.ops.InsertBranchWorkflow
import com.github.sanex3339.ghstack.ops.RemovalOutcome
import com.github.sanex3339.ghstack.ops.RemoveBranchWorkflow
import com.github.sanex3339.ghstack.ops.RepoSnapshot
import com.github.sanex3339.ghstack.ops.SubmitOutcome
import com.github.sanex3339.ghstack.ops.SubmitWorkflow
import com.github.sanex3339.ghstack.ops.SyncOutcome
import com.github.sanex3339.ghstack.ops.SyncWorkflow
import com.github.sanex3339.ghstack.ops.orAbort
import com.github.sanex3339.ghstack.planning.BranchNames
import com.github.sanex3339.ghstack.planning.InsertCheck
import com.github.sanex3339.ghstack.planning.InsertDirection
import com.github.sanex3339.ghstack.planning.InsertPlanner
import com.github.sanex3339.ghstack.planning.RemovalCheck
import com.github.sanex3339.ghstack.planning.RemovePlanner
import com.github.sanex3339.ghstack.settings.GhStackConfigurable
import com.github.sanex3339.ghstack.settings.GhStackSettings
import com.github.sanex3339.ghstack.state.RepoState
import com.github.sanex3339.ghstack.terminal.GhStackTerminal
import com.github.sanex3339.ghstack.terminal.TerminalCommands
import com.github.sanex3339.ghstack.ui.dialogs.AddBranchDialog
import com.github.sanex3339.ghstack.ui.dialogs.MergeDialog
import com.github.sanex3339.ghstack.ui.dialogs.NewStackDialog
import com.github.sanex3339.ghstack.ui.dialogs.RemoveBranchDialog
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

enum class RebaseMode(val title: String, vararg val args: String) {
    STACK("Rebase stack", "rebase"),
    UPSTACK("Rebase upstack", "rebase", "--upstack"),
    DOWNSTACK("Rebase downstack", "rebase", "--downstack"),
}

/** Everything the UI can ask Stacked PRs to do. Entry points are called on the EDT. */
object GhStackCommands {
    private fun ops(project: Project) = GhStackOperations.getInstance(project)

    private fun state(project: Project): RepoState? = StackStateService.getInstance(project).activeState()

    private fun invokeLater(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block)

    fun localBranches(project: Project, root: Path): Set<String> =
        StackStateService.getInstance(project).repository(root)?.branches?.localBranches?.map { it.name }?.toSet().orEmpty()

    // ── Navigation ────────────────────────────────────────────────────────────

    fun navigate(project: Project, vararg args: String) = ops(project).run("Switch branch", progressText = "Switching branch…") {
        if (args.first() == "trunk") ensurePushRemote()
        stack(*args)
    }

    fun checkout(project: Project, branch: String) = ops(project).run("Check out $branch", progressText = "Switching to $branch…") {
        ensurePushRemote()
        stack("checkout", branch)
    }

    fun checkoutStack(project: Project) {
        val target = Messages.showInputDialog(project, "Stack number, PR number, PR URL or branch name:", "Check Out Stack", null)?.trim()
        if (target.isNullOrEmpty()) return
        ops(project).run("Check out stack $target") {
            ensurePushRemote()
            stack("checkout", target)
        }
    }

    // ── Remote operations ─────────────────────────────────────────────────────

    fun sync(project: Project, prune: Boolean) = ops(project).run(if (prune) "Sync and prune" else "Sync") {
        ensurePushRemote()
        when (SyncWorkflow(cli, gitDir, prompts).run(prune, snapshot())) {
            SyncOutcome.SYNCED -> GhStackNotifier.info(project, "Stack synced")
            SyncOutcome.CONFLICT -> GhStackNotifier.warn(
                project,
                "Sync hit a conflict, so nothing was changed",
                "Rebase the stack to resolve the conflicts one branch at a time.",
                GhStackNotifier.action("Rebase to resolve") { rebase(project, RebaseMode.STACK) },
            )
            SyncOutcome.STACKS_UNAVAILABLE -> {
                StackStateService.getInstance(project).markStacksUnavailable(root)
                GhStackNotifier.error(project, "Stacked PRs aren't enabled for this repository")
            }
            SyncOutcome.OPEN_TERMINAL -> invokeLater { runInTerminal(project, "sync") }
            SyncOutcome.CANCELLED -> Unit
        }
    }

    fun push(project: Project) = ops(project).run("Push") {
        ensurePushRemote()
        stack("push")
    }

    fun submit(project: Project) = ops(project).run("Submit") {
        ensurePushRemote()
        val workflow = SubmitWorkflow(cli, gitDir, prompts, GhStackSettings.getInstance().state.draftByDefault)
        when (workflow.run(snapshot())) {
            SubmitOutcome.SUBMITTED -> GhStackNotifier.info(project, "Stack submitted")
            SubmitOutcome.STOPPED_ON_CONFLICT -> GhStackNotifier.warn(
                project,
                "Rebase stopped on a conflict",
                "Resolve it, continue the rebase, then submit again.",
                GhStackNotifier.action("Resolve conflicts…") { resolveConflicts(project) },
            )
            SubmitOutcome.STACKS_UNAVAILABLE -> {
                StackStateService.getInstance(project).markStacksUnavailable(root)
                GhStackNotifier.error(project, "Stacked PRs aren't enabled for this repository")
            }
            SubmitOutcome.CANCELLED -> Unit
        }
    }

    fun submitInTerminal(project: Project) = runInTerminal(project, "submit")

    fun merge(project: Project) {
        val stack = state(project)?.currentStack ?: return
        val candidates = stack.activeBranches.filter { it.pr != null && it.status != BranchStatus.QUEUED }
        if (candidates.isEmpty()) {
            Messages.showInfoMessage(project, "There are no open pull requests in this stack. Submit it first.", "Merge Stack")
            return
        }
        val settings = GhStackSettings.getInstance()
        val dialog = MergeDialog(project, candidates, settings.initialMergeMethod())
        if (!dialog.showAndGet()) return
        val pr = dialog.selectedPr()
        val method = dialog.method()
        settings.state.lastMergeMethod = method
        ops(project).run("Merge stack") {
            val result = stack("merge", pr.toString(), "--yes", method.flag)
            if (result.ok) {
                GhStackNotifier.info(project, "Merge of #$pr started", result.summary(), GhStackNotifier.action("Sync and prune") { sync(project, prune = true) })
            }
        }
    }

    // ── Rebase & conflicts ────────────────────────────────────────────────────

    fun rebase(project: Project, mode: RebaseMode, fromBranch: String? = null) = ops(project).run(mode.title) {
        ensurePushRemote()
        if (fromBranch != null && fromBranch != state.currentBranch) cli.git("checkout", fromBranch).orAbort("Switching to $fromBranch")
        stack(*mode.args)
    }

    fun rebaseContinue(project: Project) {
        if (state(project)?.operation is OperationState.RemovalStopped) removeContinue(project) else continueStackRebase(project)
    }

    private fun continueStackRebase(project: Project) = ops(project).run("Continue rebase") {
        val unmerged = ConflictResolver.unmergedFiles(cli)
        if (unmerged.isNotEmpty()) {
            invokeLater { ConflictResolver.showMergeDialog(project, unmerged) }
            GhStackNotifier.info(project, "Resolve the remaining conflicts first", "Then click Continue again.")
        } else {
            stack("rebase", "--continue")
        }
    }

    fun rebaseAbort(project: Project) {
        if (state(project)?.operation is OperationState.RemovalStopped) return removeAbort(project)
        val confirmed = MessageDialogBuilder.yesNo("Abort Rebase", "Abort the stack rebase and restore every branch to where it was?")
            .yesText("Abort Rebase").noText("Keep Going").ask(project)
        if (confirmed) ops(project).run("Abort rebase") { stack("rebase", "--abort") }
    }

    fun resolveConflicts(project: Project) = ops(project).run("Find conflicts") {
        val files = ConflictResolver.unmergedFiles(cli)
        invokeLater {
            if (files.isEmpty()) {
                GhStackNotifier.info(project, "No conflicted files", "Click Continue to resume the rebase.")
            } else {
                ConflictResolver.showMergeDialog(project, files)
            }
        }
    }

    // ── Structure ─────────────────────────────────────────────────────────────

    fun addBranch(project: Project) {
        val repoState = state(project) ?: return
        val stack = repoState.currentStack ?: return
        val top = stack.topBranch?.name ?: stack.trunk
        val dialog = AddBranchDialog(project, top)
        if (!dialog.showAndGet()) return
        val request = dialog.request()
        ops(project).run("Add branch") {
            // gh stack add only works from the top of the stack.
            if (state.currentBranch != top) cli.git("checkout", top).orAbort("Switching to $top")
            stack(*request.args().toTypedArray())
        }
    }

    fun insert(project: Project, direction: InsertDirection, targetBranch: String? = null) {
        val repoState = state(project) ?: return
        val stack = targetBranch?.let { name -> repoState.stacks.firstOrNull { it.branch(name) != null } } ?: repoState.currentStack ?: return
        val target = targetBranch ?: repoState.currentBranch ?: return
        val existing = localBranches(project, repoState.root)
        val title = if (direction == InsertDirection.BELOW) "Insert Branch Below" else "Insert Branch Above"
        val validator = object : InputValidatorEx {
            override fun getErrorText(inputString: String): String? {
                val name = inputString.trim()
                return BranchNames.validationError(name) ?: if (name in existing) "Branch \"$name\" already exists" else null
            }
        }
        val name = Messages.showInputDialog(project, "New branch ${insertPosition(stack, target, direction)}:", title, null, "", validator)?.trim() ?: return
        when (val check = InsertPlanner.plan(stack, target, direction, name, existing, repoState.operation)) {
            is InsertCheck.Invalid -> Messages.showErrorDialog(project, check.message, "Can't Insert Branch")
            InsertCheck.NeedsRebase -> {
                val rebaseNow = MessageDialogBuilder.yesNo("Stack Needs a Rebase", "Some branches must be rebased before a branch can be inserted. Rebase the stack now? Insert again once it finishes.")
                    .yesText("Rebase").ask(project)
                if (rebaseNow) rebase(project, RebaseMode.STACK)
            }
            is InsertCheck.Ready -> ops(project).run("Insert ${check.plan.newBranch}") {
                InsertBranchWorkflow(cli, gitDir).run(check.plan, state.currentBranch)
                GhStackNotifier.info(project, "Created ${check.plan.newBranch}", "Move changes into it and commit, then Submit to publish it with a pull request.")
            }
        }
    }

    private fun insertPosition(stack: StackUi, target: String, direction: InsertDirection): String {
        val active = stack.activeBranches.map { it.name }
        val index = active.indexOf(target)
        return when (direction) {
            InsertDirection.BELOW -> "between ${stack.activeParentOf(target)} and $target"
            InsertDirection.ABOVE -> active.getOrNull(index + 1)?.let { "between $target and $it" } ?: "on top of $target"
        }
    }

    fun removeBranch(project: Project, targetBranch: String? = null) {
        val repoState = state(project) ?: return
        val stack = targetBranch?.let { name -> repoState.stacks.firstOrNull { it.branch(name) != null } } ?: repoState.currentStack ?: return
        val name = targetBranch ?: repoState.currentBranch ?: return
        val modes = RemovePlanner.availableModes(stack, name)
        if (modes.isEmpty()) {
            val reason = (RemovePlanner.plan(stack, name, RemoveMode.DROP, repoState.operation) as? RemovalCheck.Invalid)?.message
            Messages.showErrorDialog(project, reason ?: "\"$name\" can't be removed from this stack.", "Can't Remove Branch")
            return
        }
        val onGitHub = repoState.repository != null && (stack.number != null || stack.activeBranches.any { it.pr != null })
        val dialog = RemoveBranchDialog(project, stack, name, modes, onGitHub)
        if (!dialog.showAndGet()) return
        val mode = dialog.mode()
        val options = dialog.options()
        when (val check = RemovePlanner.plan(stack, name, mode, repoState.operation)) {
            is RemovalCheck.Invalid -> Messages.showErrorDialog(project, check.message, "Can't Remove Branch")
            RemovalCheck.NeedsRebase -> {
                val rebaseNow = MessageDialogBuilder.yesNo("Stack Needs a Rebase", "Some branches must be rebased before a branch can be removed. Rebase the stack now? Remove again once it finishes.")
                    .yesText("Rebase").ask(project)
                if (rebaseNow) rebase(project, RebaseMode.STACK)
            }
            is RemovalCheck.Ready -> ops(project).run("Remove $name") {
                val snapshot = RepoSnapshot(stack, state.currentBranch.orEmpty(), state.repository, state.operation)
                reportRemoval(project, name, RemoveBranchWorkflow(cli, gitDir).start(check.plan, options, snapshot))
            }
        }
    }

    fun removeContinue(project: Project) = ops(project).run("Continue removing branch") {
        val unmerged = ConflictResolver.unmergedFiles(cli)
        if (unmerged.isNotEmpty()) {
            invokeLater { ConflictResolver.showMergeDialog(project, unmerged) }
            GhStackNotifier.info(project, "Resolve the remaining conflicts first", "Then click Continue again.")
        } else {
            val removed = (state.operation as? OperationState.RemovalStopped)?.removed ?: "branch"
            reportRemoval(project, removed, RemoveBranchWorkflow(cli, gitDir).resume())
        }
    }

    fun removeAbort(project: Project) {
        val confirmed = MessageDialogBuilder.yesNo("Abort Removing Branch", "Stop removing the branch and put every branch back where it was?")
            .yesText("Abort").noText("Keep Going").ask(project)
        if (confirmed) {
            ops(project).run("Abort removing branch") {
                RemoveBranchWorkflow(cli, gitDir).abort()
                GhStackNotifier.info(project, "Branch removal undone")
            }
        }
    }

    private fun reportRemoval(project: Project, removed: String, outcome: RemovalOutcome) {
        when (outcome) {
            is RemovalOutcome.Done -> {
                val where = if (outcome.githubUpdated) "locally and on GitHub" else "locally"
                val details = outcome.warnings.joinToString("\n")
                if (outcome.warnings.isEmpty()) {
                    GhStackNotifier.info(project, "Removed $removed from the stack", "The stack was updated $where.")
                } else {
                    GhStackNotifier.warn(project, "Removed $removed from the stack, with problems", details)
                }
            }
            is RemovalOutcome.StoppedOnConflict -> GhStackNotifier.warn(
                project,
                "Removing $removed stopped on a conflict in ${outcome.branch}",
                "Resolve it, then Continue, or Abort to put everything back.",
                GhStackNotifier.action("Resolve conflicts…") { resolveConflicts(project) },
            )
        }
    }

    fun newStack(project: Project) {
        val repoState = state(project) ?: return
        val dialog = NewStackDialog(project, localBranches(project, repoState.root).sorted())
        if (!dialog.showAndGet()) return
        val request = dialog.request()
        ops(project).run("Create stack") { stack(*request.args().toTypedArray()) }
    }

    fun unstack(project: Project) {
        val stack = state(project)?.currentStack ?: return
        val choice = Messages.showDialog(
            project,
            "Remove ${stack.title} from stack tracking? Pull requests and branches are never deleted.",
            "Unstack",
            arrayOf("Stop Tracking Locally", "Unstack on GitHub and Locally", "Cancel"),
            0,
            Messages.getQuestionIcon(),
        )
        when (choice) {
            0 -> ops(project).run("Unstack locally") { stack("unstack", "--local") }
            1 -> ops(project).run("Unstack") { stack("unstack") }
        }
    }

    fun modify(project: Project) = runInTerminal(project, "modify")

    fun modifyContinue(project: Project) = runInTerminal(project, "modify", "--continue")

    fun modifyAbort(project: Project) {
        val confirmed = MessageDialogBuilder.yesNo("Abort Modify", "Abort the modify session and restore the stack to its previous state?")
            .yesText("Abort Modify").noText("Cancel").ask(project)
        if (confirmed) ops(project).run("Abort modify") { stack("modify", "--abort") }
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    fun runInTerminal(project: Project, vararg args: String) {
        val terminal = GhStackTerminal.getInstance(project)
        if (terminal == null) {
            GhStackNotifier.warn(project, "The Terminal plugin is disabled", "Enable it to run interactive gh stack commands.")
            return
        }
        val repoState = state(project) ?: return
        val status = repoState.cliStatus as? CliStatus.Ready ?: return
        terminal.run(repoState.root, TerminalCommands.ghStack(status.ghPath, *args), "gh stack ${args.first()}")
    }

    fun installExtension(project: Project) {
        val repoState = state(project) ?: return
        val status = repoState.cliStatus as? CliStatus.ExtensionMissing ?: return
        object : Task.Backgroundable(project, "Installing the gh stack extension", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = IdeEnvironment.runner().run(
                    CommandRequest(repoState.root, listOf(status.ghPath, "extension", "install", "github/gh-stack")),
                    GhStackConsole.getInstance(project),
                )
                if (result.ok) GhStackNotifier.info(project, "gh stack installed") else GhStackNotifier.error(project, "Installing gh stack failed", result.summary())
            }

            override fun onFinished() = StackStateService.getInstance(project).recheckCli()
        }.queue()
    }

    fun login(project: Project) {
        val repoState = state(project) ?: return
        val status = repoState.cliStatus as? CliStatus.NotAuthenticated ?: return
        val terminal = GhStackTerminal.getInstance(project)
        if (terminal == null) {
            BrowserUtil.browse("https://cli.github.com/manual/gh_auth_login")
        } else {
            terminal.run(repoState.root, TerminalCommands.gh(status.ghPath, "auth", "login"), "gh auth login")
        }
    }

    // ── Branch helpers ────────────────────────────────────────────────────────

    fun layerDiff(project: Project, root: Path, stack: StackUi, branch: String) = LayerDiff.show(project, root, stack, branch)

    fun openPr(url: String) = BrowserUtil.browse(url)

    fun copyPrUrl(url: String) = CopyPasteManager.getInstance().setContents(StringSelection(url))

    fun showSettings(project: Project) = ShowSettingsUtil.getInstance().showSettingsDialog(project, GhStackConfigurable::class.java)
}
