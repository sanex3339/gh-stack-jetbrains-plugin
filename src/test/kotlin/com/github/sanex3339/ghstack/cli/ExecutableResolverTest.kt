package com.github.sanex3339.ghstack.cli

import com.github.sanex3339.ghstack.testutil.Scripts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ExecutableResolverTest {
    @Test
    fun `finds an executable on PATH`() {
        val gh = Scripts.executable("gh", "exit 0")
        assertEquals(gh.toString(), ExecutableResolver.find("gh", "/nope:${gh.parent}", extraDirs = emptyList()))
    }

    @Test
    fun `missing tools resolve to null`() {
        assertNull(ExecutableResolver.find("definitely-not-a-tool-xyz", "", extraDirs = emptyList()))
    }

    @Test
    fun `a configured gh path wins and an invalid one is not silently replaced`() {
        val gh = Scripts.executable("gh", "exit 0")
        assertEquals(gh.toString(), ExecutableResolver.resolveGh(gh.toString(), ""))
        assertNull(ExecutableResolver.resolveGh("/nope/gh", gh.parent.toString()))
    }
}
