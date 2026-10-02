package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.testutil.FakeStackCli
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StackPusherTest {
    /** Trimmed from a real rejection on github.com. */
    private val rulesetRejection = """
        Pushing 6 branches to origin...
        ✗ failed to push: failed to run git: remote: error: GH013: Repository rule violations found for refs/heads/emb-2407-be-side-reconciler.
        remote: Review all repository rules at https://github.com/metabase/metabase/rules?ref=refs%2Fheads%2Femb-2407-be-side-reconciler
        remote:
        remote: - Pushes can not update more than 5 branches or tags.
        remote:
        To github.com:metabase/metabase.git
         ! [remote rejected]         emb-2407-be-side-reconciler -> emb-2407-be-side-reconciler (push declined due to repository rule violations)
        error: failed to push some refs to 'github.com:metabase/metabase.git'
    """.trimIndent()

    private val sixBranches = """{"trunk":"main","currentBranch":"b6","branches":[""" +
        (1..6).joinToString(",") { """{"name":"b$it","isMerged":false,"isQueued":false,"needsRebase":false}""" } +
        """,{"name":"old","isMerged":true,"isQueued":false,"needsRebase":false}]}"""

    @Test
    fun `parses the ruleset limit`() {
        assertEquals(5, PushLimit.parse(rulesetRejection))
        assertEquals(3, PushLimit.parse("remote: - Pushes cannot update more than 3 branches or tags."))
        assertNull(PushLimit.parse("! [rejected] api -> api (stale info)"))
    }

    @Test
    fun `a normal push is left to gh stack`() {
        val cli = FakeStackCli()
        assertEquals(PushOutcome.Pushed(batchSize = null), StackPusher(cli).push())
        assertEquals(listOf("stack push"), cli.calls)
    }

    @Test
    fun `other push failures are reported as they are`() {
        val cli = FakeStackCli()
        cli.on("stack push", exitCode = 1, stderr = "✗ failed to push: ! [rejected] b1 -> b1 (stale info)")
        val outcome = StackPusher(cli).push()
        assertInstanceOf(PushOutcome.Failed::class.java, outcome)
        assertEquals(listOf("stack push"), cli.calls)
    }

    @Test
    fun `a ruleset rejection is retried in batches with per-branch leases`() {
        val cli = FakeStackCli()
        cli.once("stack push", exitCode = 1, stderr = rulesetRejection)
        cli.on("stack view --json", stdout = sixBranches)
        cli.on("git remote", stdout = "origin\n")
        cli.on("git rev-parse --verify --quiet refs/remotes/origin/b1", stdout = "aaa\n")
        cli.on("git rev-parse --verify --quiet refs/remotes/origin/b6", exitCode = 1)

        assertEquals(PushOutcome.Pushed(batchSize = 5), StackPusher(cli).push())

        val pushes = cli.calls.filter { it.startsWith("git push") }
        assertEquals(2, pushes.size, cli.calls.toString())
        assertEquals(
            "git push origin --force-with-lease=refs/heads/b1:aaa --force-with-lease=refs/heads/b2: --force-with-lease=refs/heads/b3: " +
                "--force-with-lease=refs/heads/b4: --force-with-lease=refs/heads/b5: " +
                "refs/heads/b1:refs/heads/b1 refs/heads/b2:refs/heads/b2 refs/heads/b3:refs/heads/b3 refs/heads/b4:refs/heads/b4 refs/heads/b5:refs/heads/b5",
            pushes[0],
        )
        assertEquals("git push origin --force-with-lease=refs/heads/b6: refs/heads/b6:refs/heads/b6", pushes[1])
        assertEquals("stack push", cli.calls.last(), "gh stack push runs again to record the new bases")
    }

    @Test
    fun `a failing batch aborts with the git error`() {
        val cli = FakeStackCli()
        cli.once("stack push", exitCode = 1, stderr = rulesetRejection)
        cli.on("stack view --json", stdout = sixBranches)
        cli.on("git push", exitCode = 1, stderr = "! [rejected] b1 -> b1 (stale info)")
        assertThrows<WorkflowAbort> { StackPusher(cli).push() }
    }
}
