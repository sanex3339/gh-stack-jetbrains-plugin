package com.github.sanex3339.ghstack.ui.dialogs

import com.github.sanex3339.ghstack.planning.NewStackRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class NewStackDialog(project: Project, localBranches: List<String>) : DialogWrapper(project) {
    private val baseCombo = ComboBox(arrayOf("") + localBranches).apply { isEditable = true }
    private val branchesArea = JBTextArea(6, 40)

    init {
        title = "New Stack"
        setOKButtonText("Create Stack")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Base branch:") {
            cell(baseCombo).align(AlignX.FILL).comment("Leave empty to use the repository's default branch.")
        }
        row("Branches:") {
            scrollCell(branchesArea).align(Align.FILL)
                .comment("One per line, bottom (merges first) to top. Existing branches are adopted; missing ones are created.")
        }.resizableRow()
    }

    override fun getPreferredFocusedComponent(): JComponent = branchesArea

    fun request() = NewStackRequest(baseCombo.editor.item?.toString().orEmpty(), branchesArea.text)

    override fun doValidate(): ValidationInfo? = request().validationError()?.let { ValidationInfo(it, branchesArea) }
}
