package com.github.sanex3339.ghstack.ide

import com.github.sanex3339.ghstack.cli.CommandRunner
import com.github.sanex3339.ghstack.cli.ExecutableResolver
import com.github.sanex3339.ghstack.settings.GhStackSettings
import com.intellij.util.EnvironmentUtil

/** The user's login-shell environment (the IDE loads it on macOS) and the tools found in it. */
object IdeEnvironment {
    fun shellEnvironment(): Map<String, String> = EnvironmentUtil.getEnvironmentMap()

    private fun pathValue(): String? = EnvironmentUtil.getValue("PATH")

    fun ghPath(): String? = ExecutableResolver.resolveGh(GhStackSettings.getInstance().state.ghPath, pathValue())

    fun gitPath(): String? = ExecutableResolver.find("git", pathValue())

    fun runner(): CommandRunner = CommandRunner(::shellEnvironment)
}
