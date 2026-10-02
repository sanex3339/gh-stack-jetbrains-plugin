package com.github.sanex3339.ghstack.planning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PrPrefillTest {
    private val rs = '\u001e'
    private val us = '\u001f'

    @Test
    fun `parses git log records`() {
        val output = "${rs}Add models${us}Body line 1\nBody line 2\n\n${rs}Second${us}\n"
        assertEquals(
            listOf(CommitMessage("Add models", "Body line 1\nBody line 2"), CommitMessage("Second", "")),
            PrPrefill.parseLog(output),
        )
        assertEquals(emptyList<CommitMessage>(), PrPrefill.parseLog(""))
    }

    @Test
    fun `one commit gives its subject and body`() {
        assertEquals("Add models" to "Why", PrPrefill.prefill("api-models", listOf(CommitMessage("Add models", "Why"))))
    }

    @Test
    fun `several or zero commits give the humanized branch name like gh stack`() {
        val two = listOf(CommitMessage("a", ""), CommitMessage("b", ""))
        assertEquals("feat/api models" to "", PrPrefill.prefill("feat/api-models", two))
        assertEquals("fix login bug" to "", PrPrefill.prefill("fix_login-bug", emptyList()))
    }
}
