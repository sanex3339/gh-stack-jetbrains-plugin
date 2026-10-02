package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.cli.ExitCode
import com.github.sanex3339.ghstack.cli.StackCli

sealed interface MoveOutcome {
    data object Moved : MoveOutcome

    /** The commit landed in the target layer, but rebasing the layers above it stopped on a conflict. */
    data class StoppedOnConflict(val target: String) : MoveOutcome
}

/**
 * Moves uncommitted changes of [paths] into another layer of the stack: stash them, commit them on
 * [target], rebase the layers above [target] so they include the commit, and come back. Any failure
 * before the commit puts the changes back where they were.
 */
class MoveChangesWorkflow(private val cli: StackCli) {
    fun run(paths: List<String>, target: String, message: String, currentBranch: String): MoveOutcome {
        if (paths.isEmpty()) throw WorkflowAbort("Select the changes to move")
        if (message.isBlank()) throw WorkflowAbort("Enter a commit message")
        if (target == currentBranch) throw WorkflowAbort("The changes are already on $target")

        val stash = cli.git("stash", "push", "--include-untracked", "-m", "Stacked PRs: move to $target", "--", *paths.toTypedArray())
        stash.orAbort("Stashing the changes")
        if ("No local changes" in stash.combinedOutput) throw WorkflowAbort("The selected files have no changes to move")

        val checkout = cli.git("checkout", target)
        if (!checkout.ok) {
            cli.git("stash", "pop")
            throw WorkflowAbort("Switching to $target failed, so the changes were put back: ${checkout.summary()}")
        }
        val pop = cli.git("stash", "pop")
        if (!pop.ok) {
            // The changes don't apply on the target layer: drop the half-applied state and restore them where they came from.
            cli.git("reset", "--merge")
            cli.git("checkout", currentBranch)
            cli.git("stash", "pop")
            throw WorkflowAbort("The changes conflict with $target, so they were put back on $currentBranch")
        }
        cli.git("add", "-A", "--", *paths.toTypedArray()).orAbort("Staging the changes on $target")
        cli.git("commit", "-m", message.trim(), "--", *paths.toTypedArray()).orAbort("Committing on $target")

        val rebase = cli.stack("rebase", "--upstack")
        if (rebase.exit == ExitCode.CONFLICT) return MoveOutcome.StoppedOnConflict(target)
        rebase.orAbort("Rebasing the layers above $target")
        cli.git("checkout", currentBranch).orAbort("Switching back to $currentBranch")
        return MoveOutcome.Moved
    }
}
