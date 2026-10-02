package com.github.sanex3339.ghstack.ui

import com.github.sanex3339.ghstack.cli.StackCli
import com.github.sanex3339.ghstack.ide.StackStateService
import com.github.sanex3339.ghstack.model.OperationState
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

object ConflictResolver {
    fun unmergedFiles(cli: StackCli): List<Path> =
        cli.git("diff", "--name-only", "--diff-filter=U").stdout.lines()
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .map { cli.workDir.resolve(it) }

    /**
     * Opens the IDE's 3-way merge tool (the Git merge provider stages each file once resolved). When every
     * file got resolved, the stack rebase (or branch removal) continues right away. EDT only.
     */
    fun resolve(project: Project, files: List<Path>) {
        val virtualFiles = files.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
        if (virtualFiles.isEmpty()) return
        val result = AbstractVcsHelper.getInstance(project).showMergeDialogWithResult(virtualFiles)
        if (!result.shouldFinishMerge()) return
        when (StackStateService.getInstance(project).activeState()?.operation) {
            is OperationState.RebaseConflict -> GhStackCommands.rebaseContinue(project)
            is OperationState.RemovalStopped -> GhStackCommands.removeContinue(project)
            else -> Unit
        }
    }
}
