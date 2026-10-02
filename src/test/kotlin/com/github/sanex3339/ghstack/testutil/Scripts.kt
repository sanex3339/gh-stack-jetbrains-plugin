package com.github.sanex3339.ghstack.testutil

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

object Scripts {
    /** Writes an executable `/bin/sh` script named [name] into a fresh temp directory. */
    fun executable(name: String, body: String): Path {
        val file = Files.createTempDirectory("ghstack-script").resolve(name)
        file.writeText("#!/bin/sh\n" + body.trimIndent() + "\n")
        file.toFile().setExecutable(true)
        return file
    }
}
