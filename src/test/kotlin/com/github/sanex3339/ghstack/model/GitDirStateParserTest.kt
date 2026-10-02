package com.github.sanex3339.ghstack.model

import com.github.sanex3339.ghstack.testutil.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GitDirStateParserTest {
    @Test
    fun `no state files means no operation`() {
        assertEquals(OperationState.None, GitDirStateParser.operationState(null, null))
    }

    @Test
    fun `rebase state reports the conflicted branch`() {
        assertEquals(OperationState.RebaseConflict("api"), GitDirStateParser.operationState(Fixtures.bytes("rebase-state.json"), null))
    }

    @Test
    fun `unreadable rebase state is still a rebase in progress`() {
        assertEquals(OperationState.RebaseConflict(null), GitDirStateParser.operationState("garbage".toByteArray(), null))
    }

    @Test
    fun `modify phases`() {
        assertEquals(OperationState.ModifyPendingSubmit, GitDirStateParser.operationState(null, Fixtures.bytes("modify-state-pending.json")))
        assertEquals(OperationState.ModifyInterrupted("conflict"), GitDirStateParser.operationState(null, Fixtures.bytes("modify-state-conflict.json")))
    }

    @Test
    fun `rebase wins over modify`() {
        assertEquals(
            OperationState.RebaseConflict("api"),
            GitDirStateParser.operationState(Fixtures.bytes("rebase-state.json"), Fixtures.bytes("modify-state-conflict.json")),
        )
    }
}
