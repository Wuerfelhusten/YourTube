package com.moddingforge.truetube.web

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * WebView that can stay "visible" for Chromium while the app is in the background.
 *
 * Chromium suspends media as soon as the WebView reports its window as hidden (background media
 * playback is off on Android) - JS never even sees a pause() call. The page-level visibility
 * override in 30-player.js is not enough for that, so the player WebView simply doesn't forward
 * the hidden state while [keepVisible] is true (a video is open: background playback / PiP).
 */
class TrueWebView(context: Context) : WebView(context) {

    var keepVisible: () -> Boolean = { false }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.VISIBLE && keepVisible()) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }
}
