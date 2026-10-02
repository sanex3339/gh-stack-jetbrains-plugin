package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.cli.CliResult
import com.github.sanex3339.ghstack.cli.StackCli
import com.github.sanex3339.ghstack.model.StackFileParser
import com.github.sanex3339.ghstack.model.StackFileResult
import java.nio.file.Path

/**
 * Re-registers the current stack locally in a new order with `gh stack unstack --local` +
 * `gh stack init`, which adopts existing branches and re-discovers their PRs. On failure the
 * stack file snapshot is restored and a freshly created branch is removed.
 */
class LocalStackReinit(private val cli: StackCli, private val gitDir: Path) {
    /** @param createBranch `name to parent`: a new branch to create at the parent's tip first (insert). */
    fun run(trunk: String, order: List<String>, checkoutAfter: String, createBranch: Pair<String, String>? = null) {
        val snapshot = StackFileStore.read(gitDir)
        val original = currentBranch()
        createBranch?.let { (name, parent) -> cli.git("branch", name, parent).orAbort("Creating branch $name") }

        val unstack = cli.stack("unstack", "--local")
        if (!unstack.ok) rollback(snapshot, createBranch, original, "Removing local stack tracking", unstack)

        val init = cli.stack("init", "--base", trunk, *order.toTypedArray())
        // init saves the stack before checking out the top branch; a failed checkout still leaves a good stack.
        if (!init.ok && !isRegistered(trunk, order)) rollback(snapshot, createBranch, original, "Re-creating the stack", init)

        if (currentBranch() != checkoutAfter) {
            val checkout = cli.git("checkout", checkoutAfter)
            if (!checkout.ok) {
                throw WorkflowAbort("The stack was updated, but switching to $checkoutAfter failed: ${checkout.summary()}")
            }
        }
    }

    private fun currentBranch(): String = cli.git("branch", "--show-current").stdout.trim()

    private fun isRegistered(trunk: String, order: List<String>): Boolean {
        val parsed = StackFileParser.parse(StackFileStore.read(gitDir)) as? StackFileResult.Parsed ?: return false
        return parsed.file.stacks.any { s -> s.trunk == trunk && s.branches.map { it.name } == order }
    }

    private fun rollback(snapshot: ByteArray?, createBranch: Pair<String, String>?, original: String, step: String, result: CliResult): Nothing {
        StackFileStore.restore(gitDir, snapshot)
        if (original.isNotEmpty() && currentBranch() != original) cli.git("checkout", original)
        createBranch?.let { (name, parent) ->
            val tip = revParse(name)
            if (tip != null && tip == revParse(parent)) cli.git("branch", "-D", name)
        }
        throw WorkflowAbort("$step failed, so the stack was restored: ${result.summary()}")
    }

    private fun revParse(branch: String): String? =
        cli.git("rev-parse", "--verify", "--quiet", "refs/heads/$branch").takeIf { it.ok }?.stdout?.trim()
}
