package com.github.sanex3339.ghstack.ui

import com.github.sanex3339.ghstack.model.BranchStatus
import com.github.sanex3339.ghstack.model.BranchUi
import com.github.sanex3339.ghstack.model.StackUi
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import java.nio.file.Path
import javax.swing.Icon

object GhStackIcons {
    @JvmField
    val Stack: Icon = IconLoader.getIcon("/icons/ghStack.svg", GhStackIcons::class.java)
}

/** A branch selected in the Stacked PRs tree, exposed to actions through the data context. */
data class SelectedBranch(val root: Path, val stack: StackUi, val branch: BranchUi)

object GhStackDataKeys {
    val SELECTED_BRANCH: DataKey<SelectedBranch> = DataKey.create("GhStack.SelectedBranch")
}

/** Status glyphs shared by the tree and the switcher: ✓ merged, ◎ queued, ⚠ needs rebase, ○ open PR, ◌ not submitted. */
object StatusGlyphs {
    private val green = JBColor(0x1A7F37, 0x3FB950)
    private val yellow = JBColor(0x9A6700, 0xD29922)
    private val orange = JBColor(0xBC4C00, 0xDB6D28)

    fun glyph(status: BranchStatus): String = when (status) {
        BranchStatus.MERGED -> "✓"
        BranchStatus.QUEUED -> "◎"
        BranchStatus.NEEDS_REBASE -> "⚠"
        BranchStatus.OPEN -> "○"
        BranchStatus.NOT_SUBMITTED -> "◌"
    }

    fun label(status: BranchStatus): String = when (status) {
        BranchStatus.MERGED -> "merged"
        BranchStatus.QUEUED -> "queued"
        BranchStatus.NEEDS_REBASE -> "needs rebase"
        BranchStatus.OPEN -> "open"
        BranchStatus.NOT_SUBMITTED -> "not submitted"
    }

    fun attributes(status: BranchStatus): SimpleTextAttributes = SimpleTextAttributes(
        SimpleTextAttributes.STYLE_PLAIN,
        when (status) {
            BranchStatus.MERGED, BranchStatus.NOT_SUBMITTED -> JBColor.GRAY
            BranchStatus.QUEUED -> yellow
            BranchStatus.NEEDS_REBASE -> orange
            BranchStatus.OPEN -> green
        },
    )
}
