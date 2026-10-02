package com.github.sanex3339.ghstack.ui.dialogs

import com.github.sanex3339.ghstack.planning.AddBranchRequest
import com.github.sanex3339.ghstack.planning.AddCommitMode
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class AddBranchDialog(project: Project, top: String) : DialogWrapper(project) {
    private val nameField = JBTextField()
    private val modeCombo = ComboBox(AddCommitMode.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { mode: AddCommitMode -> label(mode) }
    }
    private val messageArea = JBTextArea(3, 40).apply { isEnabled = false }

    init {
        title = "Add Branch on Top of $top"
        setOKButtonText("Add Branch")
        modeCombo.addActionListener { messageArea.isEnabled = modeCombo.item != AddCommitMode.NONE }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Branch name:") {
            cell(nameField).align(AlignX.FILL).comment("Optional when committing: gh stack then generates it from the message.")
        }
        row("Commit:") { cell(modeCombo) }
        row("Message:") { scrollCell(messageArea).align(AlignX.FILL) }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    fun request() = AddBranchRequest(nameField.text, modeCombo.item ?: AddCommitMode.NONE, messageArea.text)

    override fun doValidate(): ValidationInfo? = request().validationError()?.let { error ->
        ValidationInfo(error, if ("message" in error) messageArea else nameField)
    }

    private companion object {
        fun label(mode: AddCommitMode) = when (mode) {
            AddCommitMode.NONE -> "Don't commit (uncommitted changes carry over)"
            AddCommitMode.STAGED -> "Commit staged changes"
            AddCommitMode.TRACKED -> "Commit changes to tracked files (-u)"
            AddCommitMode.ALL -> "Commit all changes, including untracked files (-A)"
        }
    }
}
