package com.github.sanex3339.ghstack.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A multi-step CLI operation left in progress, detected from `.git/gh-stack-*-state` files. */
sealed interface OperationState {
    data object None : OperationState

    /** A cascading rebase stopped on a conflict. HEAD is detached until it continues or aborts. */
    data class RebaseConflict(val branch: String?) : OperationState

    /** `gh stack modify` stopped mid-way (phase `applying` or `conflict`). */
    data class ModifyInterrupted(val phase: String) : OperationState

    /** `gh stack modify` applied locally; `gh stack submit` still has to recreate the stack on GitHub. */
    data object ModifyPendingSubmit : OperationState
}

object GitDirStateParser {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class RebaseDto(val conflictBranch: String? = null)

    @Serializable
    private class ModifyDto(val phase: String? = null)

    /** Arguments are the raw bytes of `gh-stack-rebase-state` / `gh-stack-modify-state`, `null` when absent. */
    fun operationState(rebaseState: ByteArray?, modifyState: ByteArray?): OperationState {
        if (rebaseState != null) {
            val branch = decodeRebase(rebaseState)?.conflictBranch?.takeIf { it.isNotBlank() }
            return OperationState.RebaseConflict(branch)
        }
        if (modifyState != null) {
            val phase = decodeModify(modifyState)?.phase ?: "unknown"
            return if (phase == "pending_submit") OperationState.ModifyPendingSubmit else OperationState.ModifyInterrupted(phase)
        }
        return OperationState.None
    }

    private fun decodeRebase(bytes: ByteArray): RebaseDto? =
        try { json.decodeFromString<RebaseDto>(bytes.decodeToString()) } catch (e: IllegalArgumentException) { null }

    private fun decodeModify(bytes: ByteArray): ModifyDto? =
        try { json.decodeFromString<ModifyDto>(bytes.decodeToString()) } catch (e: IllegalArgumentException) { null }
}
