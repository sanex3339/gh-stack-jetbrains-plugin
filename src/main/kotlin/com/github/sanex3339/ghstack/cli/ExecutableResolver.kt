package com.github.sanex3339.ghstack.cli

import java.io.File

object ExecutableResolver {
    /** IDEs launched from the macOS Dock may lack the login-shell PATH, so look here too. */
    private val defaultExtraDirs = listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin")
    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    fun find(name: String, pathValue: String?, extraDirs: List<String> = defaultExtraDirs): String? {
        val dirs = pathValue.orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() } + extraDirs
        val names = if (isWindows) listOf("$name.exe", "$name.cmd", "$name.bat", name) else listOf(name)
        return dirs.asSequence()
            .flatMap { dir -> names.asSequence().map { File(dir, it) } }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    /** A configured path is used as-is (`null` when not executable, so the user sees the problem). */
    fun resolveGh(configured: String?, pathValue: String?): String? {
        if (!configured.isNullOrBlank()) return File(configured).takeIf { it.isFile && it.canExecute() }?.absolutePath
        return find("gh", pathValue)
    }
}
