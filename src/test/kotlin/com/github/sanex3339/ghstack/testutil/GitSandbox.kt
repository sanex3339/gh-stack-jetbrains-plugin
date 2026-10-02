package com.github.sanex3339.ghstack.testutil

import com.github.sanex3339.ghstack.cli.CommandRequest
import com.github.sanex3339.ghstack.cli.CommandRunner
import com.github.sanex3339.ghstack.cli.ExecutableResolver
import com.github.sanex3339.ghstack.cli.ProcessStackCli
import com.github.sanex3339.ghstack.cli.StackCli
import com.github.sanex3339.ghstack.model.StackFile
import com.github.sanex3339.ghstack.model.StackFileParser
import com.github.sanex3339.ghstack.model.StackFileResult
import com.github.sanex3339.ghstack.ops.StackFileStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/** A throwaway repo with a local bare `origin`, driven by the real `git` and `gh stack` (no network). */
class GitSandbox private constructor(val root: Path) {
    val work: Path = root.resolve("work")
    val gitDir: Path get() = work.resolve(".git")
    private val runner = CommandRunner()

    fun git(vararg args: String): String = run(work, listOf(GIT) + args)

    fun stack(vararg args: String): String = run(work, listOf(requireNotNull(GH), "stack") + args)

    fun commitFile(name: String, content: String, message: String) {
        work.resolve(name).writeText(content)
        git("add", name)
        git("commit", "-q", "-m", message)
    }

    fun currentBranch(): String = git("branch", "--show-current")

    fun branches(): Set<String> =
        git("for-each-ref", "--format=%(refname:short)", "refs/heads").lines().filter { it.isNotBlank() }.toSet()

    fun stackFile(): StackFile = (StackFileParser.parse(StackFileStore.read(gitDir)) as StackFileResult.Parsed).file

    fun stackOrders(): List<List<String>> = stackFile().stacks.map { s -> s.branches.map { it.name } }

    fun cli(): StackCli = ProcessStackCli(runner, requireNotNull(GH), GIT, work)

    fun close() {
        root.toFile().deleteRecursively()
    }

    private fun run(dir: Path, command: List<String>): String {
        val result = runner.run(CommandRequest(dir, command))
        check(result.ok) { "${command.joinToString(" ")} failed (${result.exitCode}): ${result.combinedOutput}" }
        return result.stdout.trim()
    }

    companion object {
        private val PATH: String? = System.getenv("PATH")
        val GIT: String = requireNotNull(ExecutableResolver.find("git", PATH)) { "git not found" }
        val GH: String? = ExecutableResolver.find("gh", PATH)

        fun ghStackAvailable(): Boolean =
            GH != null && CommandRunner().run(CommandRequest(Path.of(System.getProperty("java.io.tmpdir")), listOf(GH, "stack", "--version"))).ok

        fun create(): GitSandbox {
            val sandbox = GitSandbox(Files.createTempDirectory("ghstack-it"))
            sandbox.run(sandbox.root, listOf(GIT, "init", "-q", "--bare", "origin.git"))
            sandbox.run(sandbox.root, listOf(GIT, "init", "-q", "-b", "main", "work"))
            sandbox.git("config", "user.email", "test@example.com")
            sandbox.git("config", "user.name", "Test")
            sandbox.git("config", "commit.gpgsign", "false")
            sandbox.git("config", "rerere.enabled", "true")
            sandbox.git("remote", "add", "origin", "../origin.git")
            sandbox.commitFile("a.txt", "a", "init")
            sandbox.git("push", "-q", "origin", "main")
            return sandbox
        }
    }
}
