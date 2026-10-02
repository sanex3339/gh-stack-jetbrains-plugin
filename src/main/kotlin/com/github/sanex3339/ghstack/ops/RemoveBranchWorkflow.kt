package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.cli.StackCli
import com.github.sanex3339.ghstack.model.RebaseStep
import com.github.sanex3339.ghstack.model.RemovalState
import com.github.sanex3339.ghstack.model.RemoveMode
import com.github.sanex3339.ghstack.planning.RemovalPlan
import java.nio.file.Files
import java.nio.file.Path

data class RemovalOptions(val closePr: Boolean, val deleteBranch: Boolean)

sealed interface RemovalOutcome {
    /** [githubUpdated] is false when the stack isn't on GitHub; [warnings] are non-fatal follow-up failures. */
    data class Done(val githubUpdated: Boolean, val warnings: List<String>) : RemovalOutcome

    data class StoppedOnConflict(val branch: String) : RemovalOutcome
}

/**
 * Removes a branch from the stack without the `gh stack modify` TUI.
 *
 * - Fold up: nothing moves; the branch above already contains the commits.
 * - Fold down: the branch below is fast-forwarded to the removed branch.
 * - Drop: every branch above is rebased onto its new parent, skipping the removed commits. A conflict
 *   pauses here (state saved in the git dir) until [resume] or [abort].
 *
 * Then the stack is re-registered locally without the branch and, if it exists on GitHub, recreated
 * there (otherwise `gh stack sync` would pull the removed PR back in).
 */
class RemoveBranchWorkflow(private val cli: StackCli, private val gitDir: Path) {
    fun start(plan: RemovalPlan, options: RemovalOptions, snapshot: RepoSnapshot): RemovalOutcome {
        if (cli.git("status", "--porcelain", "--untracked-files=no").stdout.isNotBlank()) {
            throw WorkflowAbort("Commit or stash your changes before removing a branch")
        }
        val stackBranches = snapshot.stack.activeBranches.map { it.name }
        var current = snapshot.currentBranch
        if (current !in stackBranches) {
            cli.git("checkout", plan.removed).orAbort("Switching to ${plan.removed}")
            current = plan.removed
        }
        val tips = (stackBranches + plan.parent).distinct().associateWith { revParse(it) ?: throw WorkflowAbort("Couldn't read branch $it") }
        val remoteStack = snapshot.repository?.let { repo ->
            RemoteStacks(cli, repo).find(snapshot.stack.number, snapshot.stack.activeBranches.mapNotNull { it.pr?.number })
        }
        val steps = if (plan.mode == RemoveMode.DROP) {
            plan.above.mapIndexed { index, branch ->
                val previous = if (index == 0) plan.removed else plan.above[index - 1]
                RebaseStep(branch, onto = if (index == 0) plan.parent else previous, oldBase = tips.getValue(previous))
            }
        } else {
            emptyList()
        }
        val state = RemovalState(
            removed = plan.removed,
            mode = plan.mode,
            trunk = plan.trunk,
            newOrder = plan.newOrder,
            steps = steps,
            originalTips = tips,
            originalBranch = current,
            checkoutAfter = when {
                current != plan.removed -> current
                plan.mode == RemoveMode.FOLD_DOWN -> plan.parent
                else -> plan.above.firstOrNull() ?: plan.parent
            },
            closePr = plan.pr?.takeIf { options.closePr },
            deleteBranch = options.deleteBranch,
            remoteStackNumber = remoteStack?.number,
            repository = snapshot.repository,
        )
        if (plan.mode == RemoveMode.FOLD_DOWN) foldDown(plan, current)
        return runSteps(state)
    }

    /** Continues a drop that stopped on a conflict (after the user resolved it). */
    fun resume(): RemovalOutcome {
        val state = load() ?: throw WorkflowAbort("There's no branch removal to continue")
        if (rebaseInProgress()) {
            val result = cli.git("rebase", "--continue")
            if (!result.ok) {
                if (rebaseInProgress()) return RemovalOutcome.StoppedOnConflict(state.stoppedBranch ?: state.removed)
                rollback(state)
                throw WorkflowAbort("Continuing the rebase failed, so the removal was undone: ${result.summary()}")
            }
        }
        return runSteps(state)
    }

    /** Undoes a stopped removal: every branch goes back to where it was. */
    fun abort() {
        val state = load()
        if (state == null) {
            if (rebaseInProgress()) cli.git("rebase", "--abort")
            return
        }
        rollback(state)
    }

    private fun foldDown(plan: RemovalPlan, current: String) {
        if (!isAncestor(plan.parent, plan.removed)) throw WorkflowAbort("${plan.removed} isn't based on ${plan.parent}; rebase the stack first")
        val moved = if (current == plan.parent) cli.git("merge", "--ff-only", plan.removed) else cli.git("branch", "-f", plan.parent, plan.removed)
        moved.orAbort("Moving ${plan.parent} to include ${plan.removed}")
    }

    private fun runSteps(state: RemovalState): RemovalOutcome {
        for (index in state.nextStep until state.steps.size) {
            val step = state.steps[index]
            if (isDone(step)) continue
            val result = cli.git("rebase", "--onto", step.onto, step.oldBase, step.branch)
            if (!result.ok) {
                if (rebaseInProgress()) {
                    StackFileStore.writeOwn(gitDir, RemovalState.FILE_NAME, RemovalState.encode(state.copy(nextStep = index)))
                    return RemovalOutcome.StoppedOnConflict(step.branch)
                }
                rollback(state)
                throw WorkflowAbort("Rebasing ${step.branch} failed, so nothing was changed: ${result.summary()}")
            }
        }
        return finish(state)
    }

    private fun finish(state: RemovalState): RemovalOutcome {
        StackFileStore.deleteOwn(gitDir, RemovalState.FILE_NAME)
        try {
            LocalStackReinit(cli, gitDir).run(state.trunk, state.newOrder, checkoutAfter = state.checkoutAfter)
        } catch (e: WorkflowAbort) {
            // The stack file was restored; put the branches back too so tracking and history agree.
            rollback(state)
            throw e
        }
        val warnings = mutableListOf<String>()
        var githubUpdated = false
        val repository = state.repository
        val remoteNumber = state.remoteStackNumber
        if (repository != null && remoteNumber != null) {
            try {
                RemoteStacks(cli, repository).unstack(remoteNumber)
                githubUpdated = true
            } catch (e: WorkflowAbort) {
                warnings += e.message.orEmpty()
            }
        }
        state.closePr?.let { pr ->
            val closed = cli.gh("pr", "close", pr.toString())
            if (!closed.ok) warnings += "Couldn't close PR #$pr: ${closed.summary()}"
        }
        if (state.deleteBranch) {
            val deleted = cli.git("branch", "-D", state.removed)
            if (!deleted.ok) warnings += "Couldn't delete ${state.removed}: ${deleted.summary()}"
        }
        if (githubUpdated) {
            val submit = cli.stack("submit", "--auto")
            if (!submit.ok) {
                githubUpdated = false
                warnings += "Updating the stack on GitHub failed, run Submit to retry: ${submit.summary()}"
            }
        }
        return RemovalOutcome.Done(githubUpdated, warnings)
    }

    private fun rollback(state: RemovalState) {
        if (rebaseInProgress()) cli.git("rebase", "--abort")
        val current = currentBranch()
        state.originalTips.forEach { (branch, tip) ->
            if (revParse(branch) != tip) {
                if (branch == current) cli.git("reset", "--keep", tip) else cli.git("branch", "-f", branch, tip)
            }
        }
        if (currentBranch() != state.originalBranch) cli.git("checkout", state.originalBranch)
        StackFileStore.deleteOwn(gitDir, RemovalState.FILE_NAME)
    }

    private fun isDone(step: RebaseStep): Boolean = isAncestor(step.onto, step.branch) && !isAncestor(step.oldBase, step.branch)

    private fun isAncestor(ancestor: String, descendant: String): Boolean = cli.git("merge-base", "--is-ancestor", ancestor, descendant).ok

    private fun rebaseInProgress(): Boolean =
        Files.isDirectory(gitDir.resolve("rebase-merge")) || Files.isDirectory(gitDir.resolve("rebase-apply"))

    private fun load(): RemovalState? = StackFileStore.read(gitDir, RemovalState.FILE_NAME)?.let(RemovalState::decode)

    private fun currentBranch(): String = cli.git("branch", "--show-current").stdout.trim()

    private fun revParse(branch: String): String? =
        cli.git("rev-parse", "--verify", "--quiet", "refs/heads/$branch").takeIf { it.ok }?.stdout?.trim()
}
