package com.github.sanex3339.ghstack.model

import com.github.sanex3339.ghstack.testutil.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StackFileParserTest {
    private fun parsed(name: String): StackFile =
        assertInstanceOf(StackFileResult.Parsed::class.java, StackFileParser.parse(Fixtures.bytes(name))).file

    @Test
    fun `parses the file written by gh stack offline`() {
        val file = parsed("stack-file-offline.json")
        assertNull(file.repository)
        val stack = file.stacks.single()
        assertEquals("main", stack.trunk)
        assertEquals(listOf("auth", "api"), stack.branches.map { it.name })
        assertNull(stack.number)
        assertNull(stack.id)
        assertNull(stack.branches[0].pr)
    }

    @Test
    fun `parses numbers, ids, PRs and merged flags`() {
        val file = parsed("stack-file-github.json")
        assertEquals(RepoCoordinates("github.com", "octo", "app"), file.repository)
        val stack = file.stacks[0]
        assertEquals(7, stack.number)
        assertEquals("1201", stack.id)
        assertEquals(PrRef(42, "https://github.com/octo/app/pull/42"), stack.branches[0].pr)
        assertEquals(true, stack.branches[0].merged)
        assertEquals(false, stack.branches[1].merged)
        assertEquals(listOf("spike-a", "spike-b"), file.stacks[1].branches.map { it.name })
        assertEquals("develop", file.stacks[1].trunk)
    }

    @Test
    fun `missing file means no stacks`() {
        assertEquals(StackFileResult.Parsed(StackFile.EMPTY), StackFileParser.parse(null))
    }

    @Test
    fun `newer schema is reported as unsupported`() {
        assertEquals(StackFileResult.Unsupported(2), StackFileParser.parse(Fixtures.bytes("stack-file-future.json")))
    }

    @Test
    fun `garbage is malformed`() {
        assertInstanceOf(StackFileResult.Malformed::class.java, StackFileParser.parse("{not json".toByteArray()))
    }

    @Test
    fun `repository coordinates parse host owner and name`() {
        assertEquals(RepoCoordinates("ghe.example.com", "team", "svc"), RepoCoordinates.parse("ghe.example.com:team/svc"))
        assertEquals("repos/team/svc", RepoCoordinates.parse("ghe.example.com:team/svc")!!.apiPath)
        assertNull(RepoCoordinates.parse(""))
        assertNull(RepoCoordinates.parse("github.com:no-slash"))
        assertNull(RepoCoordinates.parse("octo/app"))
    }
}
