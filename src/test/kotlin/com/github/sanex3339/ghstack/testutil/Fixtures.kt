package com.github.sanex3339.ghstack.testutil

object Fixtures {
    fun bytes(name: String): ByteArray =
        requireNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }.use { it.readBytes() }

    fun text(name: String): String = bytes(name).decodeToString()
}
