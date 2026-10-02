package com.github.sanex3339.ghstack.ui.toolwindow

import com.github.sanex3339.ghstack.model.BranchStatus
import com.github.sanex3339.ghstack.model.BranchUi
import com.github.sanex3339.ghstack.model.StackUi
import com.github.sanex3339.ghstack.settings.GhStackSettings
import com.github.sanex3339.ghstack.state.BranchBadges
import com.github.sanex3339.ghstack.ui.GhStackIcons
import com.github.sanex3339.ghstack.ui.StatusGlyphs
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

sealed interface StackTreeNode {
    val stack: StackUi
}

data class StackNode(override val stack: StackUi) : StackTreeNode

data class BranchNode(override val stack: StackUi, val branch: BranchUi) : StackTreeNode

data class TrunkNode(override val stack: StackUi) : StackTreeNode

/**
 * Branch rows: `» ○ name  #123  needs review  ⛔ #120`. Badges carry a [com.github.sanex3339.ghstack.state.BadgeLink]
 * tag so clicks can open the PR / checks page or jump to the blocking branch; the tooltip spells everything out.
 */
class StackTreeRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        toolTipText = null
        when (val node = (value as? DefaultMutableTreeNode)?.userObject) {
            is StackNode -> {
                icon = GhStackIcons.Stack
                val stack = node.stack
                append(stack.title, if (stack.isCurrent) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ${stack.trunk}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (!expanded) {
                    val count = stack.activeBranches.size
                    append("  · $count ${if (count == 1) "branch" else "branches"}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            is BranchNode -> {
                val branch = node.branch
                append(if (branch.isCurrent) "» " else "   ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append(StatusGlyphs.glyph(branch.status) + "  ", StatusGlyphs.attributes(branch.status))
                val title = branch.details?.title?.takeIf { it.isNotBlank() && GhStackSettings.getInstance().state.showPrTitles }
                append(
                    title ?: branch.name,
                    when {
                        branch.isCurrent -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                        branch.status == BranchStatus.MERGED -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    },
                )
                branch.pr?.let { append("  #${it.number}", SimpleTextAttributes.GRAYED_ATTRIBUTES, branch.pr.url?.let(com.github.sanex3339.ghstack.state.BadgeLink::Url)) }
                BranchBadges.of(branch, node.stack).forEach { badge ->
                    append("  ")
                    append(badge.compact, StatusGlyphs.badgeAttributes(badge.tone, clickable = badge.link != null), badge.link)
                }
                toolTipText = BranchBadges.tooltip(branch, node.stack)
            }
            is TrunkNode -> append("└ ${node.stack.trunk}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            else -> Unit
        }
    }
}
