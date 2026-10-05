package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.model.RepoCoordinates
import com.github.sanex3339.ghstack.testutil.FakeStackCli
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteStackDiscoveryTest {
    private val repo = RepoCoordinates("github.com", "octo", "app")

    @Test
    fun `finds the stack of the branch's open pull request`() {
        val cli = FakeStackCli()
        cli.on("gh api --hostname github.com repos/octo/app/pulls?head=octo%3Afeat%2Fapi&state=open", stdout = """[{"number":7}]""")
        cli.on("gh api --hostname github.com repos/octo/app/stacks?pull_request=7", stdout = """[{"number":12,"pull_requests":[{"number":6,"state":"open"},{"number":7,"state":"open"}]}]""")
        val stack = RemoteStackDiscovery(cli).find("feat/api", repo)
        assertEquals(12, stack?.number)
        assertEquals(listOf(6, 7), stack?.prNumbers)
    }

    @Test
    fun `a branch without an open pull request isn't looked up further`() {
        val cli = FakeStackCli()
        cli.on("gh api --hostname github.com repos/octo/app/pulls", stdout = "[]")
        assertNull(RemoteStackDiscovery(cli).find("spike", repo))
        assertTrue(cli.calls.none { "stacks" in it }, cli.calls.toString())
    }

    @Test
    fun `a pull request outside any stack gives nothing`() {
        val cli = FakeStackCli()
        cli.on("gh api --hostname github.com repos/octo/app/pulls", stdout = """[{"number":7}]""")
        cli.on("gh api --hostname github.com repos/octo/app/stacks", stdout = "[]")
        assertNull(RemoteStackDiscovery(cli).find("solo", repo))
    }

    @Test
    fun `without a stack file the repository comes from the branch's remote`() {
        val cli = FakeStackCli()
        cli.on("git remote", stdout = "origin\nupstream\n")
        cli.on("git config --get branch.feat.remote", stdout = "upstream\n")
        cli.on("git remote get-url upstream", stdout = "git@github.com:acme/web.git\n")
        cli.on("gh api --hostname github.com repos/acme/web/pulls", stdout = """[{"number":3}]""")
        cli.on("gh api --hostname github.com repos/acme/web/stacks?pull_request=3", stdout = """[{"number":4,"pull_requests":[{"number":3}]}]""")
        assertEquals(4, RemoteStackDiscovery(cli).find("feat", knownRepo = null)?.number)
    }

    @Test
    fun `GitHub errors give nothing`() {
        val cli = FakeStackCli()
        cli.on("gh api", exitCode = 1, stderr = "HTTP 401")
        assertNull(RemoteStackDiscovery(cli).find("feat", repo))
    }
}
