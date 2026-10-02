package com.github.sanex3339.ghstack.state

import com.github.sanex3339.ghstack.cli.CliStatus
import com.github.sanex3339.ghstack.model.OperationState
import com.github.sanex3339.ghstack.model.RepoCoordinates
import com.github.sanex3339.ghstack.model.StackUi
import com.github.sanex3339.ghstack.ops.RepoSnapshot
import java.nio.file.Path

/** Everything the UI shows for one git repository. Immutable; the state service replaces it on refresh. */
data class RepoState(
    val root: Path,
    val gitDir: Path?,
    val cliStatus: CliStatus?,
    val stacks: List<StackUi>,
    val currentBranch: String?,
    val repository: RepoCoordinates?,
    val fallbackMode: Boolean,
    val operation: OperationState,
    val stacksUnavailable: Boolean,
) {
    val currentStack: StackUi? get() = stacks.firstOrNull { it.isCurrent }

    val ready: Boolean get() = cliStatus is CliStatus.Ready && gitDir != null

    fun snapshot(): RepoSnapshot? {
        val stack = currentStack ?: return null
        val branch = currentBranch ?: return null
        return RepoSnapshot(stack, branch, repository, operation)
    }

    companion object {
        fun initial(root: Path) = RepoState(
            root = root,
            gitDir = null,
            cliStatus = null,
            stacks = emptyList(),
            currentBranch = null,
            repository = null,
            fallbackMode = false,
            operation = OperationState.None,
            stacksUnavailable = false,
        )
    }
}
