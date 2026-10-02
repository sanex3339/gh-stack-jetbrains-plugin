package com.github.sanex3339.ghstack.ui

import com.github.sanex3339.ghstack.cli.StackCli
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

object ConflictResolver {
    fun unmergedFiles(cli: StackCli): List<Path> =
        cli.git("diff", "--name-only", "--diff-filter=U").stdout.lines()
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .map { cli.workDir.resolve(it) }

    /** Opens the IDE merge tool; the Git merge provider stages each file as it's resolved. EDT only. */
    fun showMergeDialog(project: Project, files: List<Path>) {
        val virtualFiles = files.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
        if (virtualFiles.isNotEmpty()) AbstractVcsHelper.getInstance(project).showMergeDialog(virtualFiles)
    }
}
