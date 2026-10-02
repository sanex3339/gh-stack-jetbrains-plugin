package com.github.sanex3339.ghstack.cli

import com.github.sanex3339.ghstack.testutil.Scripts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class GhStatusCheckerTest {
    private val checker = GhStatusChecker(CommandRunner())
    private val dir: Path = Path.of(System.getProperty("java.io.tmpdir"))

    private fun fakeGh(versionExit: Int, authExit: Int): String = Scripts.executable(
        "gh",
        """
        if [ "$1" = "stack" ]; then echo "gh stack version 0.1.1"; exit $versionExit; fi
        if [ "$1" = "auth" ]; then echo "gho_secret"; exit $authExit; fi
        exit 1
        """,
    ).toString()

    @Test
    fun `ready when the extension answers and a token exists`() {
        val gh = fakeGh(0, 0)
        assertEquals(CliStatus.Ready(gh, "/usr/bin/git", "0.1.1"), checker.check(gh, "/usr/bin/git", dir))
    }

    @Test
    fun `reports what is missing`() {
        val noExtension = fakeGh(1, 0)
        assertEquals(CliStatus.ExtensionMissing(noExtension), checker.check(noExtension, "/usr/bin/git", dir))
        val loggedOut = fakeGh(0, 1)
        assertEquals(CliStatus.NotAuthenticated(loggedOut), checker.check(loggedOut, "/usr/bin/git", dir))
        assertEquals(CliStatus.GhNotFound, checker.check(null, "/usr/bin/git", dir))
        assertEquals(CliStatus.GitNotFound, checker.check(noExtension, null, dir))
    }

    @Test
    fun `parses versions`() {
        assertEquals("0.1.1", GhStatusChecker.parseVersion("gh stack version 0.1.1\n"))
        assertEquals("0.2.0-rc1", GhStatusChecker.parseVersion("gh stack version v0.2.0-rc1"))
        assertEquals(null, GhStatusChecker.parseVersion("unknown command \"stack\""))
    }
}
