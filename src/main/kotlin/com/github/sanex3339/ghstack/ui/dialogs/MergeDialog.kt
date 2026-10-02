package com.github.sanex3339.ghstack.ui.dialogs

import com.github.sanex3339.ghstack.model.BranchUi
import com.github.sanex3339.ghstack.settings.MergeMethod
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.ListSelectionModel

/** Chooses how far up the stack to merge; [candidates] are bottom → top and all have PRs. */
class MergeDialog(project: Project, private val candidates: List<BranchUi>, initialMethod: MergeMethod) : DialogWrapper(project) {
    private val list = JBList(candidates.asReversed()).apply {
        cellRenderer = SimpleListCellRenderer.create("") { branch: BranchUi -> "#${branch.pr?.number}  ${branch.name}" }
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        selectedIndex = 0
    }
    private val methodCombo = ComboBox(MergeMethod.entries.toTypedArray()).apply { selectedItem = initialMethod }
    private val summary = JBLabel()

    init {
        title = "Merge Stack"
        setOKButtonText("Merge")
        list.addListSelectionListener { updateSummary() }
        updateSummary()
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label("Merge up to and including:") }
        row { scrollCell(list).align(Align.FILL) }.resizableRow()
        row("Method:") { cell(methodCombo) }
        row { cell(summary) }
        row {
            comment("GitHub merges the selected pull request and every unmerged one below it in a single all-or-nothing operation. If the base branch uses a merge queue, the stack is queued instead.")
        }
    }

    private fun included(): List<BranchUi> {
        val selected = list.selectedValue ?: return emptyList()
        return candidates.subList(0, candidates.indexOf(selected) + 1)
    }

    private fun updateSummary() {
        val prs = included()
        summary.text = if (prs.isEmpty()) "Select a pull request" else "Merges ${prs.size} pull request(s): " + prs.joinToString(", ") { "#${it.pr?.number}" }
    }

    fun selectedPr(): Int = requireNotNull(list.selectedValue?.pr).number

    fun method(): MergeMethod = methodCombo.item ?: MergeMethod.SQUASH

    override fun doValidate(): ValidationInfo? = if (list.selectedValue == null) ValidationInfo("Select a pull request", list) else null
}
