package com.github.sanex3339.ghstack.ui

import com.github.sanex3339.ghstack.cli.CommandRequest
import com.github.sanex3339.ghstack.ide.GhStackNotifier
import com.github.sanex3339.ghstack.ide.IdeEnvironment
import com.github.sanex3339.ghstack.ide.StackStateService
import com.github.sanex3339.ghstack.model.StackUi
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
import git4idea.changes.GitChangeUtils
import java.nio.file.Path

/** Shows a branch's own changes: the same three-dot diff its pull request shows. */
object LayerDiff {
    fun show(project: Project, root: Path, stack: StackUi, branch: String) {
        val parent = stack.activeParentOf(branch)
        val repository = StackStateService.getInstance(project).repository(root) ?: return
        val gitPath = IdeEnvironment.gitPath() ?: return
        object : Task.Backgroundable(project, "Loading changes of $branch", true) {
            private var changes: Collection<Change> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                val mergeBase = IdeEnvironment.runner().run(CommandRequest(root, listOf(gitPath, "merge-base", parent, branch)))
                    .takeIf { it.ok }?.stdout?.trim()
                    ?: throw VcsException("Couldn't find where $branch branched off $parent")
                changes = GitChangeUtils.getDiff(repository, mergeBase, branch, true) ?: emptyList()
            }

            override fun onSuccess() {
                if (changes.isEmpty()) {
                    GhStackNotifier.info(project, "No changes in $branch", "It has no commits on top of $parent.")
                } else {
                    ShowDiffAction.showDiffForChange(project, changes)
                }
            }

            override fun onThrowable(error: Throwable) {
                GhStackNotifier.error(project, "Couldn't load the changes of $branch", error.message.orEmpty())
            }
        }.queue()
    }
}
