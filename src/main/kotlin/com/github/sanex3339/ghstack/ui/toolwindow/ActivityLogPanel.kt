package com.github.sanex3339.ghstack.ui.toolwindow

import com.github.sanex3339.ghstack.ide.EntryStatus
import com.github.sanex3339.ghstack.ide.GhStackOperations
import com.github.sanex3339.ghstack.ide.LineKind
import com.github.sanex3339.ghstack.ide.LogEntry
import com.github.sanex3339.ghstack.ide.LogLine
import com.github.sanex3339.ghstack.ide.OperationLog
import com.github.sanex3339.ghstack.ide.OperationLogListener
import com.github.sanex3339.ghstack.ui.GhStackCommands
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.SideBorder
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.DefaultComboBoxModel
import javax.swing.JPanel

/**
 * The activity log under the stack tree: a header with the latest operation's state (spinner, ✓ or ✗),
 * Cancel / Undo / history, and the selected operation's live output.
 */
class ActivityLogPanel(
    private val project: Project,
    parent: Disposable,
    private val onCollapsedChanged: (Boolean) -> Unit,
) : JPanel(BorderLayout()) {
    private val log = OperationLog.getInstance(project)
    private val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console.also { Disposer.register(parent, it) }
    private val statusLabel = JBLabel()
    private val cancelLink = ActionLink("Cancel") { shown?.let { GhStackOperations.getInstance(project).cancel(it.root) } }
    private val undoLink = ActionLink("Undo") { GhStackCommands.undo(project) }
    private val historyModel = DefaultComboBoxModel<LogEntry>()
    private val history = ComboBox(historyModel).apply {
        renderer = textListCellRenderer { entry: LogEntry? -> entry?.let(::historyText) }
        addActionListener { (selectedItem as? LogEntry)?.takeIf { it != shown }?.let { show(it, follow = false) } }
    }
    private val toggleLink = ActionLink("Hide") { collapsed = !collapsed }
    private val body = JPanel(BorderLayout()).apply { add(console.component, BorderLayout.CENTER) }
    private var shown: LogEntry? = null

    var collapsed: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            body.isVisible = !value
            toggleLink.text = if (value) "Show log" else "Hide"
            onCollapsedChanged(value)
        }

    init {
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(cancelLink)
            add(undoLink)
            add(history)
            add(toggleLink)
        }
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(SideBorder(JBUI.CurrentTheme.ToolWindow.borderColor(), SideBorder.TOP), JBUI.Borders.empty(3, 8))
            add(statusLabel, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        body.isVisible = false
        toggleLink.text = "Show log"
        statusLabel.text = "No operations yet"
        statusLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()

        project.messageBus.connect(parent).subscribe(
            OperationLog.TOPIC,
            object : OperationLogListener {
                override fun entryStarted(entry: LogEntry) = onEdt {
                    refreshHistory()
                    show(entry, follow = true)
                    collapsed = false
                }

                override fun lineAdded(entry: LogEntry, line: LogLine) = onEdt { if (entry == shown) print(line) }

                override fun entryFinished(entry: LogEntry) = onEdt {
                    refreshHistory()
                    updateHeader()
                    if (entry.status == EntryStatus.FAILED) collapsed = false
                }

                override fun revealRequested() = onEdt {
                    log.entries().lastOrNull()?.let { show(it, follow = true) }
                    collapsed = false
                }
            },
        )
        log.entries().lastOrNull()?.let { show(it, follow = true) }
        refreshHistory()
    }

    private fun show(entry: LogEntry, follow: Boolean) {
        shown = entry
        console.clear()
        entry.lines().forEach(::print)
        if (follow) history.selectedItem = entry
        updateHeader()
    }

    private fun print(line: LogLine) {
        val type = when (line.kind) {
            LineKind.COMMAND -> ConsoleViewContentType.SYSTEM_OUTPUT
            LineKind.OUTPUT -> ConsoleViewContentType.NORMAL_OUTPUT
            LineKind.INFO -> ConsoleViewContentType.LOG_INFO_OUTPUT
            LineKind.SUCCESS -> ConsoleViewContentType.LOG_VERBOSE_OUTPUT
            LineKind.WARNING -> ConsoleViewContentType.LOG_WARNING_OUTPUT
            LineKind.ERROR -> ConsoleViewContentType.ERROR_OUTPUT
        }
        val prefix = when (line.kind) {
            LineKind.SUCCESS -> "✓ "
            LineKind.WARNING -> "⚠ "
            LineKind.ERROR -> "✗ "
            else -> ""
        }
        console.print(prefix + line.text + "\n", type)
    }

    private fun refreshHistory() {
        val entries = log.entries().asReversed()
        val selected = shown
        historyModel.removeAllElements()
        entries.forEach(historyModel::addElement)
        if (selected != null && selected in entries) history.selectedItem = selected
        history.isVisible = entries.size > 1
        updateHeader()
    }

    private fun updateHeader() {
        val entry = shown
        val ops = GhStackOperations.getInstance(project)
        cancelLink.isVisible = entry?.status == EntryStatus.RUNNING
        val undoTitle = entry?.root?.let(ops::undoTitle)
        undoLink.isVisible = undoTitle != null && !ops.isBusy(entry.root)
        undoLink.text = undoTitle?.let { "Undo $it" } ?: "Undo"
        if (entry == null) return
        statusLabel.foreground = JBUI.CurrentTheme.Label.foreground()
        when (entry.status) {
            EntryStatus.RUNNING -> {
                statusLabel.icon = AnimatedIcon.Default.INSTANCE
                statusLabel.text = entry.progressText
            }
            EntryStatus.SUCCEEDED -> {
                statusLabel.icon = AllIcons.General.InspectionsOK
                statusLabel.text = "${entry.title}: done in ${duration(entry)}"
            }
            EntryStatus.FAILED -> {
                statusLabel.icon = AllIcons.General.Error
                statusLabel.text = "${entry.title}: failed"
            }
            EntryStatus.CANCELLED -> {
                statusLabel.icon = AllIcons.General.Warning
                statusLabel.text = "${entry.title}: cancelled"
            }
        }
    }

    private fun historyText(entry: LogEntry): String {
        val mark = when (entry.status) {
            EntryStatus.RUNNING -> "⟳"
            EntryStatus.SUCCEEDED -> "✓"
            EntryStatus.FAILED -> "✗"
            EntryStatus.CANCELLED -> "–"
        }
        return "$mark ${entry.title} · ${TIME.format(Instant.ofEpochMilli(entry.startedAt))}"
    }

    private fun duration(entry: LogEntry): String {
        val millis = (entry.finishedAt ?: System.currentTimeMillis()) - entry.startedAt
        return if (millis < 1_000) "${millis}ms" else "${millis / 1_000}s"
    }

    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block, project.disposed)

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}
