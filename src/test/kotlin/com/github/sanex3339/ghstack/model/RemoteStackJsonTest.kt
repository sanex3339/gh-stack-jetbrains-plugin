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
