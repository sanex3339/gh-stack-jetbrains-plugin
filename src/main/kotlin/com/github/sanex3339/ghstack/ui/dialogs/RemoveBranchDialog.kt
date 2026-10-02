package com.github.sanex3339.ghstack.ui.dialogs

import com.github.sanex3339.ghstack.model.RemoveMode
import com.github.sanex3339.ghstack.model.StackUi
import com.github.sanex3339.ghstack.ops.RemovalOptions
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/** Asks how to take [branch] out of [stack]: drop its commits, or keep them in a neighbour. */
class RemoveBranchDialog(
    project: Project,
    private val stack: StackUi,
    private val branch: String,
    modes: Set<RemoveMode>,
    private val onGitHub: Boolean,
) : DialogWrapper(project) {
    private val active = stack.activeBranches.map { it.name }
    private val below = stack.activeParentOf(branch)
    private val above = active.getOrNull(active.indexOf(branch) + 1)
    private val pr = stack.branch(branch)?.pr?.number

    private val dropButton = JBRadioButton("Drop it and its commits (branches above are rebased without them)")
    private val foldDownButton = JBRadioButton("Keep its commits: fold into $below (the branch below)")
    private val foldUpButton = JBRadioButton("Keep its commits: fold into ${above ?: "the branch above"} (the branch above)")
    private val closePrBox = JBCheckBox("Close PR #$pr", true)
    private val deleteBranchBox = JBCheckBox("Delete the local branch $branch", false)

    init {
        title = "Remove $branch from Stack"
        setOKButtonText("Remove")
        dropButton.isEnabled = RemoveMode.DROP in modes
        foldDownButton.isEnabled = RemoveMode.FOLD_DOWN in modes
        foldUpButton.isEnabled = RemoveMode.FOLD_UP in modes
        listOf(dropButton, foldUpButton, foldDownButton).firstOrNull { it.isEnabled }?.isSelected = true
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        // The UI DSL owns the ButtonGroup; radio buttons must be added inside buttonsGroup.
        buttonsGroup {
            row { cell(dropButton) }
            row { cell(foldDownButton) }
            row { cell(foldUpButton) }
        }
        separator()
        if (pr != null) row { cell(closePrBox) }
        row { cell(deleteBranchBox) }
        if (onGitHub) {
            row {
                comment("${stack.title} will be recreated on GitHub with the remaining pull requests (it gets a new stack number).")
            }
        }
    }

    fun mode(): RemoveMode = when {
        foldDownButton.isSelected -> RemoveMode.FOLD_DOWN
        foldUpButton.isSelected -> RemoveMode.FOLD_UP
        else -> RemoveMode.DROP
    }

    fun options() = RemovalOptions(closePr = pr != null && closePrBox.isSelected, deleteBranch = deleteBranchBox.isSelected)
}
