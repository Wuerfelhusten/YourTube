package com.moddingforge.yourtube.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogTest {
    private val md = """
        # Changelog

        Intro text that is not part of any version.

        ## 0.10.0 - 2026-12-01

        - New **thing**
        - See [docs](https://example.com)

        ## v0.9.2
        Fixes.

        ## 0.9.1
        - Older
    """.trimIndent()

    @Test fun parsesSectionsNewestFirst() {
        val entries = Changelog.parse(md)
        assertEquals(listOf("0.10.0", "0.9.2", "0.9.1"), entries.map { it.version })
        assertEquals("- New **thing**\n- See [docs](https://example.com)", entries[0].text)
        assertEquals("Fixes.", entries[1].text)
    }

    @Test fun versionCodeMatchesGradle() {
        assertEquals(1000, Changelog.versionCode("0.1.0"))
        assertEquals(1_002_003, Changelog.versionCode("1.2.3"))
        assertTrue(Changelog.versionCode("0.10.0") > Changelog.versionCode("0.9.99"))
    }

    @Test fun entriesBetweenVersions() {
        val entries = Changelog.parse(md)
        // Updated from 0.9.1 to 0.10.0: everything after 0.9.1.
        val shown = Changelog.between(entries, Changelog.versionCode("0.9.1"), Changelog.versionCode("0.10.0"))
        assertEquals(listOf("0.10.0", "0.9.2"), shown.map { it.version })
    }

    @Test fun plainTextForDisplay() {
        assertEquals("•  New thing\n•  See docs", Changelog.plain("- New **thing**\n- See [docs](https://example.com)"))
    }

    @Test fun repositoryChangelogHasCurrentVersion() {
        // The CHANGELOG.md in the repository must contain the version in gradle.properties.
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "CHANGELOG.md").exists() }
        val version = java.io.File(root, "gradle.properties").readLines()
            .first { it.startsWith("yourtube.versionName=") }.substringAfter('=').trim()
        val entries = Changelog.parse(java.io.File(root, "CHANGELOG.md").readText())
        assertTrue("CHANGELOG.md has no section for $version", entries.any { it.version == version && it.text.isNotBlank() })
    }
}
