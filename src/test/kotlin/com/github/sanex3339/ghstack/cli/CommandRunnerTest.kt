package com.github.sanex3339.ghstack.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class CommandRunnerTest {
    private val runner = CommandRunner()
    private val dir: Path = Path.of(System.getProperty("java.io.tmpdir"))

    private fun sh(script: String, timeout: Duration? = null) = CommandRequest(dir, listOf("/bin/sh", "-c", script), timeout)

    @Test
    fun `captures exit code, stdout and stderr`() {
        val result = runner.run(sh("echo out; echo err >&2; exit 3"))
        assertEquals(3, result.exitCode)
        assertEquals("out\n", result.stdout)
        assertEquals("err\n", result.stderr)
        assertEquals(ExitCode.CONFLICT, result.exit)
    }

    @Test
    fun `echoes the command and streams lines to the sink`() {
        val lines = mutableListOf<String>()
        val sink = object : OutputSink {
            override fun command(text: String) { lines += "$ $text" }
            override fun stdout(line: String) { lines += "out:$line" }
            override fun stderr(line: String) { lines += "err:$line" }
        }
        runner.run(sh("echo a; echo b"), sink)
        assertEquals(listOf("$ sh -c 'echo a; echo b'", "out:a", "out:b"), lines)
    }

    @Test
    fun `runs non-interactively with stdin closed`() {
        val result = runner.run(sh("echo \"\$GH_PROMPT_DISABLED \$GIT_EDITOR \$NO_COLOR\"; read x; echo \"got:\$x\""))
        assertEquals("1 true 1\ngot:\n", result.stdout)
    }

    @Test
    fun `cancellation kills the process`() {
        val start = System.nanoTime()
        val result = runner.run(sh("sleep 30"), isCancelled = { System.nanoTime() - start > 200_000_000 })
        assertTrue(result.cancelled)
        assertFalse(result.ok)
        assertTrue(System.nanoTime() - start < 10_000_000_000)
    }

    @Test
    fun `timeout cancels`() {
        assertTrue(runner.run(sh("sleep 30", timeout = 300.milliseconds)).cancelled)
    }

    @Test
    fun `a missing executable is reported, not thrown`() {
        val result = runner.run(CommandRequest(dir, listOf("/nonexistent/tool")))
        assertEquals(CommandRunner.EXIT_START_FAILED, result.exitCode)
        assertTrue(result.stderr.contains("/nonexistent/tool"))
    }

    @Test
    fun `uses the supplied base environment`() {
        val result = CommandRunner { mapOf("PATH" to "/usr/bin:/bin", "MY_VAR" to "x") }.run(sh("echo \$MY_VAR"))
        assertEquals("x\n", result.stdout)
    }

    @Test
    fun `shell quoting`() {
        assertEquals("api", ShellQuote.quote("api"))
        assertEquals("feat/api-routes", ShellQuote.quote("feat/api-routes"))
        assertEquals("'has space'", ShellQuote.quote("has space"))
        assertEquals("'it'\\''s'", ShellQuote.quote("it's"))
        assertEquals("''", ShellQuote.quote(""))
    }
}
