package com.github.sanex3339.ghstack.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

data class MoveChangesRequest(val paths: List<String>, val target: String, val message: String)

/** Picks which uncommitted files to move, the layer to commit them on, and the commit message. */
class MoveChangesDialog(
    project: Project,
    private val files: List<String>,
    preselected: Set<String>,
    layers: List<String>,
    defaultLayer: String,
) : DialogWrapper(project) {
    private val fileList = CheckBoxList<String>().apply {
        files.forEach { addItem(it, it, it in preselected) }
    }
    private val layerCombo = ComboBox(layers.toTypedArray()).apply { selectedItem = defaultLayer }
    private val messageField = JBTextField()

    init {
        title = "Move Changes to Layer"
        setOKButtonText("Move and Commit")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Move to:") {
            cell(layerCombo).align(AlignX.FILL)
                .comment("The files are committed on this branch, then the branches above it are rebased to include them.")
        }
        row("Commit message:") { cell(messageField).align(AlignX.FILL) }
        row {
            cell(JBScrollPane(fileList).apply { preferredSize = JBUI.size(520, 220) }).align(Align.FILL)
        }.resizableRow()
    }

    override fun getPreferredFocusedComponent(): JComponent = messageField

    fun request() = MoveChangesRequest(
        paths = files.filter { fileList.isItemSelected(it) },
        target = layerCombo.item,
        message = messageField.text.trim(),
    )

    override fun doValidate(): ValidationInfo? {
        val request = request()
        return when {
            request.paths.isEmpty() -> ValidationInfo("Select at least one file", fileList)
            request.message.isBlank() -> ValidationInfo("Enter a commit message", messageField)
            else -> null
        }
    }
}
