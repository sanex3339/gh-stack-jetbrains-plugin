package com.github.sanex3339.ghstack.model

/** Reads the checked-out branch straight from `.git/HEAD`: instant, unlike waiting for the IDE's Git state. */
object GitHead {
    private const val BRANCH_PREFIX = "ref: refs/heads/"

    /** The branch name, or `null` when HEAD is detached (e.g. mid-rebase) or the file is missing. */
    fun currentBranch(head: ByteArray?): String? {
        val text = head?.decodeToString()?.trim() ?: return null
        return text.takeIf { it.startsWith(BRANCH_PREFIX) }?.removePrefix(BRANCH_PREFIX)?.takeIf { it.isNotEmpty() }
    }
}
