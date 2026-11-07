package com.moddingforge.truetube.web

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlayerMode { HIDDEN, EXPANDED, MINI }

/** Orientation the activity should force; NONE = follow the sensor / system setting. */
enum class OrientationLock { NONE, LANDSCAPE, PORTRAIT }

/**
 * Coordinates the feed and the player WebView like the official app:
 * a tapped video opens the full-screen player; swiping it down (or Back) turns it into the
 * mini player above the tab bar while the feed stays usable; links that leave the watch page
 * (channel, hashtag ...) open in the feed and minimize the player.
 *
 * Fullscreen follows the official app too: rotating to landscape, YouTube's fullscreen button
 * or swiping up on the video enters it; rotating back, the button, Back or swiping down leaves it.
 * When the user toggles it explicitly, the orientation is forced until the phone is physically
 * turned that way (then the sensor takes over again, see MainActivity).
 */
class PlayerController(
    val feed: WebViewHolder,
    val player: WebViewHolder,
) {
    private val _mode = MutableStateFlow(PlayerMode.HIDDEN)
    val mode: StateFlow<PlayerMode> = _mode.asStateFlow()

    private val _fullscreen = MutableStateFlow(false)
    val fullscreen: StateFlow<Boolean> = _fullscreen.asStateFlow()

    private val _orientationLock = MutableStateFlow(OrientationLock.NONE)
    val orientationLock: StateFlow<OrientationLock> = _orientationLock.asStateFlow()

    init {
        feed.onOpenVideo = ::openVideo
        player.onOpenInFeed = { url ->
            setFullscreen(false, explicit = true)
            feed.load(url)
            minimize()
        }
        player.onFullscreen = { on -> setFullscreen(on, explicit = true) }
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
        Log.i(TAG, "expand (was ${_mode.value})")
        _mode.value = PlayerMode.EXPANDED
        player.miniMode = false
    }

    fun minimize() {
        Log.i(TAG, "minimize (was ${_mode.value})")
        if (_mode.value != PlayerMode.EXPANDED) return
        if (_fullscreen.value) setFullscreen(false, explicit = true)
        _mode.value = PlayerMode.MINI
        player.miniMode = true
    }

    fun close() {
        Log.i(TAG, "close")
        if (_fullscreen.value) setFullscreen(false, explicit = true)
        _mode.value = PlayerMode.HIDDEN
        player.clear()
    }

    fun togglePlay() = player.send(BridgeCommand.PlayerControl("toggle"))

    /**
     * @param explicit the user asked for it (button, swipe, Back) -> force the orientation;
     *   otherwise it follows a rotation that already happened.
     */
    fun setFullscreen(on: Boolean, explicit: Boolean) {
        if (on && _mode.value != PlayerMode.EXPANDED) return
        if (explicit) _orientationLock.value = if (on) OrientationLock.LANDSCAPE else OrientationLock.PORTRAIT
        if (_fullscreen.value == on) return
        Log.i(TAG, "fullscreen $on (${if (explicit) "explicit" else "rotation"})")
        _fullscreen.value = on
        player.fullscreenMode = on
    }

    /** The screen actually rotated. */
    fun onRotated(landscape: Boolean) {
        if (_mode.value == PlayerMode.EXPANDED || !landscape) setFullscreen(landscape, explicit = false)
    }

    /** The phone is physically held the way the lock forced it: let the sensor decide again. */
    fun releaseOrientation() {
        if (_orientationLock.value != OrientationLock.NONE) Log.i(TAG, "orientation released")
        _orientationLock.value = OrientationLock.NONE
    }

    private companion object {
        const val TAG = "TT.Player"
    }
}
