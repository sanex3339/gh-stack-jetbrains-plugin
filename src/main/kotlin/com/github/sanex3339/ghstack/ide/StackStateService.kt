package com.github.sanex3339.ghstack.ide

import com.github.sanex3339.ghstack.cli.CliStatus
import com.github.sanex3339.ghstack.cli.CommandRequest
import com.github.sanex3339.ghstack.cli.GhStatusChecker
import com.github.sanex3339.ghstack.model.GitDirStateParser
import com.github.sanex3339.ghstack.model.GitHead
import com.github.sanex3339.ghstack.model.MergeReadiness
import com.github.sanex3339.ghstack.model.PrDetails
import com.github.sanex3339.ghstack.model.PrDetailsQuery
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.model.StackFileParser
import com.github.sanex3339.ghstack.model.StackFileResult
import com.github.sanex3339.ghstack.model.StateMerger
import com.github.sanex3339.ghstack.model.ViewJsonParser
import com.github.sanex3339.ghstack.model.ViewSnapshot
import com.github.sanex3339.ghstack.ops.StackFileStore
import com.github.sanex3339.ghstack.state.RepoState
import com.github.sanex3339.ghstack.ui.GhStackCommands
import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.messages.Topic
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

fun interface StackStateListener {
    fun stateChanged(root: Path)
}

/**
 * Keeps one [RepoState] per git root. Two refresh levels avoid a loop (`view --json` rewrites the stack file):
 * "reload" re-parses gh-stack's files (2 s poll of their stamps), "live" also runs `gh stack view --json`
 * (on git changes, IDE activation, after operations, on demand), at most one at a time per root.
 */
@Service(Service.Level.PROJECT)
class StackStateService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val states = ConcurrentHashMap<Path, RepoState>()
    private val overlays = ConcurrentHashMap<Path, ViewSnapshot>()
    private val prDetails = ConcurrentHashMap<Path, Map<Int, PrDetails>>()
    private val fileStamps = ConcurrentHashMap<Path, List<Long>>()
    private val liveRequests = ConcurrentHashMap<Path, Channel<Unit>>()
    private val autoContinued = ConcurrentHashMap<Path, Long>()
    private val lastLive = ConcurrentHashMap<Path, Long>()
    private val runner = IdeEnvironment.runner()

    @Volatile
    private var selectedRoot: Path? = null

    init {
        project.messageBus.connect(this).apply {
            subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { repository -> requestLive(repository.root.toNioPath()) })
            subscribe(VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED, VcsRepositoryMappingListener { requestAllLive() })
        }
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) = requestAllLive()
            },
        )
        scope.launch(Dispatchers.IO) {
            delay(INITIAL_DELAY_MS)
            requestAllLive()
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                roots().forEach { root -> runCatching { pollFiles(root) }.onFailure { LOG.debug(it) } }
                refreshPrStatusesIfStale()
            }
        }
    }

    fun roots(): List<Path> = GitRepositoryManager.getInstance(project).repositories.map { it.root.toNioPath() }

    fun repository(root: Path): GitRepository? =
        GitRepositoryManager.getInstance(project).repositories.firstOrNull { it.root.toNioPath() == root }

    /** The repo chosen in the selector, else the one of the active editor file, else the first. */
    fun activeRoot(): Path? {
        val roots = roots()
        selectedRoot?.takeIf { it in roots }?.let { return it }
        if (roots.size > 1) {
            val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            file?.let { GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(it) }?.root?.toNioPath()?.let { return it }
        }
        return roots.firstOrNull()
    }

    fun state(root: Path): RepoState = states[root] ?: RepoState.initial(root)

    fun activeState(): RepoState? = activeRoot()?.let(::state)

    fun selectRoot(root: Path) {
        selectedRoot = root
        publish(root)
        requestLive(root)
    }

    fun requestLive(root: Path) {
        channelFor(root).trySend(Unit)
    }

    fun requestAllLive() = roots().forEach(::requestLive)

    /** Refreshes on the calling (background) thread and returns when done; used by the MCP tools. */
    fun refreshNow(root: Path) = refreshLive(root)

    fun recheckCli() {
        states.replaceAll { _, state -> state.copy(cliStatus = null) }
        requestAllLive()
    }

    /** Re-renders listeners without re-reading anything (e.g. an operation started or finished). */
    fun notifyChanged(root: Path) = publish(root)

    fun markStacksUnavailable(root: Path) {
        states.compute(root) { _, old -> (old ?: RepoState.initial(root)).copy(stacksUnavailable = true) }
        publish(root)
    }

    private fun channelFor(root: Path): Channel<Unit> = liveRequests.computeIfAbsent(root) {
        Channel<Unit>(Channel.CONFLATED).also { channel ->
            scope.launch(Dispatchers.IO) {
                for (ignored in channel) {
                    delay(DEBOUNCE_MS)
                    channel.tryReceive()
                    try {
                        refreshLive(root)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LOG.warn("Stacked PRs refresh failed for $root", e)
                    }
                }
            }
        }
    }

    private fun refreshLive(root: Path) {
        if (repository(root) == null) return
        val previous = state(root)
        val status = previous.cliStatus ?: GhStatusChecker(runner).check(IdeEnvironment.ghPath(), IdeEnvironment.gitPath(), root)
        val gitDir = previous.gitDir ?: resolveGitDir(root, status)
        val currentBranch = readCurrentBranch(gitDir)
        val files = readFiles(gitDir)
        // Show the new current branch right away; `view --json` below talks to GitHub and can take seconds.
        states[root] = compose(previous.copy(cliStatus = status, gitDir = gitDir), files, overlays[root], currentBranch)
        publish(root)
        val overlay = if (status is CliStatus.Ready && shouldRunView(files.stackFile, currentBranch)) runView(status, root) else null
        if (overlay != null) overlays[root] = overlay else overlays.remove(root)
        fileStamps[root] = stamps(gitDir)
        val base = previous.copy(cliStatus = status, gitDir = gitDir)
        val composed = compose(base, files, overlay, currentBranch)
        states[root] = composed
        lastLive[root] = System.currentTimeMillis()
        publish(root)
        maybeAutoContinue(composed)
        // Draft / review / checks for the current stack: one GraphQL request, also slow-ish, so it comes last.
        if (status is CliStatus.Ready) {
            fetchPrDetails(status, root, composed)?.let { details ->
                prDetails[root] = details
                states[root] = compose(base, files, overlay, currentBranch)
                publish(root)
            }
        }
    }

    /** CI and reviews change on GitHub without any local event, so re-ask about the active stack every minute. */
    private fun refreshPrStatusesIfStale() {
        if (!ApplicationManager.getApplication().isActive) return
        val root = activeRoot() ?: return
        if (state(root).currentStack == null) return
        if (System.currentTimeMillis() - (lastLive[root] ?: 0L) >= PR_REFRESH_INTERVAL_MS) requestLive(root)
    }

    private fun pollFiles(root: Path) {
        val previous = states[root] ?: return
        val gitDir = previous.gitDir ?: return
        val stamps = stamps(gitDir)
        if (fileStamps.put(root, stamps) == stamps) return
        val composed = compose(previous, readFiles(gitDir), overlays[root], readCurrentBranch(gitDir))
        states[root] = composed
        publish(root)
        maybeAutoContinue(composed)
    }

    private data class FileSnapshot(val stackFile: StackFileResult, val operation: OperationState, val gitRebaseInProgress: Boolean)

    private fun readFiles(gitDir: Path) = FileSnapshot(
        stackFile = StackFileParser.parse(StackFileStore.read(gitDir)),
        operation = GitDirStateParser.operationState(
            StackFileStore.read(gitDir, StackFileStore.REBASE_STATE_FILE),
            StackFileStore.read(gitDir, StackFileStore.MODIFY_STATE_FILE),
            StackFileStore.read(gitDir, StackFileStore.REMOVAL_STATE_FILE),
        ),
        gitRebaseInProgress = Files.isDirectory(gitDir.resolve("rebase-merge")) || Files.isDirectory(gitDir.resolve("rebase-apply")),
    )

    private fun compose(base: RepoState, files: FileSnapshot, overlay: ViewSnapshot?, currentBranch: String?): RepoState {
        val parsed = files.stackFile as? StackFileResult.Parsed
        val details = prDetails[base.root].orEmpty()
        return base.copy(
            stacks = StateMerger.merge(parsed?.file, overlay, currentBranch).map { if (it.isCurrent) MergeReadiness.annotate(it, details) else it },
            currentBranch = currentBranch,
            repository = parsed?.file?.repository,
            fallbackMode = parsed == null,
            operation = files.operation,
            gitRebaseInProgress = files.gitRebaseInProgress,
            conflictedFiles = if (files.operation.isStopped()) conflictedFiles(base) else emptyList(),
        )
    }

    private fun OperationState.isStopped() = this is OperationState.RebaseConflict || this is OperationState.RemovalStopped

    private fun conflictedFiles(state: RepoState): List<String> {
        val git = (state.cliStatus as? CliStatus.Ready)?.gitPath ?: return emptyList()
        val result = runner.run(CommandRequest(state.root, listOf(git, "diff", "--name-only", "--diff-filter=U"), GIT_DIR_TIMEOUT))
        return if (result.ok) result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
    }

    /**
     * If the IDE's own "Continue Rebase" finished the conflicted branch (git is no longer mid-rebase and the
     * branch now contains its parent), carry on with the branches above it, once per stop.
     */
    private fun maybeAutoContinue(state: RepoState) {
        val operation = state.operation
        if (!operation.isStopped() || state.gitRebaseInProgress || state.conflictedFiles.isNotEmpty()) return
        if (GhStackOperations.getInstance(project).isBusy(state.root)) return
        val gitDir = state.gitDir ?: return
        val stateFile = gitDir.resolve(if (operation is OperationState.RebaseConflict) StackFileStore.REBASE_STATE_FILE else StackFileStore.REMOVAL_STATE_FILE)
        val stamp = stateFile.toFile().lastModified()
        if (autoContinued[state.root] == stamp) return
        val branch = when (operation) {
            is OperationState.RebaseConflict -> operation.branch
            is OperationState.RemovalStopped -> operation.branch
            else -> null
        } ?: return
        val stack = state.stacks.firstOrNull { it.branch(branch) != null } ?: return
        val git = (state.cliStatus as? CliStatus.Ready)?.gitPath ?: return
        val parent = stack.activeParentOf(branch)
        val finished = runner.run(CommandRequest(state.root, listOf(git, "merge-base", "--is-ancestor", parent, branch), GIT_DIR_TIMEOUT)).ok
        if (!finished) return
        autoContinued[state.root] = stamp
        ApplicationManager.getApplication().invokeLater({ GhStackCommands.rebaseContinue(project) }, project.disposed)
    }

    private fun shouldRunView(stackFile: StackFileResult, currentBranch: String?): Boolean {
        if (currentBranch == null) return false
        return when (stackFile) {
            is StackFileResult.Parsed -> stackFile.file.stacks.any { it.hasBranch(currentBranch) || it.trunk == currentBranch }
            else -> true
        }
    }

    private fun runView(status: CliStatus.Ready, root: Path): ViewSnapshot? {
        val result = runner.run(CommandRequest(root, listOf(status.ghPath, "stack", "view", "--json"), VIEW_TIMEOUT))
        return if (result.ok) ViewJsonParser.parse(result.stdout) else null
    }

    /** `null` when there is nothing to ask or GitHub didn't answer (the previous details are kept). */
    private fun fetchPrDetails(status: CliStatus.Ready, root: Path, state: RepoState): Map<Int, PrDetails>? {
        val repository = state.repository ?: return null
        val numbers = state.currentStack?.activeBranches?.mapNotNull { it.pr?.number }.orEmpty()
        if (numbers.isEmpty()) return emptyMap()
        return PrDetailsQuery.fetch(repository, numbers) { query ->
            val result = runner.run(CommandRequest(root, listOf(status.ghPath, "api", "--hostname", repository.host, "graphql", "-f", "query=$query"), VIEW_TIMEOUT))
            result.stdout.takeIf { result.ok }
        }
    }

    private fun resolveGitDir(root: Path, status: CliStatus): Path {
        val git = (status as? CliStatus.Ready)?.gitPath ?: IdeEnvironment.gitPath() ?: return root.resolve(".git")
        val result = runner.run(CommandRequest(root, listOf(git, "rev-parse", "--absolute-git-dir"), GIT_DIR_TIMEOUT))
        return if (result.ok) Path.of(result.stdout.trim()) else root.resolve(".git")
    }

    private fun readCurrentBranch(gitDir: Path): String? = GitHead.currentBranch(StackFileStore.read(gitDir, "HEAD"))

    private fun stamps(gitDir: Path): List<Long> =
        listOf("HEAD", "index", StackFileStore.STACK_FILE, StackFileStore.REBASE_STATE_FILE, StackFileStore.MODIFY_STATE_FILE, StackFileStore.REMOVAL_STATE_FILE).flatMap { name ->
            val file = gitDir.resolve(name).toFile()
            if (file.exists()) listOf(file.length(), file.lastModified()) else listOf(-1L, -1L)
        }

    private fun publish(root: Path) {
        if (project.isDisposed) return
        project.messageBus.syncPublisher(TOPIC).stateChanged(root)
        ActivityTracker.getInstance().inc() // lets toolbars (main toolbar switcher) re-run update()
    }

    override fun dispose() = Unit

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<StackStateListener> = Topic(StackStateListener::class.java, Topic.BroadcastDirection.NONE)

        private val LOG = logger<StackStateService>()
        private const val INITIAL_DELAY_MS = 500L
        private const val POLL_INTERVAL_MS = 2_000L
        private const val PR_REFRESH_INTERVAL_MS = 60_000L
        private const val DEBOUNCE_MS = 300L
        private val VIEW_TIMEOUT = 60.seconds
        private val GIT_DIR_TIMEOUT = 10.seconds

        fun getInstance(project: Project): StackStateService = project.service()
    }
}
