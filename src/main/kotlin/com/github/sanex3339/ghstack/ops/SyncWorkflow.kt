package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.cli.ExitCode
import com.github.sanex3339.ghstack.cli.StackCli
import com.github.sanex3339.ghstack.planning.SyncOutput
import java.nio.file.Path

enum class SyncOutcome {
    SYNCED,

    /** Synced, but a repository rule forced pushing the branches in batches. */
    SYNCED_PUSHED_IN_BATCHES,
    CONFLICT,
    STACKS_UNAVAILABLE,
    OPEN_TERMINAL,
    CANCELLED,
}

/** `gh stack sync [--prune]` plus the divergence resolution that non-interactive sync can't do itself. */
class SyncWorkflow(private val cli: StackCli, private val gitDir: Path, private val prompts: Prompts) {
    fun run(prune: Boolean, snapshot: RepoSnapshot): SyncOutcome {
        val args = if (prune) arrayOf("sync", "--prune") else arrayOf("sync")
        val sync = cli.stack(*args)
        if (sync.exit == ExitCode.CONFLICT) return SyncOutcome.CONFLICT
        if (sync.exit == ExitCode.STACKS_UNAVAILABLE) return SyncOutcome.STACKS_UNAVAILABLE
        sync.orAbort("Sync")
        if (!SyncOutput.isAborted(sync)) {
            if (!SyncOutput.pushFailed(sync)) return SyncOutcome.SYNCED
            val limit = PushLimit.parse(sync.combinedOutput)
                ?: throw WorkflowAbort("Sync updated the stack but couldn't push it: ${sync.summary()}")
            StackPusher(cli).pushInBatches(limit)
            return SyncOutcome.SYNCED_PUSHED_IN_BATCHES
        }

        return when (prompts.chooseDivergenceResolution(sync.combinedOutput)) {
            DivergenceChoice.USE_REMOTE -> {
                useRemote(snapshot)
                SyncOutcome.SYNCED
            }
            DivergenceChoice.KEEP_LOCAL -> {
                keepLocal(snapshot)
                SyncOutcome.SYNCED
            }
            DivergenceChoice.OPEN_TERMINAL -> SyncOutcome.OPEN_TERMINAL
            DivergenceChoice.CANCEL -> SyncOutcome.CANCELLED
        }
    }

    private fun useRemote(snapshot: RepoSnapshot) {
        val stack = snapshot.stack
        val target = stack.number?.toString()
            ?: stack.activeBranches.firstNotNullOfOrNull { it.pr?.number?.toString() }
            ?: throw WorkflowAbort("This stack has no number or pull requests on GitHub to check out")
        val before = StackFileStore.read(gitDir)
        cli.stack("unstack", "--local").orAbort("Removing local stack tracking")
        val checkout = cli.stack("checkout", target)
        if (!checkout.ok) {
            StackFileStore.restore(gitDir, before)
            throw WorkflowAbort("Checking out the stack from GitHub failed, so local tracking was restored: ${checkout.summary()}")
        }
    }

    private fun keepLocal(snapshot: RepoSnapshot) {
        val repository = snapshot.repository ?: throw WorkflowAbort("Can't tell which GitHub repository this stack belongs to")
        val remote = RemoteStacks(cli, repository)
        val localOpen = snapshot.stack.activeBranches.mapNotNull { it.pr?.number }
        val remoteStack = remote.find(snapshot.stack.number, localOpen) ?: throw WorkflowAbort("Couldn't find this stack on GitHub")
        RecreateRemoteStack(cli, gitDir).run(remote, remoteStack.number, snapshot.stack, returnTo = snapshot.currentBranch)
        cli.stack("submit", "--auto").orAbort("Submitting the stack")
    }
}
