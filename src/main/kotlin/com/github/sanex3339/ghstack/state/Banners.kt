package com.github.sanex3339.ghstack.state

import com.github.sanex3339.ghstack.cli.CliStatus
import com.github.sanex3339.ghstack.model.OperationState

/** The one message shown at the bottom of the tool window, most urgent first. */
sealed interface Banner {
    data object GitMissing : Banner
    data object GhMissing : Banner
    data object ExtensionMissing : Banner
    data object NotAuthenticated : Banner
    /** [files] still conflicted; [paused] = git isn't mid-rebase (the IDE finished or aborted that branch). */
    data class RebaseConflict(val branch: String?, val files: List<String> = emptyList(), val paused: Boolean = false) : Banner
    data class RemovalConflict(val removed: String, val branch: String?, val files: List<String> = emptyList(), val paused: Boolean = false) : Banner
    data class ModifyInterrupted(val phase: String) : Banner
    data object ModifyPendingSubmit : Banner
    data object StacksUnavailable : Banner
    data class Unsubmitted(val count: Int) : Banner
    data class DraftsBlocking(val prNumbers: List<Int>) : Banner
    data object FallbackMode : Banner
}

object Banners {
    fun of(state: RepoState?): Banner? {
        if (state == null) return null
        when (state.cliStatus) {
            CliStatus.GitNotFound -> return Banner.GitMissing
            CliStatus.GhNotFound -> return Banner.GhMissing
            is CliStatus.ExtensionMissing -> return Banner.ExtensionMissing
            is CliStatus.NotAuthenticated -> return Banner.NotAuthenticated
            is CliStatus.Ready, null -> Unit
        }
        when (val operation = state.operation) {
            is OperationState.RebaseConflict ->
                return Banner.RebaseConflict(operation.branch, state.conflictedFiles, paused = !state.gitRebaseInProgress)
            is OperationState.RemovalStopped ->
                return Banner.RemovalConflict(operation.removed, operation.branch, state.conflictedFiles, paused = !state.gitRebaseInProgress)
            is OperationState.ModifyInterrupted -> return Banner.ModifyInterrupted(operation.phase)
            OperationState.ModifyPendingSubmit -> return Banner.ModifyPendingSubmit
            OperationState.None -> Unit
        }
        if (state.stacksUnavailable) return Banner.StacksUnavailable
        state.currentStack?.unsubmittedCount?.takeIf { it > 0 }?.let { return Banner.Unsubmitted(it) }
        state.currentStack?.draftPrNumbers?.takeIf { it.isNotEmpty() }?.let { return Banner.DraftsBlocking(it) }
        if (state.fallbackMode) return Banner.FallbackMode
        return null
    }
}
