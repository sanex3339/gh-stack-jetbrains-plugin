package com.github.sanex3339.ghstack.ui.toolwindow

import com.github.sanex3339.ghstack.ide.GhStackOperations
import com.github.sanex3339.ghstack.ide.StackStateListener
import com.github.sanex3339.ghstack.ide.StackStateService
import com.github.sanex3339.ghstack.model.BranchStatus
import com.github.sanex3339.ghstack.model.StackUi
import com.github.sanex3339.ghstack.state.BadgeLink
import com.github.sanex3339.ghstack.state.Banners
import com.github.sanex3339.ghstack.state.RepoState
import com.github.sanex3339.ghstack.ui.GhStackCommands
import com.github.sanex3339.ghstack.ui.GhStackDataKeys
import com.github.sanex3339.ghstack.ui.SelectedBranch
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.ToolTipManager
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** Every local stack as a tree (branches top → bottom) with toolbar and banner, and the activity log below it. */
class StacksPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true), UiDataProvider {
    private val rootNode = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(rootNode)
    private val tree = Tree(model)
    private val banner = BannerPanel(project)
    private val loadingPanel = JBLoadingPanel(BorderLayout(), parent)
    private val expandedKeys = mutableSetOf<String>()
    private val treeArea = JPanel(BorderLayout())
    private val content = JPanel(BorderLayout())
    private val splitter = OnePixelSplitter(true, "StackedPRs.ActivityLog.Split", 0.7f)
    private val activityLog = ActivityLogPanel(project, parent) { collapsed -> layoutLog(collapsed) }
    private var shownRoot: Path? = null
    private var shownStacks: List<StackUi>? = null

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = StackTreeRenderer()
        ToolTipManager.sharedInstance().registerComponent(tree)
        installBadgeLinks()
        TreeSpeedSearch.installOn(tree, false) { path -> speedSearchText(path) }
        PopupHandler.installPopupMenu(tree, "GhStack.BranchPopup", "GhStackTree")
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val node = selectedNode() as? BranchNode ?: return false
                if (node.branch.isCurrent || node.branch.status == BranchStatus.MERGED) return false
                GhStackCommands.checkout(project, node.branch.name)
                return true
            }
        }.installOn(tree)
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                stackKey(event.path)?.let { expandedKeys += it }
            }

            override fun treeCollapsed(event: TreeExpansionEvent) {
                stackKey(event.path)?.let { expandedKeys -= it }
            }
        })

        val actionManager = ActionManager.getInstance()
        val toolbar = actionManager.createActionToolbar("GhStackToolWindow", actionManager.getAction("GhStack.ToolWindow.Toolbar") as ActionGroup, true)
        toolbar.targetComponent = this
        // Wrap onto a second row instead of hiding buttons behind a chevron when the tool window is narrow.
        toolbar.layoutStrategy = ToolbarLayoutStrategy.WRAP_STRATEGY
        setToolbar(toolbar.component)
        loadingPanel.add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        treeArea.add(loadingPanel, BorderLayout.CENTER)
        treeArea.add(banner, BorderLayout.SOUTH)
        layoutLog(activityLog.collapsed)
        setContent(content)

        project.messageBus.connect(parent).subscribe(
            StackStateService.TOPIC,
            StackStateListener { ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed) },
        )
        refresh()
        StackStateService.getInstance(project).requestAllLive()
    }

    /** Expanded: tree and log share a resizable split. Collapsed: only the log's header sits under the tree. */
    private fun layoutLog(collapsed: Boolean) {
        content.removeAll()
        if (collapsed) {
            splitter.firstComponent = null
            splitter.secondComponent = null
            content.add(treeArea, BorderLayout.CENTER)
            content.add(activityLog, BorderLayout.SOUTH)
        } else {
            splitter.firstComponent = treeArea
            splitter.secondComponent = activityLog
            content.add(splitter, BorderLayout.CENTER)
        }
        content.revalidate()
        content.repaint()
    }

    /** Badges carry a [BadgeLink] tag: open the PR / checks page, or jump to the blocking branch. */
    private fun installBadgeLinks() {
        fun linkAt(event: MouseEvent): BadgeLink? {
            val path = tree.getPathForLocation(event.x, event.y) ?: return null
            val bounds = tree.getPathBounds(path) ?: return null
            val row = tree.getRowForPath(path)
            val renderer = tree.cellRenderer.getTreeCellRendererComponent(
                tree, path.lastPathComponent, tree.isRowSelected(row), tree.isExpanded(row), model.isLeaf(path.lastPathComponent), row, false,
            ) as? StackTreeRenderer ?: return null
            renderer.bounds = bounds
            return renderer.getFragmentTagAt(event.x - bounds.x) as? BadgeLink
        }
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button != MouseEvent.BUTTON1 || event.clickCount != 1) return
                when (val link = linkAt(event)) {
                    is BadgeLink.Url -> GhStackCommands.openPr(link.url)
                    is BadgeLink.Branch -> select(link.name)
                    null -> return
                }
                event.consume()
            }

            override fun mouseMoved(event: MouseEvent) {
                tree.cursor = if (linkAt(event) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        }
        tree.addMouseListener(mouse)
        tree.addMouseMotionListener(mouse)
    }

    private fun refresh() {
        val state = StackStateService.getInstance(project).activeState()
        banner.update(Banners.of(state))
        updateEmptyText(state)
        updateProgress(state)
        val stacks = state?.stacks.orEmpty()
        if (stacks == shownStacks && state?.root == shownRoot) {
            tree.repaint()
            return
        }
        val previouslySelected = (selectedNode() as? BranchNode)?.branch?.name
        shownStacks = stacks
        shownRoot = state?.root

        rootNode.removeAllChildren()
        stacks.sortedByDescending { it.isCurrent }.forEach { stack ->
            val stackNode = DefaultMutableTreeNode(StackNode(stack))
            stack.branches.asReversed().forEach { stackNode.add(DefaultMutableTreeNode(BranchNode(stack, it))) }
            stackNode.add(DefaultMutableTreeNode(TrunkNode(stack)))
            rootNode.add(stackNode)
        }
        model.reload()
        stackNodes().forEach { node ->
            val stack = (node.userObject as StackNode).stack
            if (stack.isCurrent || stack.key in expandedKeys) tree.expandPath(TreePath(node.path))
        }
        select(previouslySelected ?: state?.currentBranch)
    }

    /** A spinner over the tree while an operation (switching branches, sync, rebase…) runs. */
    private fun updateProgress(state: RepoState?) {
        val text = state?.root?.let { GhStackOperations.getInstance(project).progressText(it) }
        if (text != null) {
            loadingPanel.setLoadingText(text)
            if (!loadingPanel.isLoading) loadingPanel.startLoading()
        } else if (loadingPanel.isLoading) {
            loadingPanel.stopLoading()
        }
    }

    private fun updateEmptyText(state: RepoState?) {
        val emptyText = tree.emptyText
        emptyText.clear()
        when {
            state == null -> emptyText.text = "No Git repository in this project"
            state.cliStatus == null -> emptyText.text = "Loading…"
            !state.ready -> emptyText.text = ""
            else -> {
                emptyText.text = "No stacks yet"
                emptyText.appendSecondaryText("New stack…", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { GhStackCommands.newStack(project) }
                emptyText.appendSecondaryText("    ", SimpleTextAttributes.REGULAR_ATTRIBUTES, null)
                emptyText.appendSecondaryText("Check out stack…", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { GhStackCommands.checkoutStack(project) }
            }
        }
    }

    private fun stackNodes(): List<DefaultMutableTreeNode> =
        rootNode.children().asSequence().filterIsInstance<DefaultMutableTreeNode>().toList()

    private fun select(branch: String?) {
        if (branch == null) return
        val node = stackNodes().flatMap { it.children().asSequence().filterIsInstance<DefaultMutableTreeNode>() }
            .firstOrNull { (it.userObject as? BranchNode)?.branch?.name == branch } ?: return
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun selectedNode(): StackTreeNode? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? StackTreeNode

    private fun stackKey(path: TreePath): String? = ((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? StackNode)?.stack?.key

    private fun speedSearchText(path: TreePath): String = when (val node = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
        is BranchNode -> node.branch.name
        is StackNode -> node.stack.title
        is TrunkNode -> node.stack.trunk
        else -> ""
    }

    override fun uiDataSnapshot(sink: DataSink) {
        val root = shownRoot ?: return
        val node = selectedNode() as? BranchNode ?: return
        sink[GhStackDataKeys.SELECTED_BRANCH] = SelectedBranch(root, node.stack, node.branch)
    }
}
