package com.github.sanex3339.ghstack.skill

import java.nio.file.Files
import java.nio.file.Path

/**
 * Installs the agent skill this plugin ships (how and when to use its MCP stack tools) into the folders agents read:
 * `~/.claude/skills` (Claude Code) and `~/.agents/skills` (agents following the shared convention). Copies it wrote
 * carry a marker and the plugin version; a skill of the same name it didn't write is never touched.
 */
class AgentSkillInstaller(private val home: Path, private val template: String, private val version: String) {
    sealed interface FolderState {
        data object Missing : FolderState
        data object Current : FolderState
        data class Outdated(val version: String?) : FolderState
        data object Foreign : FolderState
    }

    data class Location(val folder: Path, val state: FolderState)

    private val folders: List<Path> = listOf(".claude", ".agents").map { home.resolve(it).resolve("skills").resolve(NAME) }

    private val rendered: String get() = template.replace("{{version}}", version)

    fun status(): List<Location> = folders.map { folder ->
        val file = folder.resolve(FILE)
        val state = when {
            !Files.isRegularFile(file) -> FolderState.Missing
            else -> {
                val text = Files.readString(file)
                when {
                    MARKER !in text -> FolderState.Foreign
                    text == rendered -> FolderState.Current
                    else -> FolderState.Outdated(versionOf(text))
                }
            }
        }
        Location(folder, state)
    }

    /** Writes the skill everywhere except where someone else's skill of the same name lives. */
    fun install() = status().filter { it.state != FolderState.Foreign }.forEach { location ->
        Files.createDirectories(location.folder)
        Files.writeString(location.folder.resolve(FILE), rendered)
    }

    /** Deletes the copies it wrote, and their folders once empty. */
    fun remove() = status().filter { it.state == FolderState.Current || it.state is FolderState.Outdated }.forEach { location ->
        Files.deleteIfExists(location.folder.resolve(FILE))
        Files.newDirectoryStream(location.folder).use { entries -> if (!entries.iterator().hasNext()) Files.delete(location.folder) }
    }

    /** One line for Settings, e.g. "Installed in ~/.claude/skills and ~/.agents/skills". */
    fun describe(): String {
        val locations = status()
        locations.firstOrNull { it.state == FolderState.Foreign }?.let {
            return "${display(it.folder)} has a skill this plugin didn't write; it's left alone"
        }
        locations.firstNotNullOfOrNull { it.state as? FolderState.Outdated }?.let {
            return "Installed by plugin ${it.version ?: "an older version"}; Update to get the current instructions"
        }
        val installed = locations.filter { it.state == FolderState.Current }.map { display(it.folder.parent) }
        return when (installed.size) {
            0 -> "Not installed"
            locations.size -> "Installed in " + installed.joinToString(" and ")
            else -> "Installed in ${installed.joinToString(" and ")} only"
        }
    }

    private fun display(path: Path): String = "~/" + home.relativize(path).joinToString("/")

    companion object {
        const val NAME = "stacked-prs-ide"
        private const val FILE = "SKILL.md"
        private const val MARKER = "installed-by: stacked-prs-jetbrains-plugin"
        private val VERSION = Regex("""plugin-version: "?([^"\n]+)"?""")

        /** The skill as bundled; the build writes the plugin version into it (see `processResources`). */
        fun bundledTemplate(): String =
            AgentSkillInstaller::class.java.getResource("/agent-skill/$FILE")?.readText()
                ?: error("The bundled agent skill is missing")

        /** The plugin version a skill text records, e.g. "0.4.1". */
        fun versionOf(text: String): String? = VERSION.find(text)?.groupValues?.get(1)
    }
}
