package com.github.sanex3339.ghstack.planning

enum class AddCommitMode { NONE, STAGED, TRACKED, ALL }

/** Input of the Add Branch dialog → `gh stack add` arguments. */
data class AddBranchRequest(val name: String, val mode: AddCommitMode, val message: String) {
    fun validationError(): String? {
        val trimmed = name.trim()
        if (mode == AddCommitMode.NONE && trimmed.isEmpty()) return "Enter a branch name"
        if (mode != AddCommitMode.NONE && message.isBlank()) return "Enter a commit message"
        if (trimmed.isNotEmpty()) BranchNames.validationError(trimmed)?.let { return it }
        return null
    }

    fun args(): List<String> = buildList {
        add("add")
        when (mode) {
            AddCommitMode.ALL -> add("-A")
            AddCommitMode.TRACKED -> add("-u")
            AddCommitMode.STAGED, AddCommitMode.NONE -> Unit
        }
        if (mode != AddCommitMode.NONE) {
            add("-m")
            add(message.trim())
        }
        name.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
    }
}

/** Input of the New Stack dialog → `gh stack init` arguments. */
data class NewStackRequest(val base: String, val branchesText: String) {
    val branches: List<String> get() = branchesText.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun validationError(): String? {
        val names = branches
        if (names.isEmpty()) return "Enter at least one branch name"
        names.firstNotNullOfOrNull { b -> BranchNames.validationError(b)?.let { "$b: $it" } }?.let { return it }
        names.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let { return "\"${it.key}\" is listed twice" }
        if (base.isNotBlank() && base.trim() in names) return "The base branch can't also be a stack branch"
        return null
    }

    fun args(): List<String> = buildList {
        add("init")
        base.trim().takeIf { it.isNotEmpty() }?.let {
            add("--base")
            add(it)
        }
        addAll(branches)
    }
}
