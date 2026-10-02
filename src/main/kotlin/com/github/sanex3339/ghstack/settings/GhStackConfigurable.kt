package com.github.sanex3339.ghstack.settings

import com.github.sanex3339.ghstack.ide.StackStateService
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

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
    }

    override fun apply() {
        super.apply()
        ProjectManager.getInstance().openProjects.forEach { StackStateService.getInstance(it).recheckCli() }
    }
}
