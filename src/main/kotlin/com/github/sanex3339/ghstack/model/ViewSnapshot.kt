package com.github.sanex3339.ghstack.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class PrState {
    OPEN, MERGED, QUEUED, UNKNOWN;

    companion object {
        fun parse(value: String?): PrState = entries.firstOrNull { it != UNKNOWN && it.name == value?.uppercase() } ?: UNKNOWN
    }
}

data class ViewPr(val number: Int, val url: String?, val state: PrState)

data class ViewBranch(
    val name: String,
    val isCurrent: Boolean,
    val isMerged: Boolean,
    val isQueued: Boolean,
    val needsRebase: Boolean,
    val pr: ViewPr?,
)

/** Output of `gh stack view --json` for the stack containing the current branch; branches bottom → top. */
data class ViewSnapshot(val trunk: String, val currentBranch: String, val branches: List<ViewBranch>)

object ViewJsonParser {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class Dto(val trunk: String, val currentBranch: String = "", val branches: List<BranchDto> = emptyList())

    @Serializable
    private class BranchDto(
        val name: String,
        val isCurrent: Boolean = false,
        val isMerged: Boolean = false,
        val isQueued: Boolean = false,
        val needsRebase: Boolean = false,
        val pr: PrDto? = null,
    )

    @Serializable
    private class PrDto(val number: Int, val url: String? = null, val state: String? = null)

    fun parse(text: String): ViewSnapshot? {
        val dto = try {
            json.decodeFromString<Dto>(text)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return ViewSnapshot(
            trunk = dto.trunk,
            currentBranch = dto.currentBranch,
            branches = dto.branches.map { b ->
                ViewBranch(
                    name = b.name,
                    isCurrent = b.isCurrent,
                    isMerged = b.isMerged,
                    isQueued = b.isQueued,
                    needsRebase = b.needsRebase,
                    pr = b.pr?.let { ViewPr(it.number, it.url, PrState.parse(it.state)) },
                )
            },
        )
    }
}
