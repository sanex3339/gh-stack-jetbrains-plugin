package com.github.sanex3339.ghstack.planning

/** Approximates `git check-ref-format --branch` for instant dialog feedback; git still has the final word. */
object BranchNames {
    private val forbiddenSequences = listOf("..", "~", "^", ":", "?", "*", "[", "\\", "@{", "//")

    fun validationError(name: String): String? {
        if (name.isBlank()) return "Enter a branch name"
        if (name.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return "Branch names can't contain spaces or control characters"
        forbiddenSequences.firstOrNull { it in name }?.let { return "Branch names can't contain \"$it\"" }
        if (name == "@") return "\"@\" isn't a valid branch name"
        if (name.startsWith("-")) return "Branch names can't start with \"-\""
        if (name.startsWith("/") || name.endsWith("/")) return "Branch names can't start or end with \"/\""
        if (name.endsWith(".") || name.endsWith(".lock")) return "Branch names can't end with \".\" or \".lock\""
        if (name.split('/').any { it.startsWith(".") }) return "Branch name parts can't start with \".\""
        return null
    }
}
