package com.github.sanex3339.ghstack.cli

import com.github.sanex3339.ghstack.testutil.Scripts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

class ProcessStackCliTest {
    private val dir: Path = Path.of(System.getProperty("java.io.tmpdir"))

    @Test
    fun `retries once when the stack file is locked`() {
        val gh = Scripts.executable(
            "gh",
            """
            if [ -f "$0.locked" ]; then echo "args:$*"; exit 0; fi
            touch "$0.locked"; echo "✗ locked" >&2; exit 8
            """,
        )
        val cli = ProcessStackCli(CommandRunner(), gh.toString(), "/usr/bin/git", dir, lockRetryDelayMs = 10)
        val result = cli.stack("push", "--remote", "origin")
        assertTrue(result.ok)
        assertEquals("args:stack push --remote origin\n", result.stdout)
    }

    @Test
    fun `cancelled before start throws`() {
        val cli = ProcessStackCli(CommandRunner(), "/bin/echo", "/usr/bin/git", dir, isCancelled = { true })
        assertThrows<CommandCancelledException> { cli.gh("hello") }
    }
}
