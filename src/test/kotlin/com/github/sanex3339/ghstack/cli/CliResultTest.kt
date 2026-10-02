package com.github.sanex3339.ghstack.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliResultTest {
    @Test
    fun `maps documented exit codes`() {
        assertEquals(ExitCode.CONFLICT, ExitCode.fromInt(3))
        assertEquals(ExitCode.MODIFY_RECOVERY, ExitCode.fromInt(10))
        assertEquals(ExitCode.UNKNOWN, ExitCode.fromInt(42))
        assertEquals(ExitCode.UNKNOWN, ExitCode.fromInt(-1))
    }

    @Test
    fun `ok requires exit zero and no cancellation`() {
        assertTrue(CliResult(0, "", "").ok)
        assertFalse(CliResult(1, "", "").ok)
        assertFalse(CliResult(0, "", "", cancelled = true).ok)
    }

    @Test
    fun `summary prefers the gh stack error line from stderr`() {
        val stderr = """
            Stack detected: (main) <- auth <- api
            ✗ can only add branches to the top of the stack; run `gh stack top` then `gh stack add`
            Or to restructure your stack and insert a branch, use `gh stack modify`
        """.trimIndent()
        val result = CliResult(5, "", stderr)
        assertEquals("✗ can only add branches to the top of the stack; run `gh stack top` then `gh stack add`", result.summary())
    }

    @Test
    fun `summary falls back to warnings, git fatal lines, then the last line`() {
        assertEquals("⚠ Rebasing api onto auth — conflict", CliResult(3, "", "✓ Rebased auth\n⚠ Rebasing api onto auth — conflict\nmore").summary())
        assertEquals("fatal: not a git repository", CliResult(128, "", "fatal: not a git repository\n").summary())
        assertEquals("last line", CliResult(1, "first\nlast line\n", "").summary())
        assertEquals("exit code 1", CliResult(1, "", "").summary())
    }

    @Test
    fun `combined output joins stdout and stderr`() {
        assertEquals("out\nerr", CliResult(0, "out", "err").combinedOutput)
        assertEquals("err", CliResult(0, "", "err").combinedOutput)
    }
}
