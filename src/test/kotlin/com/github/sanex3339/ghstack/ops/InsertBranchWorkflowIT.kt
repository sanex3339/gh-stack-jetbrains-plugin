package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.cli.CliResult
import com.github.sanex3339.ghstack.cli.StackCli
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.model.StackUi
import com.github.sanex3339.ghstack.model.StateMerger
import com.github.sanex3339.ghstack.planning.InsertCheck
import com.github.sanex3339.ghstack.planning.InsertDirection
import com.github.sanex3339.ghstack.planning.InsertPlan
import com.github.sanex3339.ghstack.planning.InsertPlanner
import com.github.sanex3339.ghstack.testutil.GitSandbox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.io.path.writeText

class InsertBranchWorkflowIT {
    private lateinit var sandbox: GitSandbox

    @BeforeEach
    fun setUp() {
        assumeTrue(GitSandbox.ghStackAvailable(), "gh stack is not installed")
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

    private fun stackUi(): StackUi = StateMerger.merge(sandbox.stackFile(), null, sandbox.currentBranch()).single()

    private fun plan(target: String, direction: InsertDirection, name: String): InsertPlan {
        val check = InsertPlanner.plan(stackUi(), target, direction, name, sandbox.branches(), OperationState.None)
        return (check as InsertCheck.Ready).plan
    }

    @Test
    fun `insert below the top creates the branch at its parent and re-registers the order`() {
        val authTip = sandbox.git("rev-parse", "auth")
        InsertBranchWorkflow(sandbox.cli(), sandbox.gitDir).run(plan("api", InsertDirection.BELOW, "api-models"), "api")
        assertEquals(listOf(listOf("auth", "api-models", "api")), sandbox.stackOrders())
        assertEquals("api-models", sandbox.currentBranch())
        assertEquals(authTip, sandbox.git("rev-parse", "api-models"))
    }

    @Test
    fun `insert above the top is a plain add`() {
        InsertBranchWorkflow(sandbox.cli(), sandbox.gitDir).run(plan("api", InsertDirection.ABOVE, "ui"), "api")
        assertEquals(listOf(listOf("auth", "api", "ui")), sandbox.stackOrders())
        assertEquals("ui", sandbox.currentBranch())
    }

    @Test
    fun `a failing init restores the stack file and removes the new branch`() {
        val before = StackFileStore.read(sandbox.gitDir)
        val real = sandbox.cli()
        val failingInit = object : StackCli by real {
            override fun stack(vararg args: String): CliResult =
                if (args.firstOrNull() == "init") CliResult(1, "", "✗ simulated failure") else real.stack(*args)
        }
        val error = assertThrows<WorkflowAbort> {
            InsertBranchWorkflow(failingInit, sandbox.gitDir).run(plan("api", InsertDirection.BELOW, "api-models"), "api")
        }
        assertTrue(error.message!!.contains("simulated failure"), error.message)
        assertArrayEquals(before, StackFileStore.read(sandbox.gitDir))
        assertFalse("api-models" in sandbox.branches())
        assertEquals("api", sandbox.currentBranch())
    }

    @Test
    fun `uncommitted changes that block the final checkout keep the new stack and explain`() {
        sandbox.work.resolve("api.txt").writeText("dirty")
        val error = assertThrows<WorkflowAbort> {
            InsertBranchWorkflow(sandbox.cli(), sandbox.gitDir).run(plan("api", InsertDirection.BELOW, "api-models"), "api")
        }
        assertTrue(error.message!!.contains("switching to api-models failed"), error.message)
        assertEquals(listOf(listOf("auth", "api-models", "api")), sandbox.stackOrders())
        assertEquals("api", sandbox.currentBranch())
    }
}
