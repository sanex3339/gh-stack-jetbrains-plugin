package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.model.GitDirStateParser
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.model.RemovalState
import com.github.sanex3339.ghstack.model.RemoveMode
import com.github.sanex3339.ghstack.model.StateMerger
import com.github.sanex3339.ghstack.planning.RemovalCheck
import com.github.sanex3339.ghstack.planning.RemovePlanner
import com.github.sanex3339.ghstack.testutil.GitSandbox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.io.path.exists
import kotlin.io.path.writeText

/** Real git + gh stack: main ← auth ← api ← ui, each adding one file. */
class RemoveBranchWorkflowIT {
    private lateinit var sandbox: GitSandbox

    @BeforeEach
    fun setUp() {
        GitSandbox.assumeGhStack()
        sandbox = GitSandbox.create()
        sandbox.stack("init", "--base", "main", "auth")
        sandbox.commitFile("auth.txt", "auth", "Add auth")
        sandbox.stack("add", "api")
        sandbox.commitFile("api.txt", "api", "Add api")
        sandbox.stack("add", "ui")
        sandbox.commitFile("ui.txt", "ui", "Add ui")
    }

    @AfterEach
    fun tearDown() {
        if (::sandbox.isInitialized) sandbox.close()
    }

    private fun start(target: String, mode: RemoveMode, options: RemovalOptions = RemovalOptions(closePr = false, deleteBranch = false)): RemovalOutcome {
        val current = sandbox.currentBranch()
        val stack = StateMerger.merge(sandbox.stackFile(), null, current).single()
        val plan = (RemovePlanner.plan(stack, target, mode, OperationState.None) as RemovalCheck.Ready).plan
        return RemoveBranchWorkflow(sandbox.cli(), sandbox.gitDir).start(plan, options, RepoSnapshot(stack, current, null, OperationState.None))
    }

    private fun files(branch: String): Set<String> = sandbox.git("ls-tree", "--name-only", branch).lines().toSet()

    private fun removalStatePath() = sandbox.gitDir.resolve(RemovalState.FILE_NAME)

    @Test
    fun `drop removes the branch and its commits from the branches above`() {
        val outcome = start("api", RemoveMode.DROP)
        assertEquals(RemovalOutcome.Done(githubUpdated = false, warnings = emptyList()), outcome)
        assertEquals(listOf(listOf("auth", "ui")), sandbox.stackOrders())
        assertEquals(setOf("a.txt", "auth.txt", "ui.txt"), files("ui"))
        assertEquals("ui", sandbox.currentBranch())
        assertTrue("api" in sandbox.branches(), "the local branch is kept unless asked otherwise")
    }

    @Test
    fun `dropping the checked-out branch moves to the branch above and can delete it`() {
        sandbox.git("checkout", "api")
        start("api", RemoveMode.DROP, RemovalOptions(closePr = false, deleteBranch = true))
        assertEquals("ui", sandbox.currentBranch())
        assertFalse("api" in sandbox.branches())
    }

    @Test
    fun `fold down moves the commits into the branch below`() {
        val apiTip = sandbox.git("rev-parse", "api")
        start("api", RemoveMode.FOLD_DOWN)
        assertEquals(listOf(listOf("auth", "ui")), sandbox.stackOrders())
        assertEquals(apiTip, sandbox.git("rev-parse", "auth"))
        assertEquals(setOf("a.txt", "auth.txt", "api.txt", "ui.txt"), files("ui"))
    }

    @Test
    fun `fold up keeps the commits in the branch above without rewriting anything`() {
        val uiTip = sandbox.git("rev-parse", "ui")
        start("api", RemoveMode.FOLD_UP)
        assertEquals(listOf(listOf("auth", "ui")), sandbox.stackOrders())
        assertEquals(uiTip, sandbox.git("rev-parse", "ui"))
        assertTrue("api.txt" in files("ui"))
    }

    @Test
    fun `a conflicting drop pauses and abort puts everything back`() {
        conflictingUi()
        val tipsBefore = listOf("auth", "api", "ui").associateWith { sandbox.git("rev-parse", it) }
        val stackFileBefore = StackFileStore.read(sandbox.gitDir)

        val outcome = start("api", RemoveMode.DROP)

        assertEquals(RemovalOutcome.StoppedOnConflict("ui"), outcome)
        assertTrue(removalStatePath().exists())
        assertEquals(
            OperationState.RemovalStopped("api", "ui"),
            GitDirStateParser.operationState(null, null, StackFileStore.read(sandbox.gitDir, RemovalState.FILE_NAME)),
        )

        RemoveBranchWorkflow(sandbox.cli(), sandbox.gitDir).abort()

        assertEquals(tipsBefore, listOf("auth", "api", "ui").associateWith { sandbox.git("rev-parse", it) })
        assertArrayEquals(stackFileBefore, StackFileStore.read(sandbox.gitDir))
        assertFalse(removalStatePath().exists())
        assertEquals("ui", sandbox.currentBranch())
    }

    @Test
    fun `a conflicting drop continues after the conflict is resolved`() {
        conflictingUi()
        assertInstanceOf(RemovalOutcome.StoppedOnConflict::class.java, start("api", RemoveMode.DROP))

        // Resolve: keep ui's version of api.txt.
        sandbox.work.resolve("api.txt").writeText("ui owns this now")
        sandbox.git("add", "api.txt")
        val outcome = RemoveBranchWorkflow(sandbox.cli(), sandbox.gitDir).resume()

        assertEquals(RemovalOutcome.Done(githubUpdated = false, warnings = emptyList()), outcome)
        assertEquals(listOf(listOf("auth", "ui")), sandbox.stackOrders())
        assertEquals("ui owns this now", sandbox.git("show", "ui:api.txt"))
        assertFalse(removalStatePath().exists())
        assertNull(GitDirStateParser.operationState(null, null, StackFileStore.read(sandbox.gitDir, RemovalState.FILE_NAME)).takeIf { it != OperationState.None })
    }

    @Test
    fun `uncommitted changes block the removal`() {
        sandbox.work.resolve("ui.txt").writeText("dirty")
        assertThrows<WorkflowAbort> { start("api", RemoveMode.DROP) }
        assertEquals(listOf(listOf("auth", "api", "ui")), sandbox.stackOrders())
    }

    /** ui edits api.txt, so dropping api (which created the file) conflicts while rebasing ui. */
    private fun conflictingUi() {
        sandbox.commitFile("api.txt", "changed by ui", "ui edits api")
    }
}
