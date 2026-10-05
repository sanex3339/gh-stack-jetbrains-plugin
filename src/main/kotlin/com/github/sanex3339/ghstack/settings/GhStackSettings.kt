package com.github.sanex3339.ghstack.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

enum class MergeMethod(val flag: String, private val label: String) {
    SQUASH("--squash", "Squash and merge"),
    MERGE("--merge", "Create a merge commit"),
    REBASE("--rebase", "Rebase and merge");

    override fun toString() = label
}

enum class MergeMethodPreference(private val label: String) {
    REMEMBER_LAST("Remember last used"),
    SQUASH("Squash and merge"),
    MERGE("Create a merge commit"),
    REBASE("Rebase and merge");

    override fun toString() = label
}

@Service(Service.Level.APP)
@State(name = "GhStackSettings", storages = [Storage("ghStack.xml")])
class GhStackSettings : SimplePersistentStateComponent<GhStackSettings.SettingsState>(SettingsState()) {
    class SettingsState : BaseState() {
        var ghPath by string()
        var draftByDefault by property(false)
        var showPrTitles by property(false)
        var mergePreference by enum(MergeMethodPreference.REMEMBER_LAST)
        var lastMergeMethod by enum(MergeMethod.SQUASH)
        var mcpToolsEnabled by property(false)
        var autoPullStacks by property(true)
    }

    fun initialMergeMethod(): MergeMethod = when (state.mergePreference ?: MergeMethodPreference.REMEMBER_LAST) {
        MergeMethodPreference.REMEMBER_LAST -> state.lastMergeMethod ?: MergeMethod.SQUASH
        MergeMethodPreference.SQUASH -> MergeMethod.SQUASH
        MergeMethodPreference.MERGE -> MergeMethod.MERGE
        MergeMethodPreference.REBASE -> MergeMethod.REBASE
    }

    companion object {
        fun getInstance(): GhStackSettings = service()
    }
}
