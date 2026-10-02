package com.github.sanex3339.ghstack.ui.toolwindow

import com.github.sanex3339.ghstack.ide.GhStackConsole
import com.github.sanex3339.ghstack.ide.GhStackToolWindow
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class GhStackToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()
        val stacks = contentFactory.createContent(StacksPanel(project, toolWindow.disposable), "Stacks", false)
        val console = contentFactory.createContent(GhStackConsole.getInstance(project).component(), GhStackToolWindow.CONSOLE_TAB, false)
        toolWindow.contentManager.addContent(stacks)
        toolWindow.contentManager.addContent(console)
    }
}
