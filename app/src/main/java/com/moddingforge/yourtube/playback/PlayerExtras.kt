package com.moddingforge.yourtube.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.moddingforge.yourtube.data.AppSettings
import com.moddingforge.yourtube.data.SettingsRepository
import com.moddingforge.yourtube.web.BridgeCommand
import com.moddingforge.yourtube.web.WebViewHolder
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Player extras for the video in the player WebView (docs/ARCHITECTURE.md 7.5):
 *  - default quality (separately for Wi-Fi / mobile data) and default speed
 *  - SponsorBlock: segments via the privacy-preserving hash-prefix API, skipped in the page
 *  - Return YouTube Dislike: dislike count on the dislike pill
 */
class PlayerExtras(
    private val context: Context,
    private val player: WebViewHolder,
    val settings: SettingsRepository,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    @Serializable
    private data class SbVideo(val videoID: String, val segments: List<SbSegment> = emptyList())

    @Serializable
    private data class SbSegment(val segment: List<Double>, val category: String, val actionType: String = "skip")

    @Serializable
    private data class Ryd(val id: String = "", val dislikes: Long = 0)

    /** SponsorBlock segments of the current video, for the native segment strip. */
    val segments = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, List<BridgeCommand.Segment>>>("" to emptyList())

    private var sbKey = ""
    private var rydKey = ""

    init {
        scope.launch(Dispatchers.Main) {
            combine(player.video.map { it.videoId }.distinctUntilChanged(), settings.settings) { id, s -> id to s }
                .collect { (id, s) ->
                    if (id.isEmpty()) return@collect
                    player.sendSticky(BridgeCommand.PlayerPrefs(quality = quality(s), speed = s.speed.toDouble(), originalAudio = s.originalAudio, noAutoCaptions = s.noAutoCaptions))
                    sponsorBlock(id, s)
                    dislikes(id, s)
                }
        }
    }

    private fun quality(s: AppSettings): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        return if (unmetered) s.qualityWifi else s.qualityMobile
    }

    private fun sponsorBlock(videoId: String, s: AppSettings) {
        val active = s.sponsorModes.filterValues { it != "off" }
        val key = "$videoId|${s.sponsorBlock}|${active.toSortedMap()}|${s.sponsorMinLength}|${s.sponsorSkipOnce}|${s.sponsorStrip}"
        if (key == sbKey) return
        sbKey = key
        if (!s.sponsorBlock || active.isEmpty()) {
            player.sendSticky(BridgeCommand.SponsorSegments(videoId, emptyList()))
            segments.value = videoId to emptyList()
            return
        }
        scope.launch {
            val segments = runCatching { fetchSegments(videoId, active.keys) }
                .onFailure { Log.w(TAG, "SponsorBlock failed: ${it.message}") }
                .getOrDefault(emptyList())
                .filter { it.end - it.start >= s.sponsorMinLength }
                .map { it.copy(action = active[it.category] ?: "show", once = s.sponsorSkipOnce) }
            Log.i(TAG, "SponsorBlock $videoId: ${segments.size} segments")
            withContext(Dispatchers.Main) {
                if (player.video.value.videoId == videoId) {
                    player.sendSticky(BridgeCommand.SponsorSegments(videoId, segments, marks = s.sponsorStrip))
                    this@PlayerExtras.segments.value = videoId to segments
                }
            }
        }
    }

    /** k-anonymity: only the first 4 hex chars of sha256(videoId) leave the device. */
    private suspend fun fetchSegments(videoId: String, categories: Set<String>): List<BridgeCommand.Segment> = withContext(Dispatchers.IO) {
        val prefix = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray()).joinToString("") { "%02x".format(it) }.take(4)
        val cats = URLEncoder.encode(categories.joinToString(",", "[", "]") { "\"$it\"" }, "UTF-8")
        val body = get("https://sponsor.ajay.app/api/skipSegments/$prefix?categories=$cats") ?: return@withContext emptyList()
        json.decodeFromString(ListSerializer(SbVideo.serializer()), body)
            .firstOrNull { it.videoID == videoId }?.segments.orEmpty()
            .filter { it.actionType == "skip" && it.segment.size == 2 }
            .map { BridgeCommand.Segment(it.segment[0], it.segment[1], it.category) }
    }

    private fun dislikes(videoId: String, s: AppSettings) {
        val key = "$videoId|${s.returnDislikes}"
        if (key == rydKey) return
        rydKey = key
        if (!s.returnDislikes) return
        scope.launch {
            val count = runCatching {
                withContext(Dispatchers.IO) {
                    get("https://returnyoutubedislikeapi.com/votes?videoId=" + URLEncoder.encode(videoId, "UTF-8"))
                        ?.let { json.decodeFromString(Ryd.serializer(), it).dislikes }
                }
            }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                if (player.video.value.videoId == videoId) player.sendSticky(BridgeCommand.Dislikes(videoId, count))
            }
        }
    }

    /** null for 404 (SponsorBlock: no segments in this bucket). */
    private fun get(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "YourTube")
        }
        return try {
            when (conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> error("HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TAG = "TT.Extras"
    }
}
