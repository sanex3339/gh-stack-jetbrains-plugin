package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.testutil.GitSandbox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Real git + gh stack against a local origin whose hook mimics GitHub's "max 5 refs per push" ruleset. */
class PushBatchingIT {
    private lateinit var sandbox: GitSandbox
    private val branches = (1..6).map { "layer-$it" }

    @BeforeEach
    fun setUp() {
        GitSandbox.assumeGhStack()
        sandbox = GitSandbox.create()
        sandbox.stack("init", "--base", "main", branches.first())
        branches.forEachIndexed { index, branch ->
            if (index > 0) sandbox.stack("add", branch)
            sandbox.commitFile("$branch.txt", branch, "Add $branch")
        }
        sandbox.installOriginHook(
            """
            count=0
            while read old new ref; do count=$((count + 1)); done
            if [ "${'$'}count" -gt 5 ]; then
              echo "error: GH013: Repository rule violations found for refs/heads/layer-1." >&2
              echo "- Pushes can not update more than 5 branches or tags." >&2
              exit 1
            fi
            """,
        )
    }

    @AfterEach
    fun tearDown() {
        if (::sandbox.isInitialized) sandbox.close()
    }

    private fun remoteTips(): Map<String, String> =
        sandbox.git("ls-remote", "--heads", "origin").lines().filter { it.isNotBlank() }.associate { line ->
            val (sha, ref) = line.split('\t')
            ref.removePrefix("refs/heads/") to sha
        }

    @Test
    fun `pushes a stack larger than the ruleset allows`() {
        assertEquals(PushOutcome.Pushed(batchSize = 5), StackPusher(sandbox.cli()).push())
        val remote = remoteTips()
        branches.forEach { assertEquals(sandbox.git("rev-parse", it), remote[it], it) }
    }

    @Test
    fun `pushes rewritten branches in batches after a rebase`() {
        StackPusher(sandbox.cli()).push()
        // Rewrite every layer: amend the bottom one and cascade the rebase through the stack.
        sandbox.git("checkout", branches.first())
        sandbox.commitFile("${branches.first()}.txt", "changed", "Change the bottom layer")
        sandbox.stack("rebase", "--upstack")

        assertEquals(PushOutcome.Pushed(batchSize = 5), StackPusher(sandbox.cli()).push())
        val remote = remoteTips()
        branches.forEach { assertEquals(sandbox.git("rev-parse", it), remote[it], it) }
    }
}
