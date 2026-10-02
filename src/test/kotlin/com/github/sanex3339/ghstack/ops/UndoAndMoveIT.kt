package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.model.StateMerger
import com.github.sanex3339.ghstack.planning.InsertCheck
import com.github.sanex3339.ghstack.planning.InsertDirection
import com.github.sanex3339.ghstack.planning.InsertPlanner
import com.github.sanex3339.ghstack.testutil.GitSandbox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Real git + gh stack: main ← auth ← api. */
class UndoAndMoveIT {
    private lateinit var sandbox: GitSandbox

    @BeforeEach
    fun setUp() {
        GitSandbox.assumeGhStack()
        sandbox = GitSandbox.create()
        sandbox.stack("init", "--base", "main", "auth")
        sandbox.commitFile("auth.txt", "auth", "Add auth")
        sandbox.stack("add", "api")
        sandbox.commitFile("api.txt", "api", "Add api")
    }

    @AfterEach
    fun tearDown() {
        if (::sandbox.isInitialized) sandbox.close()
    }

    private fun tips() = listOf("main", "auth", "api").associateWith { sandbox.git("rev-parse", it) }

    @Test
    fun `undo reverts a rebase`() {
        val undo = UndoWorkflow(sandbox.cli(), sandbox.gitDir)
        sandbox.git("checkout", "auth")
        sandbox.commitFile("auth.txt", "auth v2", "Change auth")
        sandbox.git("checkout", "api")
        val before = tips()
        undo.save(undo.capture("Rebase stack"))

        sandbox.stack("rebase", "--no-trunk")
        assertTrue(sandbox.git("rev-parse", "api") != before["api"], "the rebase rewrote api")

        undo.undo()
        assertEquals(before, tips())
        assertEquals("api", sandbox.currentBranch())
        assertFalse(sandbox.gitDir.resolve(UndoWorkflow.FILE_NAME).exists())
    }

    @Test
    fun `undo reverts an insert, including the new branch and the stack file`() {
        val undo = UndoWorkflow(sandbox.cli(), sandbox.gitDir)
        val before = tips()
        val stackFileBefore = sandbox.gitDir.resolve("gh-stack").readText()
        undo.save(undo.capture("Insert"))

        val stack = StateMerger.merge(sandbox.stackFile(), null, "api").single()
        val plan = (InsertPlanner.plan(stack, "api", InsertDirection.BELOW, "api-models", sandbox.branches(), OperationState.None) as InsertCheck.Ready).plan
        InsertBranchWorkflow(sandbox.cli(), sandbox.gitDir).run(plan, "api")
        assertEquals(listOf(listOf("auth", "api-models", "api")), sandbox.stackOrders())

        undo.undo()
        assertEquals(before, tips())
        assertEquals(stackFileBefore, sandbox.gitDir.resolve("gh-stack").readText())
        assertFalse("api-models" in sandbox.branches())
        assertEquals("api", sandbox.currentBranch())
    }

    @Test
    fun `nothing to undo is an error`() {
        assertThrows<WorkflowAbort> { UndoWorkflow(sandbox.cli(), sandbox.gitDir).undo() }
    }

    @Test
    fun `moves uncommitted changes into a lower layer and rebases the layers above`() {
        sandbox.work.resolve("models.txt").writeText("models")          // new file
        sandbox.work.resolve("auth.txt").writeText("auth, improved")    // edit of a file the lower layer owns
        sandbox.work.resolve("scratch.txt").writeText("stays here")     // not moved

        val outcome = MoveChangesWorkflow(sandbox.cli()).run(listOf("models.txt", "auth.txt"), "auth", "Add models", currentBranch = "api")

        assertEquals(MoveOutcome.Moved, outcome)
        assertEquals("api", sandbox.currentBranch())
        assertEquals("Add models", sandbox.git("log", "-1", "--format=%s", "auth"))
        assertEquals("models", sandbox.git("show", "auth:models.txt"))
        assertEquals("auth, improved", sandbox.git("show", "api:auth.txt"), "api was rebased onto the new auth")
        assertEquals("?? scratch.txt", sandbox.git("status", "--porcelain"), "only the unselected change is left")
    }

    @Test
    fun `changes that don't apply on the target are put back`() {
        // api's own file doesn't exist on auth, so an edit to it can't move down.
        sandbox.work.resolve("api.txt").writeText("api, edited")
        val before = tips()

        assertThrows<WorkflowAbort> { MoveChangesWorkflow(sandbox.cli()).run(listOf("api.txt"), "auth", "Move", currentBranch = "api") }

        assertEquals("api", sandbox.currentBranch())
        assertEquals("api, edited", sandbox.work.resolve("api.txt").readText())
        assertEquals(before, tips())
        assertEquals("", sandbox.git("stash", "list"))
    }

    @Test
    fun `undo snapshot round-trips`() {
        val undo = UndoWorkflow(sandbox.cli(), sandbox.gitDir)
        val snapshot = undo.capture("Sync")
        undo.save(snapshot)
        assertEquals(snapshot, undo.load())
        assertArrayEquals(snapshot.stackFile!!.encodeToByteArray(), sandbox.gitDir.resolve("gh-stack").readText().encodeToByteArray())
    }
}
