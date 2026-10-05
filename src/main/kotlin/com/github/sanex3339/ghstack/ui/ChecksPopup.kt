package com.github.sanex3339.ghstack.ui

import com.github.sanex3339.ghstack.model.CheckOutcome
import com.github.sanex3339.ghstack.model.PrDetails
import com.github.sanex3339.ghstack.state.ChecksSummary
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.Icon

/**
 * A pull request's checks box, opened from its checks label: failing, running and cancelled checks, each opening its
 * job on GitHub; failing ones get "Re-run failed" once their workflow run is done, as on GitHub.
 */
object ChecksPopup {
    fun show(project: Project, root: Path, details: PrDetails, checksUrl: String?, point: RelativePoint) {
        val summary = ChecksSummary.of(details.checkRuns)
        lateinit var popup: JBPopup
        val content = panel {
            summary.listed.forEach { check ->
                row {
                    icon(iconFor(check.outcome)).gap(RightGap.SMALL)
                    val text = ChecksSummary.label(check)
                    val url = check.url
                    val cell = if (url != null) link(shorten(text)) { BrowserUtil.browse(url); popup.cancel() } else label(shorten(text))
                    cell.applyToComponent { toolTipText = text }
                    if (ChecksSummary.canRerun(check)) {
                        link("Re-run failed") { GhStackCommands.rerunFailedJobs(project, root, check); popup.cancel() }
                            .applyToComponent { toolTipText = "Re-run the failed jobs of this ${check.workflow ?: "workflow"} run" }
                    }
                }
            }
            if (summary.listed.isNotEmpty()) separator()
            row {
                icon(AllIcons.Ide.External_link_arrow).gap(RightGap.SMALL)
                link("Open all checks on GitHub") { checksUrl?.let(BrowserUtil::browse); popup.cancel() }.enabled(checksUrl != null)
                if (summary.others.isNotEmpty()) comment(summary.others)
            }
        }.apply { border = JBUI.Borders.empty(6, 10) }
        val scroll = JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            val wanted = content.preferredSize
            preferredSize = Dimension(minOf(wanted.width + JBUI.scale(16), JBUI.scale(760)), minOf(wanted.height + JBUI.scale(4), JBUI.scale(420)))
        }
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(scroll, null)
            .setTitle(summary.title)
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .createPopup()
        popup.show(point)
    }

    private fun iconFor(outcome: CheckOutcome): Icon = when (outcome) {
        CheckOutcome.FAILING -> AllIcons.RunConfigurations.TestFailed
        CheckOutcome.RUNNING -> AnimatedIcon.Default.INSTANCE
        CheckOutcome.CANCELLED -> AllIcons.RunConfigurations.TestIgnored
        CheckOutcome.PASSED -> AllIcons.RunConfigurations.TestPassed
        CheckOutcome.SKIPPED -> AllIcons.RunConfigurations.TestSkipped
    }

    private fun shorten(text: String) = if (text.length <= MAX_LABEL) text else text.take(MAX_LABEL - 1) + "…"

    private const val MAX_LABEL = 100
}
