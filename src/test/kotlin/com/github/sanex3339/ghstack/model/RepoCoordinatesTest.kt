package com.github.sanex3339.ghstack.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RepoCoordinatesTest {
    @Test
    fun `reads owner and name from the usual remote URL forms`() {
        val expected = RepoCoordinates("github.com", "octo", "app")
        listOf(
            "https://github.com/octo/app.git",
            "https://github.com/octo/app",
            "https://user@github.com/octo/app.git/",
            "git@github.com:octo/app.git",
            "ssh://git@github.com/octo/app.git",
            "ssh://git@github.com:22/octo/app",
            "git://github.com/octo/app.git",
        ).forEach { assertEquals(expected, RepoCoordinates.fromRemoteUrl(it), it) }
        assertEquals(RepoCoordinates("ghe.example.com", "team", "svc.api"), RepoCoordinates.fromRemoteUrl("git@ghe.example.com:team/svc.api.git"))
    }

    @Test
    fun `local paths and odd URLs give nothing`() {
        assertNull(RepoCoordinates.fromRemoteUrl("/tmp/origin.git"))
        assertNull(RepoCoordinates.fromRemoteUrl("file:///tmp/origin.git"))
        assertNull(RepoCoordinates.fromRemoteUrl("https://github.com/octo"))
        assertNull(RepoCoordinates.fromRemoteUrl(""))
    }
}
