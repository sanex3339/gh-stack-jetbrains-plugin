package com.github.sanex3339.ghstack.skill

import com.github.sanex3339.ghstack.skill.AgentSkillInstaller.FolderState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AgentSkillInstallerTest {
    @TempDir
    lateinit var home: Path

    private val template = "---\nname: stacked-prs-ide\nmetadata:\n  installed-by: stacked-prs-jetbrains-plugin\n  plugin-version: \"{{version}}\"\n---\nUse the tools.\n"

    private fun installer(version: String = "0.4.0") = AgentSkillInstaller(home, template, version)

    private val claude get() = home.resolve(".claude/skills/stacked-prs-ide/SKILL.md")
    private val agents get() = home.resolve(".agents/skills/stacked-prs-ide/SKILL.md")

    @Test
    fun `installs the skill for Claude Code and for agents that read the shared folder`() {
        assertEquals(listOf(FolderState.Missing, FolderState.Missing), installer().status().map { it.state })
        installer().install()
        listOf(claude, agents).forEach { file ->
            assertTrue(Files.readString(file).contains("plugin-version: \"0.4.0\""), file.toString())
        }
        assertEquals(listOf(FolderState.Current, FolderState.Current), installer().status().map { it.state })
        assertEquals("Installed in ~/.claude/skills and ~/.agents/skills", installer().describe())
    }

    @Test
    fun `a copy written by an older plugin is outdated until installed again`() {
        installer("0.3.0").install()
        assertEquals(listOf(FolderState.Outdated("0.3.0"), FolderState.Outdated("0.3.0")), installer().status().map { it.state })
        assertEquals("Installed by plugin 0.3.0; Update to get the current instructions", installer().describe())
        installer().install()
        assertEquals(listOf(FolderState.Current, FolderState.Current), installer().status().map { it.state })
    }

    @Test
    fun `a skill of the same name the plugin didn't write is left alone`() {
        Files.createDirectories(agents.parent)
        Files.writeString(agents, "my own skill")
        installer().install()
        assertEquals("my own skill", Files.readString(agents))
        assertEquals(FolderState.Foreign, installer().status().last().state)
        installer().remove()
        assertEquals("my own skill", Files.readString(agents))
        assertFalse(Files.exists(claude))
        assertEquals("~/.agents/skills/stacked-prs-ide has a skill this plugin didn't write; it's left alone", installer().describe())
    }

    @Test
    fun `removing deletes only what it installed and the folders it emptied`() {
        installer().install()
        Files.writeString(claude.resolveSibling("notes.md"), "kept")
        installer().remove()
        assertFalse(Files.exists(claude))
        assertTrue(Files.exists(claude.resolveSibling("notes.md")), "other files in the folder stay")
        assertFalse(Files.exists(agents.parent), "an emptied skill folder goes away")
        assertEquals("Not installed", AgentSkillInstaller(home.resolve("nobody"), template, "0.4.0").describe())
    }

    @Test
    fun `only one location reports where it is`() {
        installer().install()
        Files.delete(agents)
        assertEquals("Installed in ~/.claude/skills only", installer().describe())
    }

    @Test
    fun `the bundled skill names every stack tool the plugin offers`() {
        val bundled = AgentSkillInstaller.bundledTemplate()
        assertTrue(bundled.startsWith("---\nname: stacked-prs-ide\n"), bundled.take(80))
        assertEquals(System.getProperty("stackedprs.version"), AgentSkillInstaller.versionOf(bundled), "the build writes the plugin version in")
        listOf(
            "stack_view", "stack_checkout", "stack_insert_branch", "stack_remove_branch", "stack_move_changes",
            "stack_rebase", "stack_rebase_continue", "stack_rebase_abort", "stack_push", "stack_submit",
            "stack_sync", "stack_mark_ready", "stack_undo",
        ).forEach { tool -> assertTrue("`$tool" in bundled, "the skill should describe $tool") }
    }
}
