package com.github.sanex3339.ghstack

import com.github.sanex3339.ghstack.testutil.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Default chords must not shadow anything bundled with WebStorm (fixture from scripts/dump-keystrokes.py). */
class KeymapConflictTest {
    private val pluginXml = requireNotNull(javaClass.getResourceAsStream("/META-INF/plugin.xml")).use { it.readBytes().decodeToString() }
    private val taken = Fixtures.text("taken-first-keystrokes.txt").lines().filter { it.isNotBlank() }.toSet()

    private fun normalize(keystroke: String): String {
        val parts = keystroke.lowercase().replace("ctrl", "control").split(' ').filter { it.isNotEmpty() }
        return (parts.dropLast(1).toSortedSet() + parts.last()).joinToString(" ")
    }

    @Test
    fun `chord prefixes are free in the bundled keymaps and plugins`() {
        val prefixes = Regex("""first-keystroke="([^"]+)"""").findAll(pluginXml).map { normalize(it.groupValues[1]) }.toSet()
        assertEquals(setOf("alt control x", "alt meta k"), prefixes)
        prefixes.forEach { assertTrue(it !in taken, "$it is already bound in WebStorm") }
    }

    @Test
    fun `every shortcut is a two-stroke chord`() {
        val shortcuts = Regex("""<keyboard-shortcut [^>]*/>""").findAll(pluginXml).map { it.value }.toList()
        assertTrue(shortcuts.isNotEmpty())
        shortcuts.forEach { assertTrue("second-keystroke=" in it, it) }
    }
}
