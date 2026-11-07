package com.moddingforge.truetube.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlayerMode { HIDDEN, EXPANDED, MINI }

/**
 * Coordinates the feed and the player WebView like the official app:
 * a tapped video opens the full-screen player; swiping it down (or Back) turns it into the
 * mini player above the tab bar while the feed stays usable; links that leave the watch page
 * (channel, hashtag ...) open in the feed and minimize the player.
 */
class PlayerController(
    val feed: WebViewHolder,
    val player: WebViewHolder,
) {
    private val _mode = MutableStateFlow(PlayerMode.HIDDEN)
    val mode: StateFlow<PlayerMode> = _mode.asStateFlow()

    init {
        feed.onOpenVideo = ::openVideo
        player.onOpenInFeed = { url ->
            feed.load(url)
            minimize()
        }
    }

    /** Entry point for URLs from outside (intents, debug): watch pages go to the player. */
    fun navigate(url: String) {
        if (Page.of(url) == Page.WATCH) openVideo(url) else feed.load(url)
    }

    fun openVideo(url: String) {
        player.load(url)
        expand()
    }

    fun expand() {
        android.util.Log.i("TT.Player", "expand (was ${_mode.value})")
        _mode.value = PlayerMode.EXPANDED
        player.miniMode = false
    }

    fun minimize() {
        android.util.Log.i("TT.Player", "minimize (was ${_mode.value})")
        if (_mode.value != PlayerMode.EXPANDED) return
        _mode.value = PlayerMode.MINI
        player.miniMode = true
    }

    fun close() {
        android.util.Log.i("TT.Player", "close")
        _mode.value = PlayerMode.HIDDEN
        player.clear()
    }

    fun togglePlay() = player.send(BridgeCommand.PlayerControl("toggle"))
}
