package com.github.sanex3339.ghstack.ui.toolwindow

import com.github.sanex3339.ghstack.state.Banner
import com.github.sanex3339.ghstack.ui.GhStackCommands
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotificationPanel
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** The single most urgent message at the bottom of the tool window, with its one-click fixes. */
class BannerPanel(private val project: Project) : JPanel(BorderLayout()) {
    private var shown: Banner? = null

    init {
        isOpaque = false
    }

    fun update(banner: Banner?) {
        if (banner == shown) return
        shown = banner
        removeAll()
        banner?.let { add(render(it), BorderLayout.CENTER) }
        revalidate()
        repaint()
    }

    private fun conflictText(what: String, files: List<String>, paused: Boolean): String = when {
        files.isNotEmpty() -> {
            val shown = files.take(3).joinToString(", ") + if (files.size > 3) " and ${files.size - 3} more" else ""
            "$what: ${files.size} conflicted file${if (files.size == 1) "" else "s"} ($shown)."
        }
        paused -> "$what. The branch was finished or stopped outside the stack rebase: continue with the branches above, or abort."
        else -> "$what. All conflicts are resolved: continue."
    }

    private fun render(banner: Banner): JComponent {
        val isError = banner is Banner.GitMissing || banner is Banner.GhMissing || banner is Banner.StacksUnavailable
        val panel = EditorNotificationPanel(if (isError) EditorNotificationPanel.Status.Error else EditorNotificationPanel.Status.Warning)
        when (banner) {
            Banner.GitMissing -> panel.text = "git wasn't found on PATH."
            Banner.GhMissing -> {
                panel.text = "GitHub CLI (gh) wasn't found."
                panel.createActionLabel("Install gh…") { BrowserUtil.browse("https://cli.github.com") }
                panel.createActionLabel("Settings…") { GhStackCommands.showSettings(project) }
            }
            Banner.ExtensionMissing -> {
                panel.text = "The gh stack extension isn't installed."
                panel.createActionLabel("Install") { GhStackCommands.installExtension(project) }
            }
            Banner.NotAuthenticated -> {
                panel.text = "gh isn't logged in to GitHub."
                panel.createActionLabel("Log In…") { GhStackCommands.login(project) }
            }
            is Banner.RebaseConflict -> {
                panel.text = conflictText("Rebase stopped" + (banner.branch?.let { " on $it" } ?: ""), banner.files, banner.paused)
                if (banner.files.isNotEmpty()) panel.createActionLabel("Resolve Conflicts…") { GhStackCommands.resolveConflicts(project) }
                panel.createActionLabel("Continue") { GhStackCommands.rebaseContinue(project) }
                panel.createActionLabel("Abort") { GhStackCommands.rebaseAbort(project) }
            }
            is Banner.RemovalConflict -> {
                panel.text = conflictText("Removing ${banner.removed} stopped" + (banner.branch?.let { " while rebasing $it" } ?: ""), banner.files, banner.paused)
                if (banner.files.isNotEmpty()) panel.createActionLabel("Resolve Conflicts…") { GhStackCommands.resolveConflicts(project) }
                panel.createActionLabel("Continue") { GhStackCommands.removeContinue(project) }
                panel.createActionLabel("Abort") { GhStackCommands.removeAbort(project) }
            }
            is Banner.ModifyInterrupted -> {
                panel.text = "gh stack modify was interrupted (${banner.phase})."
                panel.createActionLabel("Continue in Terminal") { GhStackCommands.modifyContinue(project) }
                panel.createActionLabel("Abort Modify") { GhStackCommands.modifyAbort(project) }
            }
            Banner.ModifyPendingSubmit -> {
                panel.text = "The stack was restructured locally. Submit to update GitHub."
                panel.createActionLabel("Submit") { GhStackCommands.submit(project) }
            }
            Banner.StacksUnavailable -> panel.text = "Stacked PRs aren't enabled for this repository."
            is Banner.Unsubmitted -> {
                panel.text = if (banner.count == 1) "1 branch isn't on GitHub yet." else "${banner.count} branches aren't on GitHub yet."
                panel.createActionLabel("Submit") { GhStackCommands.submit(project) }
            }
            is Banner.DraftsBlocking -> {
                val count = banner.prNumbers.size
                panel.text = (if (count == 1) "1 pull request is a draft" else "$count pull requests are drafts") +
                    ", so the stack can't be merged."
                panel.createActionLabel("Mark Ready for Review") { GhStackCommands.markReady(project, banner.prNumbers) }
            }
            Banner.FallbackMode -> panel.text = "Unsupported gh-stack data format: showing only the current stack."
        }
        return panel
    }
}
