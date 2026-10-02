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
import com.github.sanex3339.ghstack.ops.WorkflowAbort
import com.github.sanex3339.ghstack.state.RepoState
import com.github.sanex3339.ghstack.ui.GhStackCommands
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Runs one GH Stack operation per repository at a time, in a cancellable background task. */
@Service(Service.Level.PROJECT)
class GhStackOperations(private val project: Project) {
    private val busyRoots: MutableSet<Path> = ConcurrentHashMap.newKeySet()

    fun isBusy(root: Path): Boolean = root in busyRoots

    fun run(title: String, root: Path? = null, body: OperationScope.() -> Unit) {
        val stateService = StackStateService.getInstance(project)
        val repoRoot = root ?: stateService.activeRoot() ?: return
        val state = stateService.state(repoRoot)
        val status = state.cliStatus as? CliStatus.Ready
        val gitDir = state.gitDir
        if (status == null || gitDir == null) {
            GhStackNotifier.warn(project, "GH Stack isn't ready yet", "The GH Stack tool window shows what's missing.")
            return
        }
        if (!busyRoots.add(repoRoot)) {
            GhStackNotifier.warn(project, "Another GH Stack operation is still running")
            return
        }
        object : Task.Backgroundable(project, "GH Stack: $title", true) {
            override fun run(indicator: ProgressIndicator) {
                val cli = ProcessStackCli(
                    runner = IdeEnvironment.runner(),
                    ghPath = status.ghPath,
                    gitPath = status.gitPath,
                    workDir = repoRoot,
                    sink = GhStackConsole.getInstance(project),
                    isCancelled = { indicator.isCanceled },
                )
                val scope = OperationScope(project, cli, gitDir, state, status, IdePrompts(project))
                try {
                    scope.body()
                } catch (e: CommandCancelledException) {
                    GhStackNotifier.warn(project, "$title cancelled", "If a rebase was interrupted, the GH Stack tool window shows how to continue or abort it.")
                } catch (e: WorkflowAbort) {
                    GhStackNotifier.error(project, "$title failed", e.message.orEmpty())
                }
            }

            override fun onFinished() {
                busyRoots.remove(repoRoot)
                LocalFileSystem.getInstance().findFileByNioFile(repoRoot)?.let { VfsUtil.markDirtyAndRefresh(true, true, false, it) }
                stateService.requestLive(repoRoot)
            }
        }.queue()
    }

    companion object {
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
) {
    val root: Path get() = cli.workDir

    /** Runs `gh stack <args>`; failures are reported as notifications. */
    fun stack(vararg args: String): CliResult {
        val result = cli.stack(*args)
        if (!result.ok) report(result, "gh stack ${args.firstOrNull().orEmpty()}")
        return result
    }

    fun report(result: CliResult, what: String) {
        when (result.exit) {
            ExitCode.CONFLICT -> GhStackNotifier.warn(
                project,
                "Rebase stopped on a conflict",
                "Resolve the conflicts, then continue from the GH Stack tool window.",
                GhStackNotifier.action("Resolve conflicts…") { GhStackCommands.resolveConflicts(project) },
            )
            ExitCode.API_FAILURE -> GhStackNotifier.error(project, "GitHub API request failed", result.summary())
            ExitCode.STACKS_UNAVAILABLE -> {
                StackStateService.getInstance(project).markStacksUnavailable(root)
                GhStackNotifier.error(project, "Stacked PRs aren't enabled for this repository", result.summary())
            }
            ExitCode.NOT_IN_STACK -> GhStackNotifier.warn(project, "The current branch isn't part of a stack", result.summary())
            ExitCode.DISAMBIGUATE -> GhStackNotifier.warn(project, "This branch is the trunk of several stacks", "Check out a branch of the stack you want first.")
            ExitCode.MODIFY_RECOVERY -> GhStackNotifier.warn(project, "A modify session was interrupted", "Continue or abort it from the GH Stack tool window.")
            else -> GhStackNotifier.error(project, "$what failed", result.summary())
        }
    }

    fun ensurePushRemote() = PushRemoteGuard(cli, prompts).ensure()

    fun snapshot(): RepoSnapshot = state.snapshot() ?: throw WorkflowAbort("Check out a branch of a stack first")
}
