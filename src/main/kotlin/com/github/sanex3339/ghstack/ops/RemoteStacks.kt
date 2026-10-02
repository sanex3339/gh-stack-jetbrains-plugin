package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.cli.StackCli
import com.github.sanex3339.ghstack.model.RemoteStackInfo
import com.github.sanex3339.ghstack.model.RemoteStackJson
import com.github.sanex3339.ghstack.model.RepoCoordinates

/** GitHub's Stacks REST API through `gh api` (only what the CLI can't do: read a stack, unstack without touching local tracking). */
class RemoteStacks(private val cli: StackCli, private val repo: RepoCoordinates) {
    fun get(number: Int): RemoteStackInfo? =
        cli.gh("api", "--hostname", repo.host, "${repo.apiPath}/stacks/$number")
            .takeIf { it.ok }?.let { RemoteStackJson.parseOne(it.stdout) }

    fun findForPr(pr: Int): RemoteStackInfo? =
        cli.gh("api", "--hostname", repo.host, "${repo.apiPath}/stacks?pull_request=$pr")
            .takeIf { it.ok }?.let { RemoteStackJson.parseList(it.stdout).firstOrNull() }

    fun find(stackNumber: Int?, prs: List<Int>): RemoteStackInfo? =
        stackNumber?.let { get(it) } ?: prs.take(MAX_PR_LOOKUPS).firstNotNullOfOrNull { findForPr(it) }

    fun unstack(number: Int) {
        val result = cli.gh("api", "--hostname", repo.host, "-X", "POST", "${repo.apiPath}/stacks/$number/unstack")
        if (!result.ok && "404" !in result.combinedOutput) {
            throw WorkflowAbort("Unstacking stack #$number on GitHub failed: ${result.summary()}")
        }
    }

    private companion object {
        const val MAX_PR_LOOKUPS = 3
    }
}
