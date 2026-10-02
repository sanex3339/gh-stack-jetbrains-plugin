package com.github.sanex3339.ghstack.cli

import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

sealed interface CliStatus {
    data class Ready(val ghPath: String, val gitPath: String, val extensionVersion: String?) : CliStatus
    data object GitNotFound : CliStatus
    data object GhNotFound : CliStatus
    data class ExtensionMissing(val ghPath: String) : CliStatus
    data class NotAuthenticated(val ghPath: String) : CliStatus
}

class GhStatusChecker(private val runner: CommandRunner) {
    fun check(ghPath: String?, gitPath: String?, workDir: Path): CliStatus {
        if (gitPath == null) return CliStatus.GitNotFound
        if (ghPath == null) return CliStatus.GhNotFound
        val version = runner.run(CommandRequest(workDir, listOf(ghPath, "stack", "--version"), CHECK_TIMEOUT))
        if (!version.ok) return CliStatus.ExtensionMissing(ghPath)
        // `gh auth token` reads the stored credential without a network call. No sink: the token is never logged.
        val auth = runner.run(CommandRequest(workDir, listOf(ghPath, "auth", "token"), CHECK_TIMEOUT))
        if (!auth.ok) return CliStatus.NotAuthenticated(ghPath)
        return CliStatus.Ready(ghPath, gitPath, parseVersion(version.combinedOutput))
    }

    companion object {
        const val TESTED_VERSION_PREFIX = "0.1."
        private val CHECK_TIMEOUT = 20.seconds
        private val VERSION = Regex("""version\s+v?(\d+\.\d+\.\d+[\w.-]*)""")

        fun parseVersion(output: String): String? = VERSION.find(output)?.groupValues?.get(1)
    }
}
