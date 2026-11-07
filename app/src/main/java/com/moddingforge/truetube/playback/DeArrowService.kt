package com.moddingforge.truetube.playback

import android.util.Log
import com.moddingforge.truetube.data.SettingsRepository
import com.moddingforge.truetube.web.BridgeCommand
import com.moddingforge.truetube.web.WebViewHolder
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * DeArrow (dearrow.ajay.app): replaces clickbait titles with community-submitted ones.
 * Lookups use the hash-prefix endpoint, so only 4 hex chars of sha256(videoId) leave the device,
 * and one request covers every video sharing that prefix. Results are cached for the session.
 */
class DeArrowService(
    private val holders: List<WebViewHolder>,
    private val settings: SettingsRepository,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    @Serializable private data class Title(val title: String, val original: Boolean = false, val votes: Int = 0, val locked: Boolean = false)
    @Serializable private data class Branding(val titles: List<Title> = emptyList())

    /** videoId -> title ("" = no better title). */
    private val cache = ConcurrentHashMap<String, String>()
    private val prefixDone = ConcurrentHashMap.newKeySet<String>()
    private val limit = Semaphore(3)

    init {
        for (h in holders) h.onVideoIds = { ids -> lookup(h, ids) }
    }

    private fun lookup(holder: WebViewHolder, ids: List<String>) {
        if (!settings.current.deArrow) return
        scope.launch {
            val byPrefix = ids.groupBy(::prefix)
            for ((p, group) in byPrefix) {
                if (p !in prefixDone) limit.withPermit { fetchPrefix(p) }
                val found = group.mapNotNull { id -> cache[id]?.takeIf { it.isNotEmpty() }?.let { id to it } }.toMap()
                if (found.isNotEmpty()) withContext(Dispatchers.Main) { holder.send(BridgeCommand.DearrowTitles(found)) }
            }
        }
    }

    private suspend fun fetchPrefix(p: String) = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("https://sponsor.ajay.app/api/branding/$p").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "TrueTube")
            }
            try {
                if (conn.responseCode != 200) return@runCatching
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val map = json.decodeFromString(MapSerializer(String.serializer(), Branding.serializer()), body)
                for ((id, b) in map) cache[id] = bestTitle(b) ?: ""
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "DeArrow $p failed: ${it.message}") }
        prefixDone += p
    }

    /** DeArrow's rule of thumb: trust locked titles, otherwise a non-negative vote count; skip "original". */
    private fun bestTitle(b: Branding): String? =
        b.titles.firstOrNull { !it.original && (it.locked || it.votes >= 0) }
            ?.title
            // DeArrow marks auto-formatted words with '>' (e.g. ">10 tips"), which is display-only.
            ?.replace(Regex("""(^|\s)>(\S)"""), "$1$2")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun prefix(id: String) =
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(4)

    private companion object {
        const val TAG = "TT.DeArrow"
    }
}
