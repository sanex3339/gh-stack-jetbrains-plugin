package com.github.sanex3339.ghstack.cli

/** Outcome of one CLI process. `gh stack` writes human-readable output, including errors, to stderr. */
data class CliResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val cancelled: Boolean = false,
) {
    val ok: Boolean get() = exitCode == 0 && !cancelled

    val exit: ExitCode get() = ExitCode.fromInt(exitCode)

    val combinedOutput: String
        get() = when {
            stdout.isEmpty() -> stderr
            stderr.isEmpty() -> stdout
            else -> "$stdout\n$stderr"
        }

    /** The single most useful line for an error message. */
    fun summary(): String {
        val lines = combinedOutput.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.firstOrNull { it.startsWith("✗") }
            ?: lines.firstOrNull { it.startsWith("⚠") }
            ?: lines.firstOrNull { it.startsWith("fatal:") || it.startsWith("error:", ignoreCase = true) }
            ?: lines.lastOrNull()
            ?: "exit code $exitCode"
    }
}
