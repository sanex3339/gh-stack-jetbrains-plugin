package com.github.sanex3339.ghstack.actions

import com.github.sanex3339.ghstack.ide.GhStackOperations
import com.github.sanex3339.ghstack.ide.GhStackToolWindow
import com.github.sanex3339.ghstack.ide.StackStateService
import com.github.sanex3339.ghstack.model.BranchStatus
import com.github.sanex3339.ghstack.model.BranchUi
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.model.StackUi
import com.github.sanex3339.ghstack.planning.InsertDirection
import com.github.sanex3339.ghstack.state.RepoState
import com.github.sanex3339.ghstack.terminal.GhStackTerminal
import com.github.sanex3339.ghstack.ui.GhStackCommands
import com.github.sanex3339.ghstack.ui.GhStackDataKeys
import com.github.sanex3339.ghstack.ui.RebaseMode
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Base for GH Stack actions. By default an action needs a ready CLI, no running operation,
 * a current stack, and no rebase/modify in progress; subclasses relax or tighten that.
 */
abstract class GhStackAction(icon: Icon? = null) : DumbAwareAction() {
    init {
        if (icon != null) templatePresentation.icon = icon
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    protected open val requiresReadyCli: Boolean = true

    override fun update(e: AnActionEvent) {
        val project = e.project
        val state = project?.let { StackStateService.getInstance(it).activeState() }
        if (project == null || state == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isVisible = isVisible(project, state, e)
        e.presentation.isEnabled = (!requiresReadyCli || state.ready) &&
            !GhStackOperations.getInstance(project).isBusy(state.root) &&
            isEnabled(state, e)
    }

    protected open fun isVisible(project: Project, state: RepoState, e: AnActionEvent): Boolean = true

    protected open fun isEnabled(state: RepoState, e: AnActionEvent): Boolean = state.currentStack != null && state.noBlockingOperation()

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        perform(project, e)
    }

    protected abstract fun perform(project: Project, e: AnActionEvent)

    protected fun RepoState.noBlockingOperation() = operation == OperationState.None || operation == OperationState.ModifyPendingSubmit

    /** The branch selected in the tree, else the current branch. */
    protected fun targetBranch(e: AnActionEvent, state: RepoState): Triple<Path, StackUi, BranchUi>? {
        e.getData(GhStackDataKeys.SELECTED_BRANCH)?.let { return Triple(it.root, it.stack, it.branch) }
        val stack = state.currentStack ?: return null
        val branch = state.currentBranch?.let(stack::branch) ?: return null
        return Triple(state.root, stack, branch)
    }
}

// ── Navigation ────────────────────────────────────────────────────────────────

abstract class NavigateAction(private val command: String, icon: Icon? = null) : GhStackAction(icon) {
    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.navigate(project, command)
}

class UpAction : NavigateAction("up", AllIcons.Actions.MoveUp)

class DownAction : NavigateAction("down", AllIcons.Actions.MoveDown)

class TopAction : NavigateAction("top")

class BottomAction : NavigateAction("bottom")

class TrunkAction : NavigateAction("trunk")

class CheckoutSelectedBranchAction : GhStackAction(AllIcons.Actions.CheckOut) {
    override fun isVisible(project: Project, state: RepoState, e: AnActionEvent) = e.getData(GhStackDataKeys.SELECTED_BRANCH) != null

    override fun isEnabled(state: RepoState, e: AnActionEvent): Boolean {
        val branch = e.getData(GhStackDataKeys.SELECTED_BRANCH)?.branch ?: return false
        return !branch.isCurrent && branch.status != BranchStatus.MERGED && state.operation == OperationState.None
    }

    override fun perform(project: Project, e: AnActionEvent) {
        e.getData(GhStackDataKeys.SELECTED_BRANCH)?.let { GhStackCommands.checkout(project, it.branch.name) }
    }
}

class CheckoutStackAction : GhStackAction() {
    override fun isEnabled(state: RepoState, e: AnActionEvent) = state.operation == OperationState.None

    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.checkoutStack(project)
}

// ── Remote operations ─────────────────────────────────────────────────────────

class SyncAction : GhStackAction(AllIcons.Vcs.Fetch) {
    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.sync(project, prune = false)
}

class SyncPruneAction : GhStackAction() {
    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.sync(project, prune = true)
}

class PushAction : GhStackAction(AllIcons.Vcs.Push) {
    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.push(project)
}

class SubmitAction : GhStackAction(AllIcons.Vcs.Vendors.Github) {
    override fun isEnabled(state: RepoState, e: AnActionEvent) = super.isEnabled(state, e) && !state.stacksUnavailable

    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.submit(project)
}

class SubmitInTerminalAction : GhStackAction() {
    override fun isVisible(project: Project, state: RepoState, e: AnActionEvent) = GhStackTerminal.getInstance(project) != null

    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.submitInTerminal(project)
}

class MergeAction : GhStackAction(AllIcons.Vcs.Merge) {
    override fun isEnabled(state: RepoState, e: AnActionEvent) =
        super.isEnabled(state, e) && !state.stacksUnavailable && state.currentStack?.activeBranches?.any { it.pr != null } == true

    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.merge(project)
}

// ── Rebase & conflicts ────────────────────────────────────────────────────────

abstract class RebaseAction(private val mode: RebaseMode, icon: Icon? = null) : GhStackAction(icon) {
    override fun isEnabled(state: RepoState, e: AnActionEvent) = state.currentStack != null && state.operation == OperationState.None

    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.rebase(project, mode)
}

class RebaseStackAction : RebaseAction(RebaseMode.STACK)

class RebaseUpstackAction : RebaseAction(RebaseMode.UPSTACK)

class RebaseDownstackAction : RebaseAction(RebaseMode.DOWNSTACK)

class RebaseUpstackFromHereAction : GhStackAction() {
    override fun isVisible(project: Project, state: RepoState, e: AnActionEvent) = e.getData(GhStackDataKeys.SELECTED_BRANCH) != null

    override fun isEnabled(state: RepoState, e: AnActionEvent): Boolean {
        val selected = e.getData(GhStackDataKeys.SELECTED_BRANCH) ?: return false
        return selected.stack.isCurrent && selected.branch.status != BranchStatus.MERGED && state.operation == OperationState.None
    }

    override fun perform(project: Project, e: AnActionEvent) {
        val selected = e.getData(GhStackDataKeys.SELECTED_BRANCH) ?: return
        GhStackCommands.rebase(project, RebaseMode.UPSTACK, fromBranch = selected.branch.name)
    }
}

abstract class RebaseInProgressAction(icon: Icon? = null) : GhStackAction(icon) {
    override fun isEnabled(state: RepoState, e: AnActionEvent) = state.operation is OperationState.RebaseConflict
}

class RebaseContinueAction : RebaseInProgressAction(AllIcons.Actions.Resume) {
    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.rebaseContinue(project)
}

class RebaseAbortAction : RebaseInProgressAction(AllIcons.Vcs.Abort) {
    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.rebaseAbort(project)
}

class ResolveConflictsAction : RebaseInProgressAction(AllIcons.Vcs.Merge) {
    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.resolveConflicts(project)
}

// ── Structure ─────────────────────────────────────────────────────────────────

class NewStackAction : GhStackAction(AllIcons.General.Add) {
    override fun isEnabled(state: RepoState, e: AnActionEvent) = state.operation == OperationState.None

    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.newStack(project)
}

class AddBranchAction : GhStackAction(AllIcons.General.Add) {
    override fun isEnabled(state: RepoState, e: AnActionEvent) = state.currentStack != null && state.operation == OperationState.None

    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.addBranch(project)
}

abstract class InsertAction(private val direction: InsertDirection, icon: Icon? = null) : GhStackAction(icon) {
    override fun isEnabled(state: RepoState, e: AnActionEvent): Boolean {
        if (state.operation != OperationState.None) return false
        val (_, _, branch) = targetBranch(e, state) ?: return false
        return branch.status != BranchStatus.MERGED
    }

    override fun perform(project: Project, e: AnActionEvent) {
        GhStackCommands.insert(project, direction, e.getData(GhStackDataKeys.SELECTED_BRANCH)?.branch?.name)
    }
}

class InsertBelowAction : InsertAction(InsertDirection.BELOW, AllIcons.Vcs.Branch)

class InsertAboveAction : InsertAction(InsertDirection.ABOVE)

class ModifyAction : GhStackAction(AllIcons.Actions.Edit) {
    override fun isVisible(project: Project, state: RepoState, e: AnActionEvent) = GhStackTerminal.getInstance(project) != null

    override fun isEnabled(state: RepoState, e: AnActionEvent) = state.currentStack != null && state.operation == OperationState.None

    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.modify(project)
}

abstract class ModifyInProgressAction : GhStackAction() {
    override fun isEnabled(state: RepoState, e: AnActionEvent) = state.operation is OperationState.ModifyInterrupted
}

class ModifyContinueAction : ModifyInProgressAction() {
    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.modifyContinue(project)
}

class ModifyAbortAction : ModifyInProgressAction() {
    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.modifyAbort(project)
}

class UnstackAction : GhStackAction() {
    override fun perform(project: Project, e: AnActionEvent) = GhStackCommands.unstack(project)
}

// ── Branch helpers ────────────────────────────────────────────────────────────

class LayerDiffAction : GhStackAction(AllIcons.Actions.Diff) {
    override fun isEnabled(state: RepoState, e: AnActionEvent): Boolean {
        val (_, _, branch) = targetBranch(e, state) ?: return false
        return branch.status != BranchStatus.MERGED
    }

    override fun perform(project: Project, e: AnActionEvent) {
        val state = StackStateService.getInstance(project).activeState() ?: return
        val (root, stack, branch) = targetBranch(e, state) ?: return
        GhStackCommands.layerDiff(project, root, stack, branch.name)
    }
}

abstract class PrUrlAction(icon: Icon) : GhStackAction(icon) {
    override val requiresReadyCli = false

    override fun isEnabled(state: RepoState, e: AnActionEvent) = targetBranch(e, state)?.third?.pr?.url != null

    override fun perform(project: Project, e: AnActionEvent) {
        val state = StackStateService.getInstance(project).activeState() ?: return
        targetBranch(e, state)?.third?.pr?.url?.let(::use)
    }

    protected abstract fun use(url: String)
}

class OpenPrAction : PrUrlAction(AllIcons.Vcs.Vendors.Github) {
    override fun use(url: String) = GhStackCommands.openPr(url)
}

class CopyPrUrlAction : PrUrlAction(AllIcons.Actions.Copy) {
    override fun use(url: String) = GhStackCommands.copyPrUrl(url)
}

// ── Misc ──────────────────────────────────────────────────────────────────────

class RefreshAction : GhStackAction(AllIcons.Actions.Refresh) {
    override val requiresReadyCli = false

    override fun isEnabled(state: RepoState, e: AnActionEvent) = true

    override fun perform(project: Project, e: AnActionEvent) = StackStateService.getInstance(project).recheckCli()
}

class OpenSettingsAction : DumbAwareAction(AllIcons.General.Settings) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(GhStackCommands::showSettings)
    }
}

class ShowToolWindowAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { GhStackToolWindow.show(it) }
    }
}

/** Shown in the tool window toolbar only when the project has several git repositories. */
class RepoSelectorAction : ComboBoxAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val service = e.project?.let { StackStateService.getInstance(it) }
        val roots = service?.roots().orEmpty()
        e.presentation.isEnabledAndVisible = roots.size > 1
        e.presentation.text = service?.activeRoot()?.fileName?.toString()
    }

    override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
        val project = CommonDataKeys.PROJECT.getData(context) ?: return DefaultActionGroup()
        val service = StackStateService.getInstance(project)
        return DefaultActionGroup(
            service.roots().map { root ->
                object : DumbAwareAction(root.fileName.toString()) {
                    override fun actionPerformed(e: AnActionEvent) = service.selectRoot(root)
                }
            },
        )
    }
}
