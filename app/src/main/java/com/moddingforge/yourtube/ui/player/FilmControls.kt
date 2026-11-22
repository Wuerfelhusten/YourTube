package com.moddingforge.yourtube.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moddingforge.yourtube.web.VideoState
import kotlinx.coroutines.delay

/**
 * Controls for film mode (the video comes from the hidden desktop page, whose own controls are
 * hidden). Look and behaviour follow m.youtube's player: a tap shows them, they fade out after a
 * few seconds while playing, centre play/pause, time + red seekbar + fullscreen at the bottom.
 *
 * [tapAt] changes on every single tap on the player (PlayerDragLayout.onTap).
 */
@Composable
fun FilmControls(
    video: VideoState,
    tapAt: Long,
    fullscreen: Boolean,
    onToggle: () -> Unit,
    onSeek: (Double) -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var shownAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var visible by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    // A tap toggles them (like m.youtube); any use of a control keeps them up a bit longer.
    LaunchedEffect(tapAt) {
        if (tapAt == 0L) return@LaunchedEffect
        visible = !visible
        shownAt = System.currentTimeMillis()
    }
    LaunchedEffect(visible, shownAt, video.playing, dragging) {
        if (visible && video.playing && !dragging) {
            delay(3_000)
            visible = false
        }
    }
    // Paused: always visible, like the official player.
    val show = visible || !video.playing

    // The page reports the position about once a second; move the bar smoothly in between.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val reportedAt = remember(video.position, video.playing) { System.currentTimeMillis() }
    LaunchedEffect(show, video.playing) {
        while (show && video.playing) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val position = if (video.playing) video.position + (now - reportedAt).coerceAtLeast(0) / 1000.0 else video.position
    val duration = video.duration

    AnimatedVisibility(show, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
            IconButton(
                onClick = { onToggle(); shownAt = System.currentTimeMillis() },
                modifier = Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color(0x55000000)),
            ) {
                Icon(if (video.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(44.dp))
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (duration > 0) {
                        val shown = if (dragging) dragValue.toDouble() else position.coerceAtMost(duration)
                        Text("${time(shown)} / ${time(duration)}", color = Color.White, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { onFullscreen(); shownAt = System.currentTimeMillis() }) {
                        Icon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, contentDescription = null, tint = Color.White)
                    }
                }
                if (duration > 0) {
                    SeekBar(
                        fraction = ((if (dragging) dragValue.toDouble() else position) / duration).toFloat().coerceIn(0f, 1f),
                        onDrag = { f -> dragging = true; dragValue = (f * duration).toFloat(); shownAt = System.currentTimeMillis() },
                        onDragEnd = { onSeek(dragValue.toDouble()); dragging = false },
                        modifier = Modifier.fillMaxWidth().height(if (fullscreen) 36.dp else 28.dp),
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

private fun time(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** m.youtube-style seek bar: thin red line, round thumb; tap or drag anywhere on it. */
@Composable
private fun SeekBar(fraction: Float, onDrag: (Float) -> Unit, onDragEnd: () -> Unit, modifier: Modifier = Modifier) {
    // The gesture detectors live across recompositions: always call the latest callbacks.
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val red = Color(0xFFFF0033)
    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { o -> drag((o.x / size.width).coerceIn(0f, 1f)); end() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag((o.x / size.width).coerceIn(0f, 1f)) },
                    onHorizontalDrag = { change, _ -> drag((change.position.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                )
            },
    ) {
        val y = size.height / 2
        val stroke = 3.dp.toPx()
        val x = size.width * fraction
        drawLine(Color(0x66FFFFFF), Offset(0f, y), Offset(size.width, y), stroke)
        drawLine(red, Offset(0f, y), Offset(x, y), stroke)
        drawCircle(red, radius = 6.dp.toPx(), center = Offset(x, y))
    }
}
