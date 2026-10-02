package com.github.sanex3339.ghstack.model

import com.github.sanex3339.ghstack.testutil.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ViewJsonParserTest {
    @Test
    fun `parses offline output without PRs`() {
        val view = ViewJsonParser.parse(Fixtures.text("view-offline.json"))!!
        assertEquals("main", view.trunk)
        assertEquals("api", view.currentBranch)
        assertEquals(listOf("auth", "api"), view.branches.map { it.name })
        assertEquals(true, view.branches[1].isCurrent)
        assertNull(view.branches[0].pr)
    }

    @Test
    fun `parses PR states and flags`() {
        val view = ViewJsonParser.parse(Fixtures.text("view-github.json"))!!
        assertEquals(ViewPr(42, "https://github.com/octo/app/pull/42", PrState.MERGED), view.branches[0].pr)
        assertEquals(true, view.branches[0].isMerged)
        assertEquals(true, view.branches[1].isQueued)
        assertEquals(PrState.QUEUED, view.branches[1].pr!!.state)
        assertEquals(true, view.branches[2].needsRebase)
    }

    @Test
    fun `unknown PR states and bad JSON degrade gracefully`() {
        assertEquals(PrState.UNKNOWN, PrState.parse("CLOSED"))
        assertEquals(PrState.OPEN, PrState.parse("open"))
        assertNull(ViewJsonParser.parse("✗ not in a stack"))
    }
}
