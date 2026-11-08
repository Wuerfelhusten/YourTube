package com.moddingforge.yourtube.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.moddingforge.yourtube.data.SettingsRepository
import com.moddingforge.yourtube.web.PlayerController
import com.moddingforge.yourtube.web.PlayerMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Connects to [PlaybackService] while a video is open (mini or expanded), which creates the
 * media session and lets Media3 show the notification / go foreground while playing.
 * Also keeps the player WebView running in the background during that time - unless
 * "Background playback" is switched off in YourTube Settings.
 */
@UnstableApi
class PlaybackConnector(
    private val context: Context,
    private val controller: PlayerController,
    settings: SettingsRepository,
    scope: CoroutineScope,
) {
    private var future: ListenableFuture<MediaController>? = null

    init {
        scope.launch(Dispatchers.Main) {
            combine(controller.mode, settings.settings) { mode, s -> (mode != PlayerMode.HIDDEN) to s.backgroundPlayback }
                .distinctUntilChanged()
                .collect { (active, background) ->
                    controller.player.keepRunningInBackground = active && background
                    if (active) connect() else disconnect()
                }
        }
    }

    private fun connect() {
        if (future != null) return
        Log.i(TAG, "connecting to PlaybackService")
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        future = MediaController.Builder(context, token).buildAsync()
    }

    private fun disconnect() {
        val f = future ?: return
        future = null
        Log.i(TAG, "disconnecting from PlaybackService")
        MediaController.releaseFuture(f)
    }

    private companion object {
        const val TAG = "TT.Playback"
    }
}
