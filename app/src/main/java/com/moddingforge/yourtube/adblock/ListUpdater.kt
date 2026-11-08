package com.moddingforge.yourtube.adblock

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ListMeta(
    val etag: String? = null,
    val lastModified: String? = null,
    val fetchedAt: Long = 0,
    val sha1: String = "",
)

@Serializable
data class UpdateMeta(
    val lastCheck: Long = 0,
    /** Last time at least one list changed. */
    val lastChange: Long = 0,
    val lists: Map<String, ListMeta> = emptyMap(),
)

data class UpdateResult(val checked: Int, val changed: Int, val notModified: Int, val failed: Int) {
    override fun toString() = "checked=$checked changed=$changed notModified=$notModified failed=$failed"
}

/**
 * Downloads newer versions of the filter lists from their upstream URLs (the ones in Brave's
 * catalog). Uses ETag / Last-Modified so unchanged lists cost a 304. Downloaded lists live in
 * [dir] and take precedence over the bundled snapshot.
 *
 * Scriptlet resources are NOT updated here: building them needs uBO's JS modules (see
 * tools/adblock/build-snapshot.mjs), so they are refreshed with app updates.
 */
class ListUpdater(
    private val json: Json,
    private val dir: File,
    private val metaFile: File,
) {
    @Volatile var meta: UpdateMeta = readMeta()
        private set

    /** Downloaded version of [url], or null if we only have the snapshot copy. */
    fun fileFor(url: String): File? = File(dir, fileName(url)).takeIf { it.isFile }

    /** Drops all downloads (e.g. when a newer snapshot ships with the app). */
    fun clear() {
        dir.deleteRecursively()
        metaFile.delete()
        meta = UpdateMeta()
    }

    /**
     * @param lists url -> file currently used for it (download or snapshot), to detect real changes.
     */
    suspend fun update(lists: Map<String, File>): UpdateResult = withContext(Dispatchers.IO) {
        dir.mkdirs()
        var changed = 0
        var notModified = 0
        var failed = 0
        val newMeta = meta.lists.toMutableMap()
        val now = System.currentTimeMillis()

        for ((url, current) in lists) {
            try {
                val old = newMeta[url]
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "YourTube-filter-updater")
                    // Only send validators if we actually hold the downloaded file they belong to.
                    if (fileFor(url) != null) {
                        old?.etag?.let { setRequestProperty("If-None-Match", it) }
                        old?.lastModified?.let { setRequestProperty("If-Modified-Since", it) }
                    }
                }
                try {
                    when (val code = conn.responseCode) {
                        HttpURLConnection.HTTP_NOT_MODIFIED -> notModified++
                        HttpURLConnection.HTTP_OK -> {
                            val text = conn.inputStream.bufferedReader().use { it.readText() }.replace("\r", "")
                            require(looksLikeFilterList(text)) { "response does not look like a filter list" }
                            val sha = sha1(text)
                            val currentSha = old?.sha1?.takeIf { fileFor(url) != null } ?: sha1(current.readText())
                            if (sha != currentSha) {
                                val target = File(dir, fileName(url))
                                val tmp = File(dir, target.name + ".tmp")
                                tmp.writeText(text)
                                check(tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))) { "rename failed" }
                                changed++
                            } else {
                                notModified++
                            }
                            newMeta[url] = ListMeta(conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"), now, sha)
                        }
                        else -> error("HTTP $code")
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                failed++
                Log.w(TAG, "update failed for $url: ${e.message}")
            }
        }

        meta = UpdateMeta(lastCheck = now, lastChange = if (changed > 0) now else meta.lastChange, lists = newMeta)
        writeMeta(meta)
        UpdateResult(lists.size, changed, notModified, failed)
    }

    private fun looksLikeFilterList(text: String): Boolean {
        val head = text.trimStart().take(200).lowercase()
        return text.isNotBlank() && !head.startsWith("<!doctype") && !head.startsWith("<html")
    }

    private fun readMeta(): UpdateMeta =
        metaFile.takeIf { it.isFile }?.let { runCatching { json.decodeFromString(UpdateMeta.serializer(), it.readText()) }.getOrNull() }
            ?: UpdateMeta()

    private fun writeMeta(m: UpdateMeta) {
        metaFile.parentFile?.mkdirs()
        metaFile.writeText(json.encodeToString(UpdateMeta.serializer(), m))
    }

    companion object {
        private const val TAG = "TT.Adblock"

        fun fileName(url: String) = sha1(url).take(16) + ".txt"

        fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
