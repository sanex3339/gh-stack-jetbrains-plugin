package com.github.sanex3339.ghstack.terminal.impl

import com.github.sanex3339.ghstack.terminal.GhStackTerminal
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.nio.file.Path

class GhStackTerminalImpl(private val project: Project) : GhStackTerminal {
    override fun run(workDir: Path, command: String, tabName: String) {
        val widget = TerminalToolWindowManager.getInstance(project).createShellWidget(workDir.toString(), tabName, true, true)
        widget.sendCommandToExecute(command)
    }
}
