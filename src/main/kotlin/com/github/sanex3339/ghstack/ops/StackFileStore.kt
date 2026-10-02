package com.github.sanex3339.ghstack.ops

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/** Raw access to gh-stack's files in the git dir. The only write is restoring a snapshot taken moments earlier. */
object StackFileStore {
    const val STACK_FILE = "gh-stack"
    const val REBASE_STATE_FILE = "gh-stack-rebase-state"
    const val MODIFY_STATE_FILE = "gh-stack-modify-state"

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
}
