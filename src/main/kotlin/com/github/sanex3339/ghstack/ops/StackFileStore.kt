package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.model.RemovalState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/** Raw access to gh-stack's files in the git dir. The only write is restoring a snapshot taken moments earlier. */
object StackFileStore {
    const val STACK_FILE = "gh-stack"
    const val REBASE_STATE_FILE = "gh-stack-rebase-state"
    const val MODIFY_STATE_FILE = "gh-stack-modify-state"
    const val REMOVAL_STATE_FILE = RemovalState.FILE_NAME

    fun read(gitDir: Path, name: String = STACK_FILE): ByteArray? =
        gitDir.resolve(name).takeIf { it.isRegularFile() }?.readBytes()

    /** Puts back bytes returned by [read] (deleting the file when the snapshot was `null`), atomically. */
    fun restore(gitDir: Path, snapshot: ByteArray?) {
        val target = gitDir.resolve(STACK_FILE)
        if (snapshot == null) {
            target.deleteIfExists()
            return
        }
        val temp = Files.createTempFile(gitDir, "gh-stack", ".restore")
        temp.writeBytes(snapshot)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Writes one of the plugin's own files (never gh-stack's). */
    fun writeOwn(gitDir: Path, name: String, text: String) {
        require(name.startsWith("ghstack-plugin-")) { "not a plugin-owned file: $name" }
        gitDir.resolve(name).writeText(text)
    }

    fun deleteOwn(gitDir: Path, name: String) {
        require(name.startsWith("ghstack-plugin-")) { "not a plugin-owned file: $name" }
        gitDir.resolve(name).deleteIfExists()
    }
}
