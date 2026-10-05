package com.github.sanex3339.ghstack.model

import kotlinx.serialization.Serializable

/** GitHub repository a stack file belongs to, from its `repository` field (`host:owner/name`). */
@Serializable
data class RepoCoordinates(val host: String, val owner: String, val name: String) {
    val apiPath: String get() = "repos/$owner/$name"

    companion object {
        fun parse(value: String?): RepoCoordinates? {
            if (value.isNullOrBlank()) return null
            val host = value.substringBefore(':', missingDelimiterValue = "")
            val parts = value.substringAfter(':', missingDelimiterValue = "").split('/')
            if (host.isBlank() || parts.size != 2 || parts.any { it.isBlank() }) return null
            return RepoCoordinates(host, parts[0], parts[1])
        }

        /** From a git remote URL: `https://host/owner/name(.git)`, `git@host:owner/name.git`, `ssh://git@host[:port]/owner/name`. */
        fun fromRemoteUrl(url: String): RepoCoordinates? {
            val trimmed = url.trim()
            val match = URL_FORM.matchEntire(trimmed) ?: SCP_FORM.matchEntire(trimmed) ?: return null
            val (host, owner, name) = match.destructured
            return RepoCoordinates(host, owner, name)
        }

        private val URL_FORM = Regex("""(?:https?|ssh|git)://(?:[^@/]+@)?([^/:]+)(?::\d+)?/([^/]+)/([^/]+?)(?:\.git)?/?""")
        private val SCP_FORM = Regex("""[^@/]+@([^/:]+):([^/]+)/([^/]+?)(?:\.git)?/?""")
    }
}

data class PrRef(val number: Int, val url: String?)

data class LocalBranch(val name: String, val pr: PrRef?, val merged: Boolean)

/** One stack from `.git/gh-stack`. [branches] are ordered bottom (closest to trunk) to top. */
data class LocalStack(
    val id: String?,
    val number: Int?,
    val trunk: String,
    val branches: List<LocalBranch>,
) {
    fun hasBranch(name: String): Boolean = branches.any { it.name == name }
}

data class StackFile(val repository: RepoCoordinates?, val stacks: List<LocalStack>) {
    companion object {
        val EMPTY = StackFile(null, emptyList())
    }
}

sealed interface StackFileResult {
    data class Parsed(val file: StackFile) : StackFileResult
    data class Unsupported(val schemaVersion: Int) : StackFileResult
    data class Malformed(val message: String) : StackFileResult
}
