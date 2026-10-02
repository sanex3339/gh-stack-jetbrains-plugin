package com.github.sanex3339.ghstack.planning

import com.github.sanex3339.ghstack.model.StackUi

data class NewPrTarget(val branch: String, val base: String)

enum class RecreateDecision {
    NOT_NEEDED,
    RECREATE,

    /** The GitHub stack has PRs the local stack doesn't track; recreating would drop them. */
    REMOTE_HAS_UNKNOWN_PRS,
}

object SubmitPlanner {
    /** Active branches without a PR, bottom → top, each with the base its new PR must target. */
    fun newPrTargets(stack: StackUi): List<NewPrTarget> =
        stack.activeBranches.filter { it.pr == null }.map { NewPrTarget(it.name, stack.activeParentOf(it.name)) }

    /**
     * GitHub's stacks API can only append, so any other change of the open-PR order needs the
     * stack unstacked and recreated. `gh stack submit` does that itself after `gh stack modify`.
     */
    fun recreateDecision(remoteOpenPrs: List<Int>?, localOpenPrs: List<Int>, modifyPendingSubmit: Boolean): RecreateDecision {
        if (modifyPendingSubmit || remoteOpenPrs.isNullOrEmpty()) return RecreateDecision.NOT_NEEDED
        if (remoteOpenPrs.any { it !in localOpenPrs }) return RecreateDecision.REMOTE_HAS_UNKNOWN_PRS
        val isAppend = localOpenPrs.size >= remoteOpenPrs.size && localOpenPrs.subList(0, remoteOpenPrs.size) == remoteOpenPrs
        return if (isAppend) RecreateDecision.NOT_NEEDED else RecreateDecision.RECREATE
    }
}
