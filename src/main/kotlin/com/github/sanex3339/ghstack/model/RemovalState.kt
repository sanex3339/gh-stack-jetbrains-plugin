package com.github.sanex3339.ghstack.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class RemoveMode {
    /** Remove the branch and its commits; branches above are rebased without them. */
    DROP,

    /** Remove the branch but keep its commits in the branch below. */
    FOLD_DOWN,

    /** Remove the branch but keep its commits in the branch above (which already contains them). */
    FOLD_UP,
}

/** `git rebase --onto <onto> <oldBase> <branch>`, with [oldBase] recorded before anything moved. */
@Serializable
data class RebaseStep(val branch: String, val onto: String, val oldBase: String)

/**
 * A "Remove from stack" in progress. Saved in the git dir (plugin-owned file) only while a drop is
 * stopped on a rebase conflict, so it can be continued or undone — even after an IDE restart.
 */
@Serializable
data class RemovalState(
    val removed: String,
    val mode: RemoveMode,
    val trunk: String,
    val newOrder: List<String>,
    val steps: List<RebaseStep>,
    val nextStep: Int = 0,
    val originalTips: Map<String, String>,
    val originalBranch: String,
    val checkoutAfter: String,
    val closePr: Int? = null,
    val deleteBranch: Boolean = false,
    val remoteStackNumber: Int? = null,
    val repository: RepoCoordinates? = null,
) {
    val stoppedBranch: String? get() = steps.getOrNull(nextStep)?.branch

    companion object {
        const val FILE_NAME = "ghstack-plugin-removal.json"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(bytes: ByteArray): RemovalState? =
            try { json.decodeFromString<RemovalState>(bytes.decodeToString()) } catch (e: IllegalArgumentException) { null }

        fun encode(state: RemovalState): String = json.encodeToString(serializer(), state)
    }
}
