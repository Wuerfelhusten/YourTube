package com.moddingforge.yourtube.update

import android.content.Context

/** One `## <version>` section of CHANGELOG.md. */
data class ChangelogEntry(val version: String, val text: String) {
    val code: Int get() = Changelog.versionCode(version)
}

/**
 * CHANGELOG.md (bundled into the APK by the `changelogAsset` Gradle task):
 * sections start with `## <MAJOR.MINOR.PATCH>` (anything after the version, e.g. a date, is
 * ignored); everything before the first section is the file's own introduction.
 */
object Changelog {
    private val heading = Regex("""^##\s+v?(\d+\.\d+\.\d+)\b.*$""")

    fun parse(markdown: String): List<ChangelogEntry> {
        val entries = mutableListOf<ChangelogEntry>()
        var version: String? = null
        val body = StringBuilder()
        fun flush() {
            version?.let { entries += ChangelogEntry(it, body.toString().trim()) }
            body.clear()
        }
        for (line in markdown.lines()) {
            val m = heading.matchEntire(line.trim())
            if (m != null) {
                flush()
                version = m.groupValues[1]
            } else if (version != null) {
                body.appendLine(line)
            }
        }
        flush()
        return entries.sortedByDescending { it.code }
    }

    fun load(context: Context): List<ChangelogEntry> =
        runCatching { context.assets.open("CHANGELOG.md").bufferedReader().use { parse(it.readText()) } }.getOrDefault(emptyList())

    /** Entries newer than [sinceCode] up to and including [untilCode] (newest first). */
    fun between(entries: List<ChangelogEntry>, sinceCode: Int, untilCode: Int) =
        entries.filter { it.code in (sinceCode + 1)..untilCode }

    /** Same formula as app/build.gradle.kts: MAJOR * 1_000_000 + MINOR * 1_000 + PATCH. */
    fun versionCode(version: String): Int {
        val p = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        return p.getOrElse(0) { 0 } * 1_000_000 + p.getOrElse(1) { 0 } * 1_000 + p.getOrElse(2) { 0 }
    }

    /** Markdown subset for display: list markers become bullets, emphasis markers are dropped. */
    fun plain(text: String): String = text.lines().joinToString("\n") { line ->
        val t = line.trimEnd()
        val bullet = Regex("""^(\s*)[-*]\s+(.*)$""").matchEntire(t)
        val s = if (bullet != null) bullet.groupValues[1] + "•  " + bullet.groupValues[2] else t
        s.replace("**", "").replace("__", "").replace("`", "")
            .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1") // [text](url) -> text
    }.trim()
}
