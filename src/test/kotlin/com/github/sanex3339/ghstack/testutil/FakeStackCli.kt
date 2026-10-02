package com.github.sanex3339.ghstack.testutil

import com.github.sanex3339.ghstack.cli.CliResult
import com.github.sanex3339.ghstack.cli.StackCli
import java.nio.file.Path

/** Scripted [StackCli]. Commands are recorded as `"stack submit --auto"`, `"gh pr create …"`, `"git log …"`. */
class FakeStackCli(override val workDir: Path = Path.of("/repo")) : StackCli {
    val calls = mutableListOf<String>()
    private val rules = mutableListOf<Pair<String, CliResult>>()
    private val actions = mutableListOf<Pair<String, () -> Unit>>()

    /** Later rules win; a command matches when it starts with [prefix]. Unmatched commands succeed silently. */
    fun on(prefix: String, exitCode: Int = 0, stdout: String = "", stderr: String = "") {
        rules += prefix to CliResult(exitCode, stdout, stderr)
    }

    fun onRun(prefix: String, action: () -> Unit) {
        actions += prefix to action
    }

    private fun respond(command: String): CliResult {
        calls += command
        actions.filter { command.startsWith(it.first) }.forEach { it.second() }
        return rules.lastOrNull { command.startsWith(it.first) }?.second ?: CliResult(0, "", "")
    }

    override fun stack(vararg args: String) = respond((listOf("stack") + args).joinToString(" "))
    override fun gh(vararg args: String) = respond((listOf("gh") + args).joinToString(" "))
    override fun git(vararg args: String) = respond((listOf("git") + args).joinToString(" "))
}
