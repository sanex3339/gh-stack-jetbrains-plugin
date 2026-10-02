package com.github.sanex3339.ghstack.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

object GhStackToolWindow {
    const val ID = "GH Stack"
    const val CONSOLE_TAB = "Console"

    /** Must be called on the EDT. */
    fun show(project: Project, console: Boolean = false) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
        window.activate {
            if (console) window.contentManager.findContent(CONSOLE_TAB)?.let { window.contentManager.setSelectedContent(it) }
        }
    }
}
