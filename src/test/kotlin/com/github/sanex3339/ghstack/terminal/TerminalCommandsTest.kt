package com.github.sanex3339.ghstack.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalCommandsTest {
    @Test
    fun `quotes the gh path and arguments for the shell`() {
        assertEquals("/opt/homebrew/bin/gh stack modify --continue", TerminalCommands.ghStack("/opt/homebrew/bin/gh", "modify", "--continue"))
        assertEquals("'/Applications/My Tools/gh' auth login", TerminalCommands.gh("/Applications/My Tools/gh", "auth", "login"))
    }
}
