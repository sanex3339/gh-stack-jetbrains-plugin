package com.github.sanex3339.ghstack.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A stack as returned by GitHub's Stacks REST API (`/repos/{owner}/{repo}/stacks…`). */
data class RemoteStackInfo(val number: Int, val prNumbers: List<Int>, val openPrNumbers: List<Int>)

object RemoteStackJson {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class StackDto(val number: Int = 0, @SerialName("pull_requests") val pullRequests: List<PrDto> = emptyList())

    @Serializable
    private class PrDto(val number: Int, val state: String? = null, @SerialName("merged_at") val mergedAt: String? = null)

    fun parseOne(text: String): RemoteStackInfo? =
        try { json.decodeFromString<StackDto>(text).toInfo() } catch (e: IllegalArgumentException) { null }

    fun parseList(text: String): List<RemoteStackInfo> =
        try { json.decodeFromString<List<StackDto>>(text).map { it.toInfo() } } catch (e: IllegalArgumentException) { emptyList() }

    private fun StackDto.toInfo() = RemoteStackInfo(
        number = number,
        prNumbers = pullRequests.map { it.number },
        openPrNumbers = pullRequests.filter { it.state == "open" && it.mergedAt.isNullOrEmpty() }.map { it.number },
    )
}
