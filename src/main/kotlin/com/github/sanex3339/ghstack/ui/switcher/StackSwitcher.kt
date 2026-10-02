package com.github.sanex3339.ghstack.ui.switcher

import com.github.sanex3339.ghstack.ide.GhStackOperations
import com.github.sanex3339.ghstack.ide.StackStateListener
import com.github.sanex3339.ghstack.ide.StackStateService
import com.github.sanex3339.ghstack.model.BranchStatus
import com.github.sanex3339.ghstack.model.BranchUi
import com.github.sanex3339.ghstack.model.StackUi
import com.github.sanex3339.ghstack.state.StatusText
import com.github.sanex3339.ghstack.ui.GhStackCommands
import com.github.sanex3339.ghstack.ui.GhStackIcons
import com.github.sanex3339.ghstack.ui.StatusGlyphs
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.ExpandableComboAction
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent

/** The fast branch switcher: current stack's branches (top → bottom), other stacks as submenus, navigation. */
object StackSwitcherPopup {
    fun create(project: Project, dataContext: DataContext): ListPopup {
        val state = StackStateService.getInstance(project).activeState()
        val group = DefaultActionGroup()
        val stacks = state?.stacks.orEmpty()
        stacks.firstOrNull { it.isCurrent }?.let { current ->
            group.addSeparator("${current.title} · ${current.trunk}")
            current.branches.asReversed().forEach { group.add(SwitchToBranchAction(project, current, it)) }
        }
        val others = stacks.filter { !it.isCurrent }
        if (others.isNotEmpty()) {
            group.addSeparator("Other stacks")
            others.forEach { stack ->
                val submenu = DefaultActionGroup.createPopupGroup { "${stack.title} · ${stack.trunk}" }
                submenu.templatePresentation.icon = GhStackIcons.Stack
                stack.branches.asReversed().forEach { submenu.add(SwitchToBranchAction(project, stack, it)) }
                group.add(submenu)
            }
        }
        group.addSeparator()
        group.add(ActionManager.getInstance().getAction("GhStack.SwitcherFooter"))
        val title = if (stacks.isEmpty()) "No stacks" else null
        return JBPopupFactory.getInstance().createActionGroupPopup(title, group, dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
    }
}

private class SwitchToBranchAction(
    private val project: Project,
    private val stack: StackUi,
    private val branch: BranchUi,
) : DumbAwareAction() {
    init {
        val pr = branch.pr?.let { "   #${it.number}" }.orEmpty()
        templatePresentation.setText("${StatusGlyphs.glyph(branch.status)}  ${branch.name}$pr", false)
        templatePresentation.description = "${StatusGlyphs.label(branch.status)} · ${stack.title}"
        templatePresentation.icon = if (branch.isCurrent) AllIcons.Actions.Checked else null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !branch.isCurrent && branch.status != BranchStatus.MERGED
    }

    override fun actionPerformed(e: AnActionEvent) = GhStackCommands.checkout(project, branch.name)
}

/** Dropdown next to the Git branch widget in the main toolbar: `⧉ api 2/4 ▾`. */
class StackSwitcherToolbarAction : ExpandableComboAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val state = project?.let { StackStateService.getInstance(it).activeState() }
        val progress = state?.let { GhStackOperations.getInstance(project).progressText(it.root) }
        val text = progress ?: StatusText.of(state)?.removePrefix("⧉ ")
        e.presentation.isEnabledAndVisible = text != null
        if (text != null) {
            e.presentation.text = text
            e.presentation.icon = if (progress != null) AnimatedIcon.Default.INSTANCE else GhStackIcons.Stack
            e.presentation.description = "Switch branches in the current stack"
        }
    }

    override fun createPopup(event: AnActionEvent): JBPopup? {
        val project = event.project ?: return null
        return StackSwitcherPopup.create(project, event.dataContext)
    }
}

/** Shortcut entry point (chord + L). */
class ShowStackSwitcherAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        StackSwitcherPopup.create(project, e.dataContext).showInBestPositionFor(e.dataContext)
    }
}

class StackStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID

    override fun getDisplayName(): String = "Stacked PRs"

    override fun createWidget(project: Project): StatusBarWidget = StackStatusBarWidget(project)

    companion object {
        const val ID = "GhStackStatusBar"
    }
}

private class StackStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    override fun ID(): String = StackStatusBarWidgetFactory.ID

    override fun install(statusBar: StatusBar) {
        project.messageBus.connect(this).subscribe(
            StackStateService.TOPIC,
            StackStateListener { ApplicationManager.getApplication().invokeLater({ statusBar.updateWidget(ID()) }, project.disposed) },
        )
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String {
        val state = StackStateService.getInstance(project).activeState()
        val progress = state?.let { GhStackOperations.getInstance(project).progressText(it.root) }
        return progress?.let { "⧉ $it" } ?: StatusText.of(state).orEmpty()
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String = "Stacked PRs: click to switch branches"

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        val component = event.component
        val popup = StackSwitcherPopup.create(project, DataManager.getInstance().getDataContext(component))
        popup.show(RelativePoint(component, Point(0, -popup.content.preferredSize.height)))
    }

    override fun dispose() = Unit
}
