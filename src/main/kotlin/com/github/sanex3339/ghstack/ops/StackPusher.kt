package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.cli.CliResult
import com.github.sanex3339.ghstack.cli.StackCli
import com.github.sanex3339.ghstack.model.ViewJsonParser

/** GitHub's "max refs per push" repository rule, read from a rejected push. */
object PushLimit {
    private val RULE = Regex("""Pushes (?:can not|cannot|can't) update more than (\d+) branches""", RegexOption.IGNORE_CASE)

    fun parse(output: String): Int? = RULE.find(output)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
}

sealed interface PushOutcome {
    /** [batchSize] is set when a repository rule forced pushing in batches of that size. */
    data class Pushed(val batchSize: Int?) : PushOutcome

    data class Failed(val result: CliResult) : PushOutcome
}

/**
 * `gh stack push` sends every branch in one `git push`. Repositories with a "Pushes can not update more
 * than N branches" ruleset reject that, so this falls back to pushing in batches of N with the same
 * per-branch `--force-with-lease` the CLI uses.
 */
class StackPusher(private val cli: StackCli) {
    fun push(): PushOutcome {
        val first = cli.stack("push")
        if (first.ok) return PushOutcome.Pushed(batchSize = null)
        val limit = PushLimit.parse(first.combinedOutput) ?: return PushOutcome.Failed(first)
        pushInBatches(limit)
        // Every branch is up to date now, so this push updates nothing; it lets gh stack record the new bases.
        val bookkeeping = cli.stack("push")
        return if (bookkeeping.ok) PushOutcome.Pushed(limit) else PushOutcome.Failed(bookkeeping)
    }

    fun pushOrAbort() {
        val outcome = push()
        if (outcome is PushOutcome.Failed) throw WorkflowAbort("Pushing the stack failed: ${outcome.result.summary()}")
    }

    /** Pushes the stack's unmerged, unqueued branches, at most [batchSize] per `git push`. */
    fun pushInBatches(batchSize: Int) {
        val branches = pushableBranches()
        if (branches.isEmpty()) return
        val remote = pushRemote()
        // Refresh tracking refs so the leases below match the remote (fails harmlessly for unpushed branches).
        branches.forEach { cli.git("fetch", "--quiet", remote, "refs/heads/$it:refs/remotes/$remote/$it") }
        branches.chunked(batchSize).forEach { batch ->
            val args = mutableListOf("push", remote)
            batch.forEach { branch ->
                // An empty expected value means "must not exist on the remote yet".
                val expected = cli.git("rev-parse", "--verify", "--quiet", "refs/remotes/$remote/$branch").takeIf { it.ok }?.stdout?.trim().orEmpty()
                args += "--force-with-lease=refs/heads/$branch:$expected"
            }
            batch.forEach { args += "refs/heads/$it:refs/heads/$it" }
            cli.git(*args.toTypedArray()).orAbort("Pushing ${batch.joinToString(", ")}")
        }
    }

    /** Read fresh: a sync may have just merged or queued branches, and those must not be pushed. */
    private fun pushableBranches(): List<String> {
        val view = ViewJsonParser.parse(cli.stack("view", "--json").stdout)
            ?: throw WorkflowAbort("Couldn't read the stack to push it in batches")
        return view.branches.filter { !it.isMerged && !it.isQueued }.map { it.name }
    }

    private fun pushRemote(): String {
        cli.git("config", "--get", "remote.pushDefault").stdout.trim().takeIf { it.isNotEmpty() }?.let { return it }
        return cli.git("remote").stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }.singleOrNull() ?: "origin"
    }
}
