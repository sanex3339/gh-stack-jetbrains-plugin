package com.github.sanex3339.ghstack.ide

import com.github.sanex3339.ghstack.ops.DivergenceChoice
import com.github.sanex3339.ghstack.ops.PrDraft
import com.github.sanex3339.ghstack.ops.Prompts
import com.github.sanex3339.ghstack.ops.RebaseChoice
import com.github.sanex3339.ghstack.ops.SubmitRequest
import com.github.sanex3339.ghstack.ui.dialogs.SubmitDialog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages

/** [Prompts] for background workflows: each question is shown on the EDT while the workflow thread waits. */
class IdePrompts(private val project: Project) : Prompts {
    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        ApplicationManager.getApplication().invokeAndWait({ result = runCatching(block) }, ModalityState.defaultModalityState())
        return requireNotNull(result).getOrThrow()
    }

    override fun confirm(title: String, message: String, yesText: String): Boolean = onEdt {
        MessageDialogBuilder.yesNo(title, message).yesText(yesText).noText("Cancel").ask(project)
    }

    override fun chooseRebaseBeforeSubmit(branchCount: Int): RebaseChoice = onEdt {
        val message = if (branchCount == 1) "1 branch needs a rebase before submitting." else "$branchCount branches need a rebase before submitting."
        when (Messages.showDialog(project, "$message Rebase the stack first?", "Stack Needs a Rebase", arrayOf("Rebase and Continue", "Submit Anyway", "Cancel"), 0, Messages.getWarningIcon())) {
            0 -> RebaseChoice.REBASE
            1 -> RebaseChoice.SUBMIT_ANYWAY
            else -> RebaseChoice.CANCEL
        }
    }

    override fun editPrDrafts(drafts: List<PrDraft>): SubmitRequest? = onEdt { SubmitDialog(project, drafts).showAndGetRequest() }

    override fun chooseDivergenceResolution(details: String): DivergenceChoice = onEdt {
        val message = "The stack changed both locally and on GitHub.\n\n${details.trim()}"
        when (Messages.showDialog(project, message, "Stack Diverged", arrayOf("Use GitHub's Version", "Keep Mine", "Resolve in Terminal", "Cancel"), 0, Messages.getWarningIcon())) {
            0 -> DivergenceChoice.USE_REMOTE
            1 -> DivergenceChoice.KEEP_LOCAL
            2 -> DivergenceChoice.OPEN_TERMINAL
            else -> DivergenceChoice.CANCEL
        }
    }

    override fun chooseRemote(remotes: List<String>): String? = onEdt {
        val index = Messages.showChooseDialog(
            project,
            "This repository has several remotes. Which one should gh stack push to? (Saved as remote.pushDefault.)",
            "Choose Remote",
            Messages.getQuestionIcon(),
            remotes.toTypedArray(),
            remotes.firstOrNull { it == "origin" } ?: remotes.first(),
        )
        remotes.getOrNull(index)
    }
}
