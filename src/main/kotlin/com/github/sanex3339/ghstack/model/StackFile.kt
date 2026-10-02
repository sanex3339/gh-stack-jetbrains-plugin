package com.github.sanex3339.ghstack.model

/** GitHub repository a stack file belongs to, from its `repository` field (`host:owner/name`). */
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
