package com.github.sanex3339.ghstack.ide

import com.github.sanex3339.ghstack.cli.CliResult
import com.github.sanex3339.ghstack.cli.CliStatus
import com.github.sanex3339.ghstack.cli.CommandCancelledException
import com.github.sanex3339.ghstack.cli.ExitCode
import com.github.sanex3339.ghstack.cli.ProcessStackCli
import com.github.sanex3339.ghstack.cli.StackCli
import com.github.sanex3339.ghstack.ops.Prompts
import com.github.sanex3339.ghstack.ops.PushRemoteGuard
import com.github.sanex3339.ghstack.ops.RepoSnapshot
import com.github.sanex3339.ghstack.ops.UndoWorkflow
import com.github.sanex3339.ghstack.ops.WorkflowAbort
import com.github.sanex3339.ghstack.state.RepoState
import com.github.sanex3339.ghstack.ui.ConflictResolver
import com.intellij.notification.NotificationAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** The result of an operation run for an agent (MCP): how it ended and what it printed. */
data class OperationResult(val status: EntryStatus, val lines: List<LogLine>) {
    val text: String get() = lines.joinToString("\n") { it.text }
}

/**
 * Runs one Stacked PRs operation per repository at a time. Output goes to the activity log; failures
 * also raise a notification. Operations marked undoable save a snapshot first ("Undo <title>").
 */
@Service(Service.Level.PROJECT)
class GhStackOperations(private val project: Project) {
    private val busyRoots: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    private val progressTexts = ConcurrentHashMap<Path, String>()
    private val indicators = ConcurrentHashMap<Path, ProgressIndicator>()
    private val undoTitles = ConcurrentHashMap<Path, String>()

    fun isBusy(root: Path): Boolean = root in busyRoots

    /** What's running for [root] right now, e.g. "Switching to api…", for spinners in the UI. */
    fun progressText(root: Path): String? = progressTexts[root]

    fun cancel(root: Path) {
        indicators[root]?.cancel()
    }

    /** Title of the operation that "Undo" would revert, if a snapshot exists. */
    fun undoTitle(root: Path): String? = undoTitles.computeIfAbsent(root) {
        StackStateService.getInstance(project).state(root).gitDir
            ?.let { gitDir -> runCatching { UndoWorkflow(NoCli(root), gitDir).load()?.title }.getOrNull() }
            .orEmpty()
    }.takeIf { it.isNotEmpty() }

    internal fun forgetUndo(root: Path) {
        undoTitles[root] = ""
    }

    /** Starts [body] in a cancellable background task. Call from the EDT. */
    fun run(title: String, root: Path? = null, progressText: String = "$title…", undoable: Boolean = false, body: OperationScope.() -> Unit) {
        val prepared = prepare(root) ?: return
        FileDocumentManager.getInstance().saveAllDocuments() // git is about to read or rewrite files
        if (!begin(prepared.root, progressText)) {
            GhStackNotifier.warn(project, "Another Stacked PRs operation is still running")
            return
        }
        object : Task.Backgroundable(project, "Stacked PRs: $title", true) {
            override fun run(indicator: ProgressIndicator) {
                indicators[prepared.root] = indicator
                execute(prepared, title, progressText, undoable, IdePrompts(project), notify = true, isCancelled = { indicator.isCanceled }, body)
            }

            override fun onFinished() = end(prepared.root)
        }.queue()
    }

    /** Runs [body] on the calling (background) thread and waits; used by the MCP tools. */
    fun runAndWait(
        title: String,
        prompts: Prompts,
        undoable: Boolean = false,
        isCancelled: () -> Boolean = { false },
        body: OperationScope.() -> Unit,
    ): OperationResult {
        val prepared = prepare(null, quiet = true) ?: throw WorkflowAbort("Stacked PRs isn't ready: check that gh and the gh stack extension are installed and logged in")
        ApplicationManager.getApplication().invokeAndWait { FileDocumentManager.getInstance().saveAllDocuments() }
        if (!begin(prepared.root, "$title…")) throw WorkflowAbort("Another Stacked PRs operation is still running")
        try {
            val entry = execute(prepared, title, "$title…", undoable, prompts, notify = false, isCancelled, body)
            return OperationResult(entry.status, entry.lines())
        } finally {
            end(prepared.root)
        }
    }

    private data class Prepared(val root: Path, val state: RepoState, val status: CliStatus.Ready, val gitDir: Path)

    private fun prepare(root: Path?, quiet: Boolean = false): Prepared? {
        val stateService = StackStateService.getInstance(project)
        val repoRoot = root ?: stateService.activeRoot() ?: return null
        val state = stateService.state(repoRoot)
        val status = state.cliStatus as? CliStatus.Ready
        val gitDir = state.gitDir
        if (status == null || gitDir == null) {
            if (!quiet) GhStackNotifier.warn(project, "Stacked PRs isn't ready yet", "The Stacked PRs tool window shows what's missing.")
            return null
        }
        return Prepared(repoRoot, state, status, gitDir)
    }

    private fun begin(root: Path, progressText: String): Boolean {
        if (!busyRoots.add(root)) return false
        progressTexts[root] = progressText
        StackStateService.getInstance(project).notifyChanged(root)
        return true
    }

    private fun end(root: Path) {
        busyRoots.remove(root)
        progressTexts.remove(root)
        indicators.remove(root)
        val stateService = StackStateService.getInstance(project)
        stateService.notifyChanged(root)
        LocalFileSystem.getInstance().findFileByNioFile(root)?.let { VfsUtil.markDirtyAndRefresh(true, true, false, it) }
        stateService.requestLive(root)
    }

    private fun execute(
        prepared: Prepared,
        title: String,
        progressText: String,
        undoable: Boolean,
        prompts: Prompts,
        notify: Boolean,
        isCancelled: () -> Boolean,
        body: OperationScope.() -> Unit,
    ): LogEntry {
        val log = OperationLog.getInstance(project)
        val entry = log.start(title, progressText, prepared.root)
        val cli = ProcessStackCli(
            runner = IdeEnvironment.runner(),
            ghPath = prepared.status.ghPath,
            gitPath = prepared.status.gitPath,
            workDir = prepared.root,
            sink = log.sink(entry),
            isCancelled = isCancelled,
        )
        val scope = OperationScope(project, cli, prepared.gitDir, prepared.state, prepared.status, prompts, entry, notify)
        val status = try {
            if (undoable) saveUndoSnapshot(cli, prepared, title, entry)
            scope.body()
            if (scope.failed) EntryStatus.FAILED else EntryStatus.SUCCEEDED
        } catch (e: CommandCancelledException) {
            log.add(entry, "Cancelled. If a rebase was interrupted, the banner shows how to continue or abort it.", LineKind.WARNING)
            EntryStatus.CANCELLED
        } catch (e: WorkflowAbort) {
            scope.error("$title failed", e.message.orEmpty())
            EntryStatus.FAILED
        }
        log.finish(entry, status)
        return entry
    }

    private fun saveUndoSnapshot(cli: StackCli, prepared: Prepared, title: String, entry: LogEntry) {
        try {
            val undo = UndoWorkflow(cli, prepared.gitDir)
            undo.save(undo.capture(title))
            undoTitles[prepared.root] = title
        } catch (e: Exception) {
            LOG.warn("Couldn't save an undo snapshot", e)
            OperationLog.getInstance(project).add(entry, "Couldn't save an undo point: ${e.message}", LineKind.WARNING)
        }
    }

    /** A placeholder for reading the undo file, which needs no commands. */
    private class NoCli(override val workDir: Path) : StackCli {
        override fun stack(vararg args: String) = CliResult(1, "", "")
        override fun gh(vararg args: String) = CliResult(1, "", "")
        override fun git(vararg args: String) = CliResult(1, "", "")
    }

    companion object {
        private val LOG = logger<GhStackOperations>()

        fun getInstance(project: Project): GhStackOperations = project.service()
    }
}

/** What an operation body can use. Runs on a background thread. */
class OperationScope(
    val project: Project,
    val cli: StackCli,
    val gitDir: Path,
    val state: RepoState,
    val status: CliStatus.Ready,
    val prompts: Prompts,
    private val entry: LogEntry,
    private val notify: Boolean,
) {
    val root: Path get() = cli.workDir

    /** Set when the operation reported an error; the log entry then ends as failed. */
    var failed: Boolean = false
        private set

    private val log get() = OperationLog.getInstance(project)

    /** Runs `gh stack <args>`; failures are reported. */
    fun stack(vararg args: String): CliResult {
        val result = cli.stack(*args)
        if (!result.ok) report(result, "gh stack ${args.firstOrNull().orEmpty()}")
        return result
    }

    fun info(message: String) = log.add(entry, message, LineKind.INFO)

    fun success(message: String) = log.add(entry, message, LineKind.SUCCESS)

    /** Needs the user's attention: logged, plus a notification with [actions] when run from the UI. */
    fun warn(title: String, details: String = "", vararg actions: NotificationAction) {
        log.add(entry, listOf(title, details).filter { it.isNotBlank() }.joinToString(": "), LineKind.WARNING)
        if (notify) GhStackNotifier.warn(project, title, details, *actions)
    }

    fun error(title: String, details: String = "") {
        failed = true
        log.add(entry, listOf(title, details).filter { it.isNotBlank() }.joinToString(": "), LineKind.ERROR)
        if (notify) GhStackNotifier.error(project, title, details)
    }

    fun report(result: CliResult, what: String) {
        when (result.exit) {
            ExitCode.CONFLICT -> {
                warn("Rebase stopped on a conflict", "Resolve the conflicts, then continue.")
                openConflicts()
            }
            ExitCode.API_FAILURE -> error("GitHub API request failed", result.summary())
            ExitCode.STACKS_UNAVAILABLE -> {
                StackStateService.getInstance(project).markStacksUnavailable(root)
                error("Stacked PRs aren't enabled for this repository", result.summary())
            }
            ExitCode.NOT_IN_STACK -> error("The current branch isn't part of a stack", result.summary())
            ExitCode.DISAMBIGUATE -> error("This branch is the trunk of several stacks", "Check out a branch of the stack you want first.")
            ExitCode.MODIFY_RECOVERY -> warn("A modify session was interrupted", "Continue or abort it from the Stacked PRs tool window.")
            else -> error("$what failed", result.summary())
        }
    }

    /** Opens the IDE merge tool for the conflicted files (UI runs only). */
    fun openConflicts() {
        if (!notify) return
        val files = ConflictResolver.unmergedFiles(cli)
        if (files.isNotEmpty()) ApplicationManager.getApplication().invokeLater({ ConflictResolver.resolve(project, files) }, project.disposed)
    }

    fun ensurePushRemote() = PushRemoteGuard(cli, prompts).ensure()

    fun snapshot(): RepoSnapshot = state.snapshot() ?: throw WorkflowAbort("Check out a branch of a stack first")
}
