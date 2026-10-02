package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.cli.StackCli
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** Where every local branch pointed (and the stack file) right before a stack operation. */
@Serializable
data class UndoSnapshot(
    val title: String,
    val takenAtMillis: Long,
    val branchTips: Map<String, String>,
    val currentBranch: String?,
    val stackFile: String?,
)

/**
 * One-level undo for local stack operations (rebase, insert, remove, move changes, sync…). It restores
 * branch positions and the stack file; anything already pushed to GitHub is not undone.
 */
class UndoWorkflow(private val cli: StackCli, private val gitDir: Path) {
    fun capture(title: String): UndoSnapshot = UndoSnapshot(
        title = title,
        takenAtMillis = System.currentTimeMillis(),
        branchTips = branchTips(),
        currentBranch = currentBranch().ifEmpty { null },
        stackFile = StackFileStore.read(gitDir)?.decodeToString(),
    )

    fun save(snapshot: UndoSnapshot) = StackFileStore.writeOwn(gitDir, FILE_NAME, json.encodeToString(UndoSnapshot.serializer(), snapshot))

    fun load(): UndoSnapshot? = StackFileStore.read(gitDir, FILE_NAME)?.let {
        try { json.decodeFromString(UndoSnapshot.serializer(), it.decodeToString()) } catch (e: IllegalArgumentException) { null }
    }

    fun undo() {
        val snapshot = load() ?: throw WorkflowAbort("There's nothing to undo")
        if (Files.isDirectory(gitDir.resolve("rebase-merge")) || Files.isDirectory(gitDir.resolve("rebase-apply"))) {
            throw WorkflowAbort("Finish or abort the rebase in progress before undoing")
        }
        val current = currentBranch()
        val now = branchTips()
        snapshot.branchTips.forEach { (branch, tip) ->
            when (now[branch]) {
                tip -> Unit
                null -> cli.git("branch", branch, tip).orAbort("Restoring $branch")
                else -> if (branch == current) {
                    cli.git("reset", "--keep", tip).orAbort("Restoring $branch")
                } else {
                    cli.git("branch", "-f", branch, tip).orAbort("Restoring $branch")
                }
            }
        }
        StackFileStore.restore(gitDir, snapshot.stackFile?.encodeToByteArray())
        val target = snapshot.currentBranch
        if (target != null && currentBranch() != target) cli.git("checkout", target).orAbort("Switching back to $target")
        // Branches the operation created (e.g. an inserted one) go away if they hold no commits of their own.
        (now.keys - snapshot.branchTips.keys).forEach { branch ->
            val tip = now.getValue(branch)
            if (branch != currentBranch() && snapshot.branchTips.values.any { cli.git("merge-base", "--is-ancestor", tip, it).ok }) {
                cli.git("branch", "-D", branch)
            }
        }
        StackFileStore.deleteOwn(gitDir, FILE_NAME)
    }

    private fun branchTips(): Map<String, String> =
        cli.git("for-each-ref", "--format=%(refname:short) %(objectname)", "refs/heads").stdout.lines()
            .mapNotNull { line -> line.trim().split(' ').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()

    private fun currentBranch(): String = cli.git("branch", "--show-current").stdout.trim()

    companion object {
        const val FILE_NAME = "ghstack-plugin-undo.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
