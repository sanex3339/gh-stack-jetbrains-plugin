package com.github.sanex3339.ghstack.model

import com.github.sanex3339.ghstack.testutil.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StateMergerTest {
    private val githubFile = (StackFileParser.parse(Fixtures.bytes("stack-file-github.json")) as StackFileResult.Parsed).file
    private val githubView = ViewJsonParser.parse(Fixtures.text("view-github.json"))!!

    @Test
    fun `without overlay statuses come from the file`() {
        val stacks = StateMerger.merge(githubFile, null, "api")
        val current = stacks[0]
        assertTrue(current.isCurrent)
        assertEquals(
            listOf(BranchStatus.MERGED, BranchStatus.OPEN, BranchStatus.OPEN, BranchStatus.OPEN),
            current.branches.map { it.status },
        )
        assertTrue(current.branch("api")!!.isCurrent)
        assertFalse(stacks[1].isCurrent)
        assertEquals(listOf(BranchStatus.NOT_SUBMITTED, BranchStatus.NOT_SUBMITTED), stacks[1].branches.map { it.status })
    }

    @Test
    fun `overlay supplies live statuses for the current stack`() {
        val current = StateMerger.merge(githubFile, githubView, "api")[0]
        assertEquals(
            listOf(BranchStatus.MERGED, BranchStatus.QUEUED, BranchStatus.NEEDS_REBASE, BranchStatus.OPEN),
            current.branches.map { it.status },
        )
        assertEquals(PrUi(44, "https://github.com/octo/app/pull/44", PrState.OPEN), current.branch("api")!!.pr)
        assertEquals(7, current.number)
    }

    @Test
    fun `stale overlay with a different composition is ignored`() {
        val afterInsert = StackFile(
            repository = null,
            stacks = listOf(
                LocalStack(null, null, "main", listOf("auth", "api-models", "api").map { LocalBranch(it, null, false) }),
            ),
        )
        val staleView = ViewJsonParser.parse(Fixtures.text("view-offline.json"))!!
        val stack = StateMerger.merge(afterInsert, staleView, "api-models").single()
        assertEquals(listOf("auth", "api-models", "api"), stack.branches.map { it.name })
        assertTrue(stack.isCurrent)
        assertTrue(stack.branch("api-models")!!.isCurrent)
        assertEquals(BranchStatus.NOT_SUBMITTED, stack.branch("api-models")!!.status)
    }

    @Test
    fun `the current marker follows HEAD before the overlay is refreshed`() {
        val current = StateMerger.merge(githubFile, githubView, "frontend")[0]
        assertTrue(current.branch("frontend")!!.isCurrent)
        assertFalse(current.branch("api")!!.isCurrent)
        assertEquals(BranchStatus.NEEDS_REBASE, current.branch("api")!!.status, "statuses still come from the overlay")
    }

    @Test
    fun `an overlay from the previous stack is not applied after switching stacks`() {
        val stacks = StateMerger.merge(githubFile, githubView, "spike-a")
        assertFalse(stacks[0].isCurrent)
        assertEquals(BranchStatus.OPEN, stacks[0].branch("api")!!.status, "file data, not the stale overlay")
        assertTrue(stacks[1].isCurrent)
    }

    @Test
    fun `fallback mode builds the current stack from the overlay alone`() {
        val stack = StateMerger.merge(null, githubView, "api").single()
        assertNull(stack.number)
        assertTrue(stack.isCurrent)
        assertEquals(4, stack.branches.size)
        assertEquals(emptyList<StackUi>(), StateMerger.merge(null, null, "api"))
    }

    @Test
    fun `trunk checkout has no current stack`() {
        assertTrue(StateMerger.merge(githubFile, null, "main").none { it.isCurrent })
    }

    @Test
    fun `stack helpers`() {
        val stack = StateMerger.merge(githubFile, githubView, "api")[0]
        assertEquals("Stack #7", stack.title)
        assertEquals(listOf("auth", "api", "frontend"), stack.activeBranches.map { it.name })
        assertEquals("main", stack.activeParentOf("auth"))
        assertEquals("auth", stack.activeParentOf("api"))
        assertEquals(2 to 3, stack.positionOf("api"))
        assertNull(stack.positionOf("models"))
        assertEquals("frontend", stack.topBranch!!.name)
        assertTrue(stack.needsRebase)
        assertEquals("Local stack", StateMerger.merge(githubFile, null, "api")[1].title)
    }
}
