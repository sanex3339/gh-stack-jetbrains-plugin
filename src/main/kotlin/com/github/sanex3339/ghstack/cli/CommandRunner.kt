package com.github.sanex3339.ghstack.cli

import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration

/** Receives a process's output line by line (the IDE console tab implements it). */
interface OutputSink {
    fun command(text: String)
    fun stdout(line: String)
    fun stderr(line: String)
}

data class CommandRequest(val workDir: Path, val command: List<String>, val timeout: Duration? = null) {
    fun displayText(): String =
        command.mapIndexed { index, arg -> if (index == 0) Path.of(arg).fileName.toString() else ShellQuote.quote(arg) }.joinToString(" ")
}

/**
 * Runs CLI processes non-interactively: stdin closed, prompts, colours, pagers and editors disabled.
 * Plain [ProcessBuilder] keeps it usable from tests without an IDE.
 */
class CommandRunner(private val baseEnvironment: () -> Map<String, String> = { System.getenv() }) {
    fun run(request: CommandRequest, sink: OutputSink? = null, isCancelled: () -> Boolean = { false }): CliResult {
        sink?.command(request.displayText())
        val builder = ProcessBuilder(request.command).directory(request.workDir.toFile())
        builder.environment().apply {
            clear()
            putAll(baseEnvironment())
            putAll(NON_INTERACTIVE_ENV)
        }
        val process = try {
            builder.start()
        } catch (e: IOException) {
            val message = "Failed to start ${request.command.first()}: ${e.message}"
            sink?.stderr(message)
            return CliResult(EXIT_START_FAILED, "", message)
        }
        process.outputStream.close()
        val stdout = StringBuffer()
        val stderr = StringBuffer()
        val outPump = pump(process.inputStream, stdout) { sink?.stdout(it) }
        val errPump = pump(process.errorStream, stderr) { sink?.stderr(it) }
        val deadline = request.timeout?.let { System.nanoTime() + it.inWholeNanoseconds }
        while (!process.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
            if (isCancelled() || (deadline != null && System.nanoTime() > deadline)) {
                destroy(process)
                outPump.join(JOIN_TIMEOUT_MS)
                errPump.join(JOIN_TIMEOUT_MS)
                return CliResult(EXIT_CANCELLED, stdout.toString(), stderr.toString(), cancelled = true)
            }
        }
        // A detached grandchild (e.g. `git gc --auto`) may keep the pipes open; don't wait for it forever.
        outPump.join(DRAIN_TIMEOUT_MS)
        errPump.join(DRAIN_TIMEOUT_MS)
        return CliResult(process.exitValue(), stdout.toString(), stderr.toString())
    }

    private fun pump(stream: InputStream, buffer: StringBuffer, onLine: (String) -> Unit): Thread =
        thread(isDaemon = true, name = "gh-stack-output") {
            try {
                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        buffer.append(line).append('\n')
                        onLine(line)
                    }
                }
            } catch (_: IOException) {
                // Stream closed because the process was destroyed.
            }
        }

    private fun destroy(process: Process) {
        process.descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        const val EXIT_START_FAILED = 127
        const val EXIT_CANCELLED = -2
        private const val POLL_INTERVAL_MS = 50L
        private const val JOIN_TIMEOUT_MS = 1_000L
        private const val DRAIN_TIMEOUT_MS = 5_000L

        val NON_INTERACTIVE_ENV: Map<String, String> = mapOf(
            "GH_PROMPT_DISABLED" to "1",
            "NO_COLOR" to "1",
            "CLICOLOR" to "0",
            "GH_STACK_HYPERLINKS" to "0",
            "GH_NO_UPDATE_NOTIFIER" to "1",
            "GH_SPINNER_DISABLED" to "1",
            "GIT_EDITOR" to "true",
            "GIT_SEQUENCE_EDITOR" to "true",
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_PAGER" to "cat",
            "PAGER" to "cat",
        )
    }
}
