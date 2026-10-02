package com.github.sanex3339.ghstack.planning

import com.github.sanex3339.ghstack.cli.CliResult

object SyncOutput {
    private const val ABORTED_MARKER = "Sync aborted"

    /** Non-interactive `gh stack sync` exits 0 and prints "Sync aborted" when local and remote stacks diverged. */
    fun isAborted(result: CliResult): Boolean = result.ok && ABORTED_MARKER in result.combinedOutput

    /** Sync only warns when its push fails and still exits 0. */
    fun pushFailed(result: CliResult): Boolean = result.ok && "Push failed" in result.combinedOutput
}
