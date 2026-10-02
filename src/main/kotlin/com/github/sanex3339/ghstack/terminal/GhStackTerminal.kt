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
    fun ghStack(ghPath: String, vararg args: String): String = gh(ghPath, "stack", *args)

    fun gh(ghPath: String, vararg args: String): String = (listOf(ghPath) + args).joinToString(" ") { ShellQuote.quote(it) }
}
