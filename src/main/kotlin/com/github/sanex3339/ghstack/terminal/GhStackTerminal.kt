package com.github.sanex3339.ghstack.terminal

import com.github.sanex3339.ghstack.cli.ShellQuote
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.project.Project
import java.nio.file.Path

/** Runs an interactive command in an IDE terminal tab. Implemented only when the Terminal plugin is enabled. */
interface GhStackTerminal {
    fun run(workDir: Path, command: String, tabName: String)

    companion object {
        fun getInstance(project: Project): GhStackTerminal? = project.serviceOrNull()
    }
}

object TerminalCommands {
    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    fun ghStack(ghPath: String, vararg args: String, windows: Boolean = isWindows): String = gh(ghPath, "stack", *args, windows = windows)

    fun gh(ghPath: String, vararg args: String, windows: Boolean = isWindows): String {
        if (!windows) return (listOf(ghPath) + args).joinToString(" ") { ShellQuote.quote(it) }
        // PowerShell treats a quoted first token as a string, so invoke it with the call operator.
        return "& " + (listOf(ghPath) + args).joinToString(" ") { ShellQuote.quotePowerShell(it) }
    }
}
