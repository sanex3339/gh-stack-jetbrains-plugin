package com.github.sanex3339.ghstack.ui.dialogs

import com.github.sanex3339.ghstack.ops.PrDraft
import com.github.sanex3339.ghstack.ops.SubmitRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/** Titles, bodies and draft state for the pull requests Submit is about to open, bottom → top. */
class SubmitDialog(project: Project, private val drafts: List<PrDraft>) : DialogWrapper(project) {
    private val titleFields = drafts.map { JBTextField(it.title) }
    private val bodyAreas = drafts.map { JBTextArea(it.body, 4, 60).apply { lineWrap = true; wrapStyleWord = true } }
    private val draftBoxes = drafts.map { JBCheckBox("Create as draft", it.draft) }
    private val markReady = JBCheckBox("Also mark existing pull requests in the stack ready for review")

    init {
        title = "Submit Stack"
        setOKButtonText("Submit")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val content = panel {
            row { label("New pull requests, bottom to top. Each one targets the branch below it.") }
            drafts.forEachIndexed { index, draft ->
                group("${draft.branch} → ${draft.base}") {
                    row("Title:") { cell(titleFields[index]).align(AlignX.FILL) }
                    row("Body:") { scrollCell(bodyAreas[index]).align(AlignX.FILL) }
                    row { cell(draftBoxes[index]) }
                }
            }
            row { cell(markReady) }
        }
        if (drafts.size <= 2) return content
        return JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            preferredSize = JBUI.size(640, 560)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent? = titleFields.firstOrNull()

    override fun doValidate(): ValidationInfo? =
        titleFields.firstOrNull { it.text.isBlank() }?.let { ValidationInfo("Enter a title", it) }

    fun showAndGetRequest(): SubmitRequest? {
        if (!showAndGet()) return null
        val edited = drafts.mapIndexed { index, draft ->
            draft.copy(title = titleFields[index].text.trim(), body = bodyAreas[index].text, draft = draftBoxes[index].isSelected)
        }
        return SubmitRequest(edited, markReady.isSelected)
    }
}
