package com.github.sanex3339.ghstack.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Reads `.git/gh-stack`, the CLI's local tracking file. Read-only by design. */
object StackFileParser {
    const val SUPPORTED_SCHEMA_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class FileDto(val schemaVersion: Int = 0, val repository: String? = null, val stacks: List<StackDto>? = null)

    @Serializable
    private class StackDto(val id: String? = null, val number: Int? = null, val trunk: RefDto, val branches: List<RefDto>? = null)

    @Serializable
    private class RefDto(val branch: String, val pullRequest: PrDto? = null)

    @Serializable
    private class PrDto(val number: Int = 0, val url: String? = null, val merged: Boolean = false)

    /** [bytes] are the raw file contents; `null` means the file does not exist (no stacks yet). */
    fun parse(bytes: ByteArray?): StackFileResult {
        if (bytes == null) return StackFileResult.Parsed(StackFile.EMPTY)
        val dto = try {
            json.decodeFromString<FileDto>(bytes.decodeToString())
        } catch (e: IllegalArgumentException) {
            return StackFileResult.Malformed(e.message ?: "invalid JSON")
        }
        if (dto.schemaVersion > SUPPORTED_SCHEMA_VERSION) return StackFileResult.Unsupported(dto.schemaVersion)
        return StackFileResult.Parsed(
            StackFile(
                repository = RepoCoordinates.parse(dto.repository),
                stacks = dto.stacks.orEmpty().map { it.toModel() },
            ),
        )
    }

    private fun StackDto.toModel() = LocalStack(
        id = id?.takeIf { it.isNotBlank() },
        number = number?.takeIf { it > 0 },
        trunk = trunk.branch,
        branches = branches.orEmpty().map { ref ->
            val pr = ref.pullRequest?.takeIf { it.number > 0 }
            LocalBranch(name = ref.branch, pr = pr?.let { PrRef(it.number, it.url) }, merged = pr?.merged == true)
        },
    )
}
