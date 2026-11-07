package com.moddingforge.truetube.web

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

    /** Player: play | pause | toggle */
    @Serializable @SerialName("player-control")
    data class PlayerControl(val action: String) : BridgeCommand

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
        /** Name of the global object the page uses: TrueTubeNative.postMessage(...) */
        const val JS_OBJECT = "TrueTubeNative"
    }
}
