package com.github.sanex3339.ghstack.cli

import java.nio.file.Path

class CommandCancelledException : RuntimeException("Cancelled")

/** Runs commands for one repository. Workflows depend on this interface so tests can script it. */
interface StackCli {
    val workDir: Path
    fun stack(vararg args: String): CliResult
    fun gh(vararg args: String): CliResult
    fun git(vararg args: String): CliResult
}

class ProcessStackCli(
    private val runner: CommandRunner,
    private val ghPath: String,
    private val gitPath: String,
    override val workDir: Path,
    private val sink: OutputSink? = null,
    private val isCancelled: () -> Boolean = { false },
    private val lockRetryDelayMs: Long = 5_000,
) : StackCli {
    override fun stack(vararg args: String): CliResult {
        val command = listOf(ghPath, "stack") + args
        val first = exec(command)
        if (first.exit != ExitCode.LOCKED) return first
        sink?.stderr("Stack file is locked by another gh stack process; retrying in ${lockRetryDelayMs / 1000}s…")
        Thread.sleep(lockRetryDelayMs)
        return exec(command)
    }

    override fun gh(vararg args: String): CliResult = exec(listOf(ghPath) + args)

    override fun git(vararg args: String): CliResult = exec(listOf(gitPath) + args)

    private fun exec(command: List<String>): CliResult {
        if (isCancelled()) throw CommandCancelledException()
        val result = runner.run(CommandRequest(workDir, command), sink, isCancelled)
        if (result.cancelled) throw CommandCancelledException()
        return result
    }
}
