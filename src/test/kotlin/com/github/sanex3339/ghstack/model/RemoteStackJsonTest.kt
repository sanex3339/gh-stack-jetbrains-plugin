package com.github.sanex3339.ghstack.model

import com.github.sanex3339.ghstack.testutil.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RemoteStackJsonTest {
    @Test
    fun `parses a single stack and separates open PRs`() {
        assertEquals(RemoteStackInfo(7, listOf(42, 43, 44), listOf(43, 44)), RemoteStackJson.parseOne(Fixtures.text("remote-stack.json")))
    }

    @Test
    fun `closed unmerged PRs are listed separately`() {
        val json = """{"number":7,"pull_requests":[{"number":1,"state":"closed","merged_at":"2026-01-01T00:00:00Z"},{"number":2,"state":"closed","merged_at":null},{"number":3,"state":"open"}]}"""
        assertEquals(RemoteStackInfo(7, listOf(1, 2, 3), listOf(3), listOf(2)), RemoteStackJson.parseOne(json))
    }

    @Test
    fun `parses the pull_request lookup list`() {
        assertEquals(listOf(RemoteStackInfo(7, listOf(43, 44), listOf(43, 44))), RemoteStackJson.parseList(Fixtures.text("remote-stack-list.json")))
        assertEquals(emptyList<RemoteStackInfo>(), RemoteStackJson.parseList("[]"))
    }

    @Test
    fun `bad JSON is null or empty`() {
        assertNull(RemoteStackJson.parseOne("{\"message\":\"Not Found\""))
        assertEquals(emptyList<RemoteStackInfo>(), RemoteStackJson.parseList("nope"))
    }
}
