package com.moddingforge.truetube.web

import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.moddingforge.truetube.BuildConfig

/** Fullscreen (onShowCustomView) and file chooser follow in M5. */
class TrueChromeClient(private val holder: WebViewHolder) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        holder.onProgress(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        holder.onTitle(title.orEmpty())
    }

    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        // Only surface our own script output in logcat; YouTube is very chatty.
        val text = message.message()
        if (BuildConfig.DEBUG && text.startsWith("[TrueTube]")) {
            val priority = when (message.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                else -> Log.DEBUG
            }
            Log.println(priority, "TT.JS", "$text (${message.sourceId()}:${message.lineNumber()})")
        }
        return true
    }
}
