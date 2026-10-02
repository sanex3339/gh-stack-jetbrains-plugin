package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.cli.ExitCode
import com.github.sanex3339.ghstack.cli.StackCli
import com.github.sanex3339.ghstack.model.BranchStatus
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.planning.PrPrefill
import com.github.sanex3339.ghstack.planning.RecreateDecision
import com.github.sanex3339.ghstack.planning.SubmitPlanner
import java.nio.file.Path

enum class SubmitOutcome { SUBMITTED, CANCELLED, STOPPED_ON_CONFLICT, STACKS_UNAVAILABLE }

/**
 * Pushes the stack, opens PRs for new branches with user-edited titles, recreates the GitHub stack
 * when its order changed (e.g. after an insert), and links everything with `gh stack submit --auto`.
 */
class SubmitWorkflow(
    private val cli: StackCli,
    private val gitDir: Path,
    private val prompts: Prompts,
    private val draftByDefault: Boolean,
) {
    fun run(snapshot: RepoSnapshot): SubmitOutcome {
        val stack = snapshot.stack
        if (snapshot.operation is OperationState.RebaseConflict) throw WorkflowAbort("Finish or abort the rebase in progress first")

        if (stack.needsRebase) {
            when (prompts.chooseRebaseBeforeSubmit(stack.activeBranches.count { it.status == BranchStatus.NEEDS_REBASE })) {
                RebaseChoice.REBASE -> {
                    val rebase = cli.stack("rebase")
                    if (rebase.exit == ExitCode.CONFLICT) return SubmitOutcome.STOPPED_ON_CONFLICT
                    rebase.orAbort("Rebasing the stack")
                }
                RebaseChoice.SUBMIT_ANYWAY -> Unit
                RebaseChoice.CANCEL -> return SubmitOutcome.CANCELLED
            }
        }

        var markReady = false
        val createdPrs = mutableMapOf<String, Int>()
        val targets = SubmitPlanner.newPrTargets(stack)
        if (targets.isNotEmpty()) {
            val drafts = targets.map { target ->
                val log = cli.git("log", "--format=${PrPrefill.LOG_FORMAT}", "${target.base}..${target.branch}")
                val (title, body) = PrPrefill.prefill(target.branch, PrPrefill.parseLog(log.stdout))
                PrDraft(target.branch, target.base, title, body, draftByDefault)
            }
            val request = prompts.editPrDrafts(drafts) ?: return SubmitOutcome.CANCELLED
            markReady = request.markReady
            cli.stack("push").orAbort("Pushing the stack")
            for (draft in request.drafts) {
                val args = mutableListOf("pr", "create", "--head", draft.branch, "--base", draft.base, "--title", draft.title, "--body", draft.body)
                if (draft.draft) args += "--draft"
                val created = cli.gh(*args.toTypedArray()).orAbort("Creating the pull request for ${draft.branch}")
                PR_URL.find(created.stdout)?.groupValues?.get(1)?.toIntOrNull()?.let { createdPrs[draft.branch] = it }
            }
        }

        val repository = snapshot.repository
        if (repository != null && snapshot.operation != OperationState.ModifyPendingSubmit) {
            val localOpen = stack.activeBranches.mapNotNull { it.pr?.number ?: createdPrs[it.name] }
            val remote = RemoteStacks(cli, repository)
            val remoteStack = if (stack.number != null || localOpen.isNotEmpty()) remote.find(stack.number, localOpen) else null
            when (SubmitPlanner.recreateDecision(remoteStack?.openPrNumbers, localOpen, modifyPendingSubmit = false)) {
                RecreateDecision.NOT_NEEDED -> Unit
                RecreateDecision.REMOTE_HAS_UNKNOWN_PRS -> throw WorkflowAbort(
                    "The stack on GitHub has pull requests your local stack doesn't track. Run Sync first, then Submit again.",
                )
                RecreateDecision.RECREATE -> {
                    val number = requireNotNull(remoteStack).number
                    val confirmed = prompts.confirm(
                        "Recreate Stack on GitHub",
                        "The order of the stack changed. GitHub can only append to a stack, so stack #$number will be " +
                            "unstacked and recreated with the same pull requests and a new stack number.",
                        "Recreate",
                    )
                    if (!confirmed) return SubmitOutcome.CANCELLED
                    RecreateRemoteStack(cli, gitDir).run(remote, number, stack, returnTo = snapshot.currentBranch)
                }
            }
        }

        val submitArgs = mutableListOf("submit", "--auto")
        if (markReady) submitArgs += "--open"
        val submit = cli.stack(*submitArgs.toTypedArray())
        if (submit.exit == ExitCode.STACKS_UNAVAILABLE) return SubmitOutcome.STACKS_UNAVAILABLE
        submit.orAbort("Submitting the stack")
        return SubmitOutcome.SUBMITTED
    }

    private companion object {
        val PR_URL = Regex("""/pull/(\d+)""")
    }
}
