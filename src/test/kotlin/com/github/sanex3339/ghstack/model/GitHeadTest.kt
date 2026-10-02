package com.github.sanex3339.ghstack.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GitHeadTest {
    @Test
    fun `reads the branch from a symbolic ref`() {
        assertEquals("feat/api", GitHead.currentBranch("ref: refs/heads/feat/api\n".toByteArray()))
    }

    @Test
    fun `detached or missing HEAD has no branch`() {
        assertNull(GitHead.currentBranch("4bbd75a07711aeb6f446cd6d4f7a866c21459e5e\n".toByteArray()))
        assertNull(GitHead.currentBranch(null))
    }
}
