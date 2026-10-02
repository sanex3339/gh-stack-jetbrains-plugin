package com.github.sanex3339.ghstack.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

object GhStackToolWindow {
    const val ID = "Stacked PRs"

    /** Must be called on the EDT. [revealLog] also expands the activity log. */
    fun show(project: Project, revealLog: Boolean = false) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
        window.activate { if (revealLog) OperationLog.getInstance(project).reveal() }
    }
}
