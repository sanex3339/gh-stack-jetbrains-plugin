package com.github.sanex3339.ghstack.ui

import com.github.sanex3339.ghstack.cli.CliStatus
import com.github.sanex3339.ghstack.cli.CommandRequest
import com.github.sanex3339.ghstack.ide.EntryStatus
import com.github.sanex3339.ghstack.ide.GhStackNotifier
import com.github.sanex3339.ghstack.ide.GhStackOperations
import com.github.sanex3339.ghstack.ide.IdeEnvironment
import com.github.sanex3339.ghstack.ide.LineKind
import com.github.sanex3339.ghstack.ide.OperationLog
import com.github.sanex3339.ghstack.ide.OperationScope
import com.github.sanex3339.ghstack.ide.StackStateService
import com.github.sanex3339.ghstack.model.BranchStatus
import com.github.sanex3339.ghstack.model.CheckRunInfo
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.model.RemoteStackInfo
import com.github.sanex3339.ghstack.model.RemoveMode
import com.github.sanex3339.ghstack.model.StackUi
import com.github.sanex3339.ghstack.ops.InsertBranchWorkflow
import com.github.sanex3339.ghstack.ops.MoveChangesWorkflow
import com.github.sanex3339.ghstack.ops.MoveOutcome
import com.github.sanex3339.ghstack.ops.PushOutcome
import com.github.sanex3339.ghstack.ops.RemovalOutcome
import com.github.sanex3339.ghstack.ops.RemoveBranchWorkflow
import com.github.sanex3339.ghstack.ops.RepoSnapshot
import com.github.sanex3339.ghstack.ops.StackPusher
import com.github.sanex3339.ghstack.ops.SubmitOutcome
import com.github.sanex3339.ghstack.ops.SubmitWorkflow
import com.github.sanex3339.ghstack.ops.SyncOutcome
import com.github.sanex3339.ghstack.ops.SyncWorkflow
import com.github.sanex3339.ghstack.ops.UndoWorkflow
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
import com.github.sanex3339.ghstack.ui.dialogs.MoveChangesDialog
import com.github.sanex3339.ghstack.ui.dialogs.NewStackDialog
import com.github.sanex3339.ghstack.ui.dialogs.RemoveBranchDialog
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.ChangeListManager
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

enum class RebaseMode(val title: String, vararg val args: String) {
    STACK("Rebase stack", "rebase"),
    UPSTACK("Rebase upstack", "rebase", "--upstack"),
    DOWNSTACK("Rebase downstack", "rebase", "--downstack"),
}

/** Everything the UI can ask Stacked PRs to do. Entry points are called on the EDT. */
object GhStackCommands {
    /** GitHub queues re-run jobs within seconds; refresh once they show up rather than waiting for the minute poll. */
    private const val RERUN_REFRESH_DELAY_MS = 5_000L

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
        if (stack("checkout", branch).ok) success("Switched to $branch")
    }

    fun checkoutStack(project: Project) {
        val target = Messages.showInputDialog(project, "Stack number, PR number, PR URL or branch name:", "Check Out Stack", null)?.trim()
        if (target.isNullOrEmpty()) return
        ops(project).run("Check out stack $target") {
            ensurePushRemote()
            if (stack("checkout", target).ok) success("Checked out $target")
        }
    }

    /** Started by the state service when the checked-out branch is in a stack on GitHub that no local stack tracks. */
    fun pullStack(project: Project, root: Path, branch: String, stack: RemoteStackInfo) =
        ops(project).run("Pull stack #${stack.number} from GitHub", root, progressText = "Pulling stack #${stack.number}…") {
            info("$branch belongs to stack #${stack.number} on GitHub (${stack.prNumbers.size} pull requests); pulling it")
            ensurePushRemote()
            // A bare number would be read as a stack number first.
            val target = if (branch.all(Char::isDigit)) stack.number.toString() else branch
            if (stack("checkout", target).ok) success("Pulled stack #${stack.number}: its branches are tracked locally now")
        }

    // ── Remote operations ─────────────────────────────────────────────────────

    fun sync(project: Project, prune: Boolean) = ops(project).run(if (prune) "Sync and prune" else "Sync", progressText = "Syncing…", undoable = true) {
        ensurePushRemote()
        when (SyncWorkflow(cli, gitDir, prompts).run(prune, snapshot())) {
            SyncOutcome.SYNCED -> success("Stack synced")
            SyncOutcome.SYNCED_PUSHED_IN_BATCHES ->
                success("Stack synced (this repository limits how many branches one push may update, so they were pushed in batches)")
            SyncOutcome.CONFLICT -> warn(
                "Sync hit a conflict, so nothing was changed",
                "Rebase the stack to resolve the conflicts one branch at a time.",
                GhStackNotifier.action("Rebase to resolve") { rebase(project, RebaseMode.STACK) },
            )
            SyncOutcome.STACKS_UNAVAILABLE -> {
                StackStateService.getInstance(project).markStacksUnavailable(root)
                error("Stacked PRs aren't enabled for this repository")
            }
            SyncOutcome.OPEN_TERMINAL -> invokeLater { runInTerminal(project, "sync") }
            SyncOutcome.CANCELLED -> info("Sync cancelled")
        }
    }

    fun push(project: Project) = ops(project).run("Push", progressText = "Pushing…") {
        ensurePushRemote()
        when (val outcome = StackPusher(cli).push()) {
            is PushOutcome.Pushed -> success(
                outcome.batchSize?.let { "Pushed in batches of $it (this repository limits how many branches one push may update)" } ?: "Pushed",
            )
            is PushOutcome.Failed -> report(outcome.result, "gh stack push")
        }
    }

    fun submit(project: Project) = ops(project).run("Submit", progressText = "Submitting…") {
        ensurePushRemote()
        val workflow = SubmitWorkflow(cli, gitDir, prompts, GhStackSettings.getInstance().state.draftByDefault)
        when (workflow.run(snapshot())) {
            SubmitOutcome.SUBMITTED -> success("Stack submitted")
            SubmitOutcome.STOPPED_ON_CONFLICT -> {
                warn("Rebase stopped on a conflict", "Resolve it, continue the rebase, then submit again.")
                openConflicts()
            }
            SubmitOutcome.STACKS_UNAVAILABLE -> {
                StackStateService.getInstance(project).markStacksUnavailable(root)
                error("Stacked PRs aren't enabled for this repository")
            }
            SubmitOutcome.CANCELLED -> info("Submit cancelled")
        }
    }

    fun submitInTerminal(project: Project) = runInTerminal(project, "submit")

    fun markReady(project: Project, prNumbers: List<Int>) {
        if (prNumbers.isEmpty()) return
        val what = if (prNumbers.size == 1) "#${prNumbers.single()}" else "${prNumbers.size} pull requests"
        ops(project).run("Mark ready for review", progressText = "Marking $what ready…") {
            prNumbers.forEach { cli.gh("pr", "ready", it.toString()).orAbort("Marking #$it ready for review") }
            success("Marked $what ready for review")
        }
    }

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
        ops(project).run("Merge stack", progressText = "Merging…") {
            if (stack("merge", pr.toString(), "--yes", method.flag).ok) {
                success("Merge of #$pr started; sync and prune once GitHub has merged it")
                GhStackNotifier.info(project, "Merge of #$pr started", "Sync and prune once GitHub has merged it.", GhStackNotifier.action("Sync and prune") { sync(project, prune = true) })
            }
        }
    }

    // ── Rebase & conflicts ────────────────────────────────────────────────────

    fun rebase(project: Project, mode: RebaseMode, fromBranch: String? = null) = ops(project).run(mode.title, undoable = true) {
        ensurePushRemote()
        if (fromBranch != null && fromBranch != state.currentBranch) cli.git("checkout", fromBranch).orAbort("Switching to $fromBranch")
        if (stack(*mode.args).ok) success("${mode.title}: done")
    }

    fun rebaseContinue(project: Project) {
        if (state(project)?.operation is OperationState.RemovalStopped) removeContinue(project) else continueStackRebase(project)
    }

    private fun continueStackRebase(project: Project) = ops(project).run("Continue rebase") {
        if (!openRemainingConflicts()) {
            if (stack("rebase", "--continue").ok) success("Stack rebase finished")
        }
    }

    fun rebaseAbort(project: Project) {
        if (state(project)?.operation is OperationState.RemovalStopped) return removeAbort(project)
        val confirmed = MessageDialogBuilder.yesNo("Abort Rebase", "Abort the stack rebase and restore every branch to where it was?")
            .yesText("Abort Rebase").noText("Keep Going").ask(project)
        if (confirmed) ops(project).run("Abort rebase") { if (stack("rebase", "--abort").ok) success("Rebase aborted; every branch is back where it was") }
    }

    fun resolveConflicts(project: Project) = ops(project).run("Find conflicts") {
        if (!openRemainingConflicts()) info("No conflicted files. Continue to resume the rebase.")
    }

    /** Opens the merge tool when conflicts remain; `true` if there were some. */
    private fun OperationScope.openRemainingConflicts(): Boolean {
        val files = ConflictResolver.unmergedFiles(cli)
        if (files.isEmpty()) return false
        info("${files.size} conflicted file(s): ${files.joinToString(", ") { root.relativize(it).toString() }}")
        invokeLater { ConflictResolver.resolve(project, files) }
        return true
    }

    // ── Structure ─────────────────────────────────────────────────────────────

    fun addBranch(project: Project) {
        val repoState = state(project) ?: return
        val stack = repoState.currentStack ?: return
        val top = stack.topBranch?.name ?: stack.trunk
        val dialog = AddBranchDialog(project, top)
        if (!dialog.showAndGet()) return
        val request = dialog.request()
        ops(project).run("Add branch", undoable = true) {
            // gh stack add only works from the top of the stack.
            if (state.currentBranch != top) cli.git("checkout", top).orAbort("Switching to $top")
            if (stack(*request.args().toTypedArray()).ok) success("Added a branch on top of $top")
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
            is InsertCheck.Ready -> ops(project).run("Insert ${check.plan.newBranch}", undoable = true) {
                InsertBranchWorkflow(cli, gitDir).run(check.plan, state.currentBranch)
                success("Created ${check.plan.newBranch}. Move changes into it and commit, then Submit to publish it with a pull request.")
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
            is RemovalCheck.Ready -> ops(project).run("Remove $name", undoable = true) {
                val snapshot = RepoSnapshot(stack, state.currentBranch.orEmpty(), state.repository, state.operation)
                reportRemoval(name, RemoveBranchWorkflow(cli, gitDir).start(check.plan, options, snapshot))
            }
        }
    }

    fun removeContinue(project: Project) = ops(project).run("Continue removing branch") {
        if (!openRemainingConflicts()) {
            val removed = (state.operation as? OperationState.RemovalStopped)?.removed ?: "branch"
            reportRemoval(removed, RemoveBranchWorkflow(cli, gitDir).resume())
        }
    }

    fun removeAbort(project: Project) {
        val confirmed = MessageDialogBuilder.yesNo("Abort Removing Branch", "Stop removing the branch and put every branch back where it was?")
            .yesText("Abort").noText("Keep Going").ask(project)
        if (confirmed) {
            ops(project).run("Abort removing branch") {
                RemoveBranchWorkflow(cli, gitDir).abort()
                success("Branch removal undone")
            }
        }
    }

    private fun OperationScope.reportRemoval(removed: String, outcome: RemovalOutcome) {
        when (outcome) {
            is RemovalOutcome.Done -> {
                val where = if (outcome.githubUpdated) "locally and on GitHub" else "locally"
                if (outcome.warnings.isEmpty()) {
                    success("Removed $removed from the stack, $where")
                } else {
                    warn("Removed $removed from the stack, with problems", outcome.warnings.joinToString("\n"))
                }
            }
            is RemovalOutcome.StoppedOnConflict -> {
                warn("Removing $removed stopped on a conflict in ${outcome.branch}", "Resolve it, then Continue, or Abort to put everything back.")
                openConflicts()
            }
        }
    }

    /**
     * Moves uncommitted changes into another layer of the current stack. [preselected] files (e.g. the
     * selection in the Commit tool window) start checked; [target] preselects the destination layer.
     */
    fun moveChanges(project: Project, preselected: Collection<Path> = emptyList(), target: String? = null) {
        val repoState = state(project) ?: return
        val stack = repoState.currentStack ?: return
        val current = repoState.currentBranch ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        val changed = changedFiles(project, repoState.root)
        if (changed.isEmpty()) {
            Messages.showInfoMessage(project, "There are no uncommitted changes to move.", "Move Changes to Layer")
            return
        }
        val layers = stack.activeBranches.map { it.name }.filter { it != current }
        if (layers.isEmpty()) {
            Messages.showInfoMessage(project, "The stack has no other layer to move changes to.", "Move Changes to Layer")
            return
        }
        val selected = preselected.mapNotNull { path -> runCatching { repoState.root.relativize(path).toString() }.getOrNull() }.toSet()
        val dialog = MoveChangesDialog(project, changed, selected.ifEmpty { changed.toSet() }, layers, target ?: stack.activeParentOf(current).takeIf { it in layers } ?: layers.first())
        if (!dialog.showAndGet()) return
        val request = dialog.request()
        ops(project).run("Move changes to ${request.target}", progressText = "Moving changes to ${request.target}…", undoable = true) {
            when (MoveChangesWorkflow(cli).run(request.paths, request.target, request.message, current)) {
                MoveOutcome.Moved -> success("Committed ${request.paths.size} file(s) on ${request.target} and rebased the layers above it")
                is MoveOutcome.StoppedOnConflict -> {
                    warn("The changes are committed on ${request.target}, but rebasing the layers above stopped on a conflict", "Resolve it, then Continue.")
                    openConflicts()
                }
            }
        }
    }

    private fun changedFiles(project: Project, root: Path): List<String> {
        val manager = ChangeListManager.getInstance(project)
        val tracked = manager.allChanges.mapNotNull { (it.afterRevision ?: it.beforeRevision)?.file?.path }
        val untracked = manager.unversionedFilesPaths.map { it.path }
        return (tracked + untracked)
            .mapNotNull { path -> Path.of(path).takeIf { it.startsWith(root) }?.let { root.relativize(it).toString() } }
            .distinct()
            .sorted()
    }

    fun undo(project: Project) {
        val root = state(project)?.root ?: return
        val title = ops(project).undoTitle(root) ?: return
        val confirmed = MessageDialogBuilder.yesNo(
            "Undo $title",
            "Put every branch and the stack back to where they were before \"$title\"?\n\n" +
                "Only local branches are restored. Anything already pushed or changed on GitHub stays; push or submit again afterwards.",
        ).yesText("Undo").noText("Cancel").ask(project)
        if (!confirmed) return
        ops(project).run("Undo $title", progressText = "Undoing $title…") {
            UndoWorkflow(cli, gitDir).undo()
            GhStackOperations.getInstance(project).forgetUndo(root)
            success("Restored the stack to before \"$title\"")
        }
    }

    fun newStack(project: Project) {
        val repoState = state(project) ?: return
        val dialog = NewStackDialog(project, localBranches(project, repoState.root).sorted())
        if (!dialog.showAndGet()) return
        val request = dialog.request()
        ops(project).run("Create stack", undoable = true) { if (stack(*request.args().toTypedArray()).ok) success("Stack created") }
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
            0 -> ops(project).run("Unstack locally", undoable = true) { if (stack("unstack", "--local").ok) success("Stopped tracking ${stack.title} locally") }
            1 -> ops(project).run("Unstack") { if (stack("unstack").ok) success("Unstacked ${stack.title}") }
        }
    }

    fun modify(project: Project) = runInTerminal(project, "modify")

    fun modifyContinue(project: Project) = runInTerminal(project, "modify", "--continue")

    fun modifyAbort(project: Project) {
        val confirmed = MessageDialogBuilder.yesNo("Abort Modify", "Abort the modify session and restore the stack to its previous state?")
            .yesText("Abort Modify").noText("Cancel").ask(project)
        if (confirmed) ops(project).run("Abort modify") { if (stack("modify", "--abort").ok) success("Modify aborted") }
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
        val log = OperationLog.getInstance(project)
        object : Task.Backgroundable(project, "Installing the gh stack extension", false) {
            override fun run(indicator: ProgressIndicator) {
                val entry = log.start("Install gh stack", "Installing the gh stack extension…", repoState.root)
                val result = IdeEnvironment.runner().run(
                    CommandRequest(repoState.root, listOf(status.ghPath, "extension", "install", "github/gh-stack")),
                    log.sink(entry),
                )
                if (result.ok) {
                    log.add(entry, "gh stack installed", LineKind.SUCCESS)
                } else {
                    log.add(entry, "Installing gh stack failed: ${result.summary()}", LineKind.ERROR)
                    GhStackNotifier.error(project, "Installing gh stack failed", result.summary())
                }
                log.finish(entry, if (result.ok) EntryStatus.SUCCEEDED else EntryStatus.FAILED)
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

    /** `gh run rerun <run> --failed` for the workflow run a failing check belongs to (from the checks box). */
    fun rerunFailedJobs(project: Project, root: Path, check: CheckRunInfo) {
        val runId = check.runId ?: return
        val workflow = check.workflow ?: "workflow"
        ops(project).run("Re-run failed jobs of $workflow", root, progressText = "Re-running $workflow…") {
            val repo = state.repository?.let { listOf("--repo", "${it.host}/${it.owner}/${it.name}") }.orEmpty()
            val result = cli.gh("run", "rerun", runId.toString(), "--failed", *repo.toTypedArray())
            if (result.ok) {
                success("Re-running the failed jobs of $workflow (run $runId); the checks label follows them")
                StackStateService.getInstance(project).requestLiveLater(root, RERUN_REFRESH_DELAY_MS)
            } else {
                error("Re-running the failed jobs of $workflow didn't work", result.summary())
            }
        }
    }

    fun copyPrUrl(url: String) = CopyPasteManager.getInstance().setContents(StringSelection(url))

    fun showSettings(project: Project) = ShowSettingsUtil.getInstance().showSettingsDialog(project, GhStackConfigurable::class.java)
}
