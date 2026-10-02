package com.github.sanex3339.ghstack.ide

import com.github.sanex3339.ghstack.cli.OutputSink
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.JComponent

/** The "Console" tab: every command the plugin runs and its output. Buffers output until the tab exists. */
@Service(Service.Level.PROJECT)
class GhStackConsole(private val project: Project) : OutputSink, Disposable {
    private val lock = Any()
    private var view: ConsoleView? = null
    private val pending = ArrayDeque<Pair<String, ConsoleViewContentType>>()

    /** Called on the EDT when the tool window builds its content. */
    fun component(): JComponent {
        val console = synchronized(lock) {
            view ?: TextConsoleBuilderFactory.getInstance().createBuilder(project).console.also { created ->
                Disposer.register(this, created)
                pending.forEach { (text, type) -> created.print(text, type) }
                pending.clear()
                view = created
            }
        }
        return console.component
    }

    override fun command(text: String) = print("\n[${LocalTime.now().format(TIME)}] $ $text\n", ConsoleViewContentType.SYSTEM_OUTPUT)

    override fun stdout(line: String) = print("$line\n", ConsoleViewContentType.NORMAL_OUTPUT)

    // gh stack reports progress on stderr, so it isn't coloured as an error.
    override fun stderr(line: String) = print("$line\n", ConsoleViewContentType.NORMAL_OUTPUT)

    private fun print(text: String, type: ConsoleViewContentType) {
        synchronized(lock) {
            val console = view
            if (console != null) {
                console.print(text, type)
            } else {
                pending.addLast(text to type)
                while (pending.size > MAX_PENDING) pending.removeFirst()
            }
        }
    }

    override fun dispose() = Unit

    companion object {
        private const val MAX_PENDING = 2_000
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

        fun getInstance(project: Project): GhStackConsole = project.service()
    }
}
