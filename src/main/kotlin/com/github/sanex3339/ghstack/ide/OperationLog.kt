package com.github.sanex3339.ghstack.ide

import com.github.sanex3339.ghstack.cli.OutputSink
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

enum class LineKind { COMMAND, OUTPUT, INFO, SUCCESS, WARNING, ERROR }

data class LogLine(val text: String, val kind: LineKind)

enum class EntryStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED }

/** One operation (sync, push, rebase…) and everything it printed. */
class LogEntry(val id: Int, val title: String, val progressText: String, val root: Path) {
    val startedAt: Long = System.currentTimeMillis()
    private val lines = ArrayList<LogLine>()

    @Volatile
    var status: EntryStatus = EntryStatus.RUNNING
        internal set

    @Volatile
    var finishedAt: Long? = null
        internal set

    fun lines(): List<LogLine> = synchronized(lines) { lines.toList() }

    internal fun add(line: LogLine) = synchronized(lines) {
        lines += line
        if (lines.size > MAX_LINES) lines.removeAt(0)
    }

    private companion object {
        const val MAX_LINES = 5_000
    }
}

interface OperationLogListener {
    fun entryStarted(entry: LogEntry) {}
    fun lineAdded(entry: LogEntry, line: LogLine) {}
    fun entryFinished(entry: LogEntry) {}

    /** Someone asked to show the log, e.g. "Show log" on an error notification. */
    fun revealRequested() {}
}

/** History of operations shown in the activity log under the stack tree. Thread-safe; listeners may be called off the EDT. */
@Service(Service.Level.PROJECT)
class OperationLog(private val project: Project) {
    private val entries = CopyOnWriteArrayList<LogEntry>()
    private val ids = AtomicInteger()

    fun entries(): List<LogEntry> = entries.toList()

    fun start(title: String, progressText: String, root: Path): LogEntry {
        val entry = LogEntry(ids.incrementAndGet(), title, progressText, root)
        entries += entry
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        publisher().entryStarted(entry)
        return entry
    }

    fun add(entry: LogEntry, text: String, kind: LineKind) {
        text.lines().forEach { lineText ->
            val line = LogLine(lineText, kind)
            entry.add(line)
            publisher().lineAdded(entry, line)
        }
    }

    fun finish(entry: LogEntry, status: EntryStatus) {
        entry.status = status
        entry.finishedAt = System.currentTimeMillis()
        publisher().entryFinished(entry)
    }

    /** Streams a command and its output into [entry]. */
    fun sink(entry: LogEntry): OutputSink = object : OutputSink {
        override fun command(text: String) = add(entry, "$ $text", LineKind.COMMAND)
        override fun stdout(line: String) = add(entry, line, LineKind.OUTPUT)
        override fun stderr(line: String) = add(entry, line, LineKind.OUTPUT) // gh stack reports progress on stderr
    }

    fun reveal() = publisher().revealRequested()

    private fun publisher(): OperationLogListener = project.messageBus.syncPublisher(TOPIC)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<OperationLogListener> = Topic(OperationLogListener::class.java, Topic.BroadcastDirection.NONE)
        private const val MAX_ENTRIES = 30

        fun getInstance(project: Project): OperationLog = project.service()
    }
}
