package com.github.sanex3339.ghstack.planning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DialogRequestsTest {
    @Test
    fun `add without commit needs a name`() {
        assertNotNull(AddBranchRequest("", AddCommitMode.NONE, "").validationError())
        assertEquals(listOf("add", "api"), AddBranchRequest(" api ", AddCommitMode.NONE, "").args())
    }

    @Test
    fun `add with commit needs a message and maps staging flags`() {
        assertNotNull(AddBranchRequest("api", AddCommitMode.ALL, " ").validationError())
        assertNull(AddBranchRequest("", AddCommitMode.ALL, "Add API").validationError())
        assertEquals(listOf("add", "-A", "-m", "Add API", "api"), AddBranchRequest("api", AddCommitMode.ALL, "Add API").args())
        assertEquals(listOf("add", "-u", "-m", "Fix"), AddBranchRequest("", AddCommitMode.TRACKED, "Fix").args())
        assertEquals(listOf("add", "-m", "Staged"), AddBranchRequest("", AddCommitMode.STAGED, "Staged").args())
    }

    @Test
    fun `new stack request parses lines and validates`() {
        val request = NewStackRequest(" develop ", "auth\n\n  api \nui")
        assertEquals(listOf("auth", "api", "ui"), request.branches)
        assertNull(request.validationError())
        assertEquals(listOf("init", "--base", "develop", "auth", "api", "ui"), request.args())
        assertEquals(listOf("init", "auth"), NewStackRequest("", "auth").args())
        assertNotNull(NewStackRequest("", " \n ").validationError())
        assertNotNull(NewStackRequest("", "a\na").validationError())
        assertNotNull(NewStackRequest("main", "main\nx").validationError())
        assertNotNull(NewStackRequest("", "bad name").validationError())
    }
}
