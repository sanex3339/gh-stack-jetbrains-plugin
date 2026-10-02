package com.github.sanex3339.ghstack.planning

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BranchNamesTest {
    @Test
    fun `accepts ordinary names`() {
        listOf("api", "feat/api-routes", "fix_login.v2", "user@feature").forEach { assertNull(BranchNames.validationError(it), it) }
    }

    @Test
    fun `rejects names git would reject`() {
        listOf("", " ", "has space", "a..b", "a~1", "a^", "a:b", "a?", "a*", "a[b", "a\\b", "-x", "/x", "x/", "x.", "x.lock", "a//b", "a@{1}", "@", "feat/.hidden")
            .forEach { assertNotNull(BranchNames.validationError(it), it) }
    }
}
