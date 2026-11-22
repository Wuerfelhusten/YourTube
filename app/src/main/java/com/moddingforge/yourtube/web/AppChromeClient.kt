package com.moddingforge.yourtube.web

import android.graphics.Bitmap
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.moddingforge.yourtube.BuildConfig

/** Fullscreen (onShowCustomView) and file chooser follow in M5. */
class AppChromeClient(private val holder: WebViewHolder) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        holder.onProgress(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        holder.onTitle(title.orEmpty())
    }

    /**
     * WebView draws a grey box with a large pixelated play icon over a <video> that has no poster
     * yet (while a video loads). Transparent instead: the page's own thumbnail / our poster shows.
     */
    override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    /**
     * DRM (Widevine) for free movies / protected videos: WebView denies EME unless the app grants
     * RESOURCE_PROTECTED_MEDIA_ID. Only that resource, only for YouTube's own pages - camera,
     * microphone etc. stay denied.
     */
    override fun onPermissionRequest(request: PermissionRequest) {
        val host = request.origin?.host.orEmpty()
        val youtube = host == "youtube.com" || host.endsWith(".youtube.com")
        val drm = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
        if (youtube && drm.isNotEmpty()) {
            Log.i(TAG, "granting protected media (DRM) to $host")
            request.grant(drm.toTypedArray())
        } else {
            Log.i(TAG, "denying ${request.resources.joinToString()} for $host")
            request.deny()
        }
    }

    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        // Only surface our own script output in logcat; YouTube is very chatty.
        val text = message.message()
        if (BuildConfig.DEBUG && text.startsWith("[YourTube]")) {
            val priority = when (message.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                else -> Log.DEBUG
            }
            Log.println(priority, "TT.JS", "$text (${message.sourceId()}:${message.lineNumber()})")
        }
        return true
    }

    private companion object {
        const val TAG = "TT.Chrome"
    }
}
