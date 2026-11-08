package com.moddingforge.yourtube.web

import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Messages sent from the injected JS to the app. `type` is the JSON discriminator. */
@Serializable
sealed interface BridgeMessage {
    @Serializable @SerialName("ready")
    data class Ready(val version: Int, val url: String) : BridgeMessage

    /** YouTube session of the page: signed in, and the active brand channel (empty = personal channel). */
    @Serializable @SerialName("session")
    data class Session(val loggedIn: Boolean = false, val pageId: String = "") : BridgeMessage

    @Serializable @SerialName("navigation")
    data class Navigation(val url: String, val title: String = "") : BridgeMessage

    @Serializable @SerialName("pong")
    data class Pong(val nonce: Long) : BridgeMessage

    @Serializable @SerialName("log")
    data class Log(val level: String = "info", val message: String) : BridgeMessage

    /** New CSS classes/ids on the page, for generic cosmetic filtering. */
    @Serializable @SerialName("cosmetic-classids")
    data class CosmeticClassIds(val host: String, val classes: List<String> = emptyList(), val ids: List<String> = emptyList()) : BridgeMessage

    /** Feed: a watch link was tapped - open it in the player WebView. */
    @Serializable @SerialName("open-video")
    data class OpenVideo(val url: String) : BridgeMessage

    /** Player: a non-watch link (channel, hashtag ...) was tapped - open it in the feed WebView. */
    @Serializable @SerialName("open-in-feed")
    data class OpenInFeed(val url: String) : BridgeMessage

    /** Player: now-playing info for the mini player / media session. */
    @Serializable @SerialName("video-state")
    data class VideoStateMsg(
        val videoId: String = "",
        val title: String = "",
        val channel: String = "",
        val playing: Boolean = false,
        val position: Double = 0.0,
        val duration: Double = 0.0,
    ) : BridgeMessage

    /** Player: playback is inside a "button" segment (category, end) - or left it (category empty). */
    @Serializable @SerialName("sponsor-prompt")
    data class SponsorPrompt(val category: String = "", val end: Double = 0.0) : BridgeMessage

    /** Player: YouTube's own controls (and seekbar) became visible / hidden. */
    @Serializable @SerialName("player-controls")
    data class PlayerControls(val visible: Boolean = false) : BridgeMessage

    /** Player: a YouTube menu / bottom sheet is open over the page (gestures pause). */
    @Serializable @SerialName("player-menu")
    data class PlayerMenu(val open: Boolean = false) : BridgeMessage

    /** Player: a SponsorBlock segment was skipped. */
    @Serializable @SerialName("sponsor-skipped")
    data class SponsorSkipped(val category: String, val seconds: Double = 0.0) : BridgeMessage

    /** Video ids visible on the page (DeArrow lookups). */
    @Serializable @SerialName("video-ids")
    data class VideoIds(val ids: List<String>) : BridgeMessage

    /** navigator.share() from the page -> native share sheet. */
    @Serializable @SerialName("share")
    data class Share(val url: String = "", val title: String = "", val text: String = "") : BridgeMessage

    /** The "YourTube Settings" entry on YouTube's settings page was tapped. */
    @Serializable @SerialName("open-settings")
    data object OpenSettings : BridgeMessage

    /** Player: the page entered/left fullscreen (YouTube's fullscreen button). */
    @Serializable @SerialName("fullscreen")
    data class Fullscreen(val on: Boolean) : BridgeMessage

    /** The fallback script had to skip a video ad (blocking missed it). */
    @Serializable @SerialName("ad-skipped")
    data object AdSkipped : BridgeMessage
}

/** Commands sent from the app to the injected JS. */
@Serializable
sealed interface BridgeCommand {
    @Serializable @SerialName("ping")
    data class Ping(val nonce: Long) : BridgeCommand

    /** Switch tab via m.youtube's (hidden) pivot bar - fast in-page navigation. */
    @Serializable @SerialName("nav-tab")
    data class NavTab(val index: Int, val url: String) : BridgeCommand

    @Serializable @SerialName("scroll-top")
    data object ScrollTop : BridgeCommand

    /** Player: default quality ("auto" = YouTube decides) and speed for the current video. */
    @Serializable @SerialName("player-prefs")
    data class PlayerPrefs(
        val quality: String,
        val speed: Double,
        val originalAudio: Boolean = true,
        val noAutoCaptions: Boolean = false,
    ) : BridgeCommand

    /** Player tools sheet: loop on/off, set speed, set quality now (not as default). */
    /** Player: open the comments and scroll to [commentId] ("parent" or "parent.reply"), from an lc= link. */
    @Serializable @SerialName("open-comment")
    data class OpenComment(val videoId: String, val commentId: String) : BridgeCommand

    @Serializable @SerialName("player-tool")
    data class PlayerTool(val action: String, val value: String = "") : BridgeCommand

    @Serializable
    data class Segment(
        val start: Double,
        val end: Double,
        val category: String,
        /** auto | button | show */
        val action: String = "auto",
        /** Skip only the first time it is reached. */
        val once: Boolean = true,
    )

    /** Player: SponsorBlock segments to skip for [videoId]. */
    @Serializable @SerialName("sponsor-segments")
    data class SponsorSegments(val videoId: String, val segments: List<Segment>, val marks: Boolean = true) : BridgeCommand

    /** Player: Return YouTube Dislike count for [videoId]. */
    @Serializable @SerialName("dislikes")
    data class Dislikes(val videoId: String, val count: Long) : BridgeCommand

    /** DeArrow: community titles by video id. */
    @Serializable @SerialName("dearrow-titles")
    data class DearrowTitles(val titles: Map<String, String>) : BridgeCommand

    /** CSS from YourTube Settings (hide comments, custom CSS). Empty = remove. */
    @Serializable @SerialName("user-css")
    data class UserCss(val css: String) : BridgeCommand

    /** Player: play | pause | toggle | seek (with [position] in seconds) */
    @Serializable @SerialName("player-control")
    data class PlayerControl(val action: String, val position: Double = 0.0) : BridgeCommand

    /** Player: enter/leave YouTube's fullscreen layout (native rotation, Back, swipe). */
    @Serializable @SerialName("player-fullscreen")
    data class PlayerFullscreen(val on: Boolean) : BridgeCommand

    /** Player: switch the page into mini layout (only the video, filling the small WebView). */
    @Serializable @SerialName("player-mini")
    data class PlayerMini(val on: Boolean) : BridgeCommand

    /** Generic hide selectors the engine returned for reported classes/ids. */
    @Serializable @SerialName("cosmetic-hide")
    data class CosmeticHide(val selectors: List<String>) : BridgeCommand
}

/**
 * JS <-> Kotlin channel based on WebMessageListener. Unlike addJavascriptInterface it is only
 * exposed to the allowed YouTube origins, not to every page or iframe.
 */
class Bridge(
    private val json: Json,
    private val onMessage: (BridgeMessage) -> Unit,
) {
    private var proxy: JavaScriptReplyProxy? = null

    fun install(webView: WebView): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(TAG, "WEB_MESSAGE_LISTENER not supported - bridge disabled")
            return false
        }
        WebViewCompat.addWebMessageListener(webView, JS_OBJECT, ScriptInjector.ORIGINS) { _, message, origin, isMainFrame, replyProxy ->
            if (!isMainFrame) return@addWebMessageListener
            proxy = replyProxy
            val data = message.data ?: return@addWebMessageListener
            val msg = try {
                json.decodeFromString(BridgeMessage.serializer(), data)
            } catch (e: Exception) {
                Log.w(TAG, "Unparseable message from $origin: ${data.take(200)}", e)
                return@addWebMessageListener
            }
            onMessage(msg)
        }
        return true
    }

    /** Sends a command to the current main-frame document. No-op until the page said "ready". */
    fun send(command: BridgeCommand) {
        val p = proxy ?: return
        p.postMessage(json.encodeToString(BridgeCommand.serializer(), command))
    }

    /** Called when a new document starts loading; the old reply proxy is stale then. */
    fun reset() {
        proxy = null
    }

    companion object {
        const val TAG = "TT.Bridge"
        /** Name of the global object the page uses: YourTubeNative.postMessage(...) */
        const val JS_OBJECT = "YourTubeNative"
    }
}
