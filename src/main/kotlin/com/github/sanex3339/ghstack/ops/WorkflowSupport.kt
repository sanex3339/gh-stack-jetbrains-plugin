package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.cli.CliResult
import com.github.sanex3339.ghstack.cli.CommandCancelledException
import com.github.sanex3339.ghstack.cli.StackCli
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.model.RepoCoordinates
import com.github.sanex3339.ghstack.model.StackUi

/** Stops a workflow with a message for the user; the IDE shows it as an error notification. */
class WorkflowAbort(message: String) : RuntimeException(message)

fun CliResult.orAbort(step: String): CliResult = if (ok) this else throw WorkflowAbort("$step failed: ${summary()}")

data class PrDraft(val branch: String, val base: String, val title: String, val body: String, val draft: Boolean)

data class SubmitRequest(val drafts: List<PrDraft>, val markReady: Boolean)

enum class RebaseChoice { REBASE, SUBMIT_ANYWAY, CANCEL }

enum class DivergenceChoice { USE_REMOTE, KEEP_LOCAL, OPEN_TERMINAL, CANCEL }

/** Questions a workflow may ask mid-way. Implementations block the calling (background) thread until answered. */
interface Prompts {
    fun confirm(title: String, message: String, yesText: String): Boolean
    fun chooseRebaseBeforeSubmit(branchCount: Int): RebaseChoice
    fun editPrDrafts(drafts: List<PrDraft>): SubmitRequest?
    fun chooseDivergenceResolution(details: String): DivergenceChoice
    fun chooseRemote(remotes: List<String>): String?
}

/** What a workflow starts from: the current stack and where it lives. */
data class RepoSnapshot(
    val stack: StackUi,
    val currentBranch: String,
    val repository: RepoCoordinates?,
    val operation: OperationState,
)

/** `checkout` and `trunk` have no `--remote` flag, so with several remotes gh stack needs `remote.pushDefault`. */
class PushRemoteGuard(private val cli: StackCli, private val prompts: Prompts) {
    fun ensure() {
        val remotes = cli.git("remote").stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (remotes.size <= 1) return
        if (cli.git("config", "--get", "remote.pushDefault").stdout.isNotBlank()) return
        val chosen = prompts.chooseRemote(remotes) ?: throw CommandCancelledException()
        cli.git("config", "remote.pushDefault", chosen).orAbort("Saving remote.pushDefault")
    }
}
