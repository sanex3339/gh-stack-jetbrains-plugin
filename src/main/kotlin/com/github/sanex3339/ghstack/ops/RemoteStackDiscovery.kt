package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.cli.StackCli
import com.github.sanex3339.ghstack.model.RemoteStackInfo
import com.github.sanex3339.ghstack.model.RepoCoordinates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.net.URLEncoder

/**
 * Finds the GitHub stack a branch belongs to through its open pull request, for branches no local stack tracks
 * (a colleague's stack, or yours from another machine). Read-only: two `gh api` calls, or one when there's no PR.
 */
class RemoteStackDiscovery(private val cli: StackCli) {
    /** [knownRepo] comes from the stack file; without one, the repository is read from the branch's remote. */
    fun find(branch: String, knownRepo: RepoCoordinates?): RemoteStackInfo? {
        val repo = knownRepo ?: repository(branch) ?: return null
        val pr = openPullRequest(repo, branch) ?: return null
        return RemoteStacks(cli, repo).findForPr(pr)
    }

    /** The remote the branch tracks, else `remote.pushDefault`, else `origin`, else the only remote. */
    private fun repository(branch: String): RepoCoordinates? {
        val remotes = cli.git("remote").stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val remote = listOf(
            cli.git("config", "--get", "branch.$branch.remote").stdout.trim(),
            cli.git("config", "--get", "remote.pushDefault").stdout.trim(),
            "origin",
        ).firstOrNull { it in remotes } ?: remotes.singleOrNull() ?: return null
        val url = cli.git("remote", "get-url", remote).takeIf { it.ok }?.stdout ?: return null
        return RepoCoordinates.fromRemoteUrl(url)
    }

    private fun openPullRequest(repo: RepoCoordinates, branch: String): Int? {
        val head = URLEncoder.encode("${repo.owner}:$branch", Charsets.UTF_8)
        val result = cli.gh("api", "--hostname", repo.host, "${repo.apiPath}/pulls?head=$head&state=open&per_page=1")
        if (!result.ok) return null
        val pulls = try {
            Json.parseToJsonElement(result.stdout) as? JsonArray
        } catch (e: IllegalArgumentException) {
            null
        }
        return ((pulls?.firstOrNull() as? JsonObject)?.get("number") as? JsonPrimitive)?.intOrNull
    }
}
