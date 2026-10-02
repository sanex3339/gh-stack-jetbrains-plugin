package com.github.sanex3339.ghstack.ui.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class GhStackToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val stacks = ContentFactory.getInstance().createContent(StacksPanel(project, toolWindow.disposable), "", false)
        toolWindow.contentManager.addContent(stacks)
    }
}
