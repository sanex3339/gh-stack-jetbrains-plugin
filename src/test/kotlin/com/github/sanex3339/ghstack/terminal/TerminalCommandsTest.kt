package com.github.sanex3339.ghstack.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalCommandsTest {
    @Test
    fun `quotes the gh path and arguments for POSIX shells`() {
        assertEquals("/opt/homebrew/bin/gh stack modify --continue", TerminalCommands.ghStack("/opt/homebrew/bin/gh", "modify", "--continue", windows = false))
        assertEquals("'/Applications/My Tools/gh' auth login", TerminalCommands.gh("/Applications/My Tools/gh", "auth", "login", windows = false))
    }

    @Test
    fun `uses the call operator and doubled quotes for PowerShell`() {
        assertEquals("& 'C:\\Program Files\\GitHub CLI\\gh.exe' stack modify", TerminalCommands.ghStack("C:\\Program Files\\GitHub CLI\\gh.exe", "modify", windows = true))
        assertEquals("& 'C:\\Users\\O''Neil\\gh.exe' auth login", TerminalCommands.gh("C:\\Users\\O'Neil\\gh.exe", "auth", "login", windows = true))
    }
}
