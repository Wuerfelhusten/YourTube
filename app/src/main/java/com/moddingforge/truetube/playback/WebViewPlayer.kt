package com.moddingforge.truetube.playback

import android.os.Looper
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.moddingforge.truetube.web.BridgeCommand
import com.moddingforge.truetube.web.VideoState
import com.moddingforge.truetube.web.WebViewHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A Media3 [Player] whose "playback" is the YouTube player inside the player WebView.
 * It mirrors [WebViewHolder.video] (reported by 30-player.js) and forwards play/pause/seek
 * from the notification, lock screen, Bluetooth buttons and PiP back to the page.
 */
@UnstableApi
class WebViewPlayer(private val holder: WebViewHolder) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var video = VideoState()
    private var videoAt = SystemClock.elapsedRealtime()

    init {
        scope.launch {
            holder.video.collect {
                video = it
                videoAt = SystemClock.elapsedRealtime()
                invalidateState()
            }
        }
    }

    private fun positionMs(): Long {
        val base = (video.position * 1000).toLong()
        val running = if (video.playing) SystemClock.elapsedRealtime() - videoAt else 0L
        val duration = (video.duration * 1000).toLong()
        val pos = base + running
        return if (duration > 0) pos.coerceAtMost(duration) else pos
    }

    override fun getState(): State {
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
            )
            .build()

        if (video.videoId.isEmpty()) {
            return State.Builder()
                .setAvailableCommands(commands)
                .setPlaybackState(Player.STATE_IDLE)
                .build()
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(video.title)
            .setArtist(video.channel)
            .setArtworkUri("https://i.ytimg.com/vi/${video.videoId}/hqdefault.jpg".toUri())
            .build()
        val item = MediaItemData.Builder(video.videoId)
            .setMediaItem(MediaItem.Builder().setMediaId(video.videoId).setMediaMetadata(metadata).build())
            .setDurationUs(if (video.duration > 0) (video.duration * 1_000_000).toLong() else androidx.media3.common.C.TIME_UNSET)
            .setIsSeekable(video.duration > 0)
            .build()

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(video.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs { positionMs() }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        holder.send(BridgeCommand.PlayerControl(if (playWhenReady) "play" else "pause"))
        // Optimistic update so the notification reacts immediately; the page confirms shortly.
        video = video.copy(playing = playWhenReady, position = positionMs() / 1000.0)
        videoAt = SystemClock.elapsedRealtime()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        holder.send(BridgeCommand.PlayerControl("seek", positionMs / 1000.0))
        video = video.copy(position = positionMs / 1000.0)
        videoAt = SystemClock.elapsedRealtime()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        holder.send(BridgeCommand.PlayerControl("pause"))
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }
}
