package com.github.sanex3339.ghstack.testutil

import com.github.sanex3339.ghstack.ops.DivergenceChoice
import com.github.sanex3339.ghstack.ops.PrDraft
import com.github.sanex3339.ghstack.ops.Prompts
import com.github.sanex3339.ghstack.ops.RebaseChoice
import com.github.sanex3339.ghstack.ops.SubmitRequest

class FakePrompts(
    var confirmAnswer: Boolean = true,
    var rebaseChoice: RebaseChoice = RebaseChoice.REBASE,
    var editDrafts: (List<PrDraft>) -> SubmitRequest? = { SubmitRequest(it, markReady = false) },
    var divergenceChoice: DivergenceChoice = DivergenceChoice.CANCEL,
    var remote: String? = "origin",
) : Prompts {
    val asked = mutableListOf<String>()
    var shownDrafts: List<PrDraft>? = null

    override fun confirm(title: String, message: String, yesText: String): Boolean {
        asked += "confirm:$title"
        return confirmAnswer
    }

    override fun chooseRebaseBeforeSubmit(branchCount: Int): RebaseChoice {
        asked += "rebase:$branchCount"
        return rebaseChoice
    }

    override fun editPrDrafts(drafts: List<PrDraft>): SubmitRequest? {
        asked += "drafts"
        shownDrafts = drafts
        return editDrafts(drafts)
    }

    override fun chooseDivergenceResolution(details: String): DivergenceChoice {
        asked += "diverged"
        return divergenceChoice
    }

    override fun chooseRemote(remotes: List<String>): String? {
        asked += "remote"
        return remote
    }
}
