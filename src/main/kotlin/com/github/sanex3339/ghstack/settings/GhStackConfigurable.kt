package com.github.sanex3339.ghstack.settings

import com.github.sanex3339.ghstack.ide.StackStateService
import com.github.sanex3339.ghstack.skill.AgentSkillInstaller
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.io.IOException
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JLabel

class GhStackConfigurable : BoundConfigurable("Stacked PRs") {
    private val settings get() = GhStackSettings.getInstance().state

    override fun createPanel(): DialogPanel = panel {
        row("Path to gh:") {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Select the gh Executable"))
                .bindText({ settings.ghPath.orEmpty() }, { settings.ghPath = it.trim().ifEmpty { null } })
                .align(AlignX.FILL)
                .comment("Leave empty to find gh on PATH, /opt/homebrew/bin or /usr/local/bin.")
        }
        row {
            checkBox("Create new pull requests as drafts")
                .bindSelected({ settings.draftByDefault }, { settings.draftByDefault = it })
        }
        row("Merge method:") {
            comboBox(MergeMethodPreference.entries)
                .bindItem({ settings.mergePreference ?: MergeMethodPreference.REMEMBER_LAST }, { settings.mergePreference = it ?: MergeMethodPreference.REMEMBER_LAST })
        }
        row {
            checkBox("Pull a branch's stack from GitHub when checking out a branch no local stack tracks")
                .bindSelected({ settings.autoPullStacks }, { settings.autoPullStacks = it })
                .comment("For example a colleague's stack, or your own from another machine. Runs <code>gh stack checkout</code>.")
        }
        row {
            checkBox("Let AI agents use stack tools through the IDE's MCP server")
                .bindSelected({ settings.mcpToolsEnabled }, { settings.mcpToolsEnabled = it })
                .comment(
                    "Adds stack_view, stack_rebase, stack_push, stack_submit and other tools for agents connected to " +
                        "Settings | Tools | MCP Server. Turning this off stops them right away; agents see the tool list " +
                        "change after the IDE restarts. The agent skill below tells agents when to use them.",
                )
        }
        group("Agent Skill") {
            row {
                text(
                    "Teaches AI agents when and how to use the stack tools: a <code>${AgentSkillInstaller.NAME}</code> skill " +
                        "for Claude Code (<code>~/.claude/skills</code>) and for agents that read <code>~/.agents/skills</code>.",
                )
            }
            row { skillStatus = label("").component }
            row {
                installButton = button("Install") { skillAction { skill.install() } }.component
                removeButton = button("Remove") { skillAction { skill.remove() } }.component
            }
        }
        updateSkillUi()
    }

    private val skill by lazy {
        val bundled = AgentSkillInstaller.bundledTemplate()
        AgentSkillInstaller(Path.of(System.getProperty("user.home")), bundled, AgentSkillInstaller.versionOf(bundled) ?: "dev")
    }
    private lateinit var skillStatus: JLabel
    private lateinit var installButton: JButton
    private lateinit var removeButton: JButton

    /** Installing and removing act right away, like the IDE's own install buttons, not on Apply. */
    private fun skillAction(action: () -> Unit) {
        try {
            action()
        } catch (e: IOException) {
            Messages.showErrorDialog(installButton, e.message ?: e.toString(), "Agent Skill")
        }
        updateSkillUi()
    }

    private fun updateSkillUi() {
        val states = skill.status().map { it.state }
        skillStatus.text = skill.describe()
        installButton.text = if (states.any { it is AgentSkillInstaller.FolderState.Outdated }) "Update" else "Install"
        installButton.isEnabled = states.any { it is AgentSkillInstaller.FolderState.Missing || it is AgentSkillInstaller.FolderState.Outdated }
        removeButton.isEnabled = states.any { it == AgentSkillInstaller.FolderState.Current || it is AgentSkillInstaller.FolderState.Outdated }
    }

    override fun apply() {
        super.apply()
        ProjectManager.getInstance().openProjects.forEach { StackStateService.getInstance(it).recheckCli() }
    }
}
