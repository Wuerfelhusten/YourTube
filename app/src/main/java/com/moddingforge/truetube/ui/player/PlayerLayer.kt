package com.moddingforge.truetube.ui.player

import android.content.res.Configuration
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.draw.clipToBounds
import com.moddingforge.truetube.playback.PlayerExtras
import com.moddingforge.truetube.web.BridgeCommand
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moddingforge.truetube.web.PlayerController
import com.moddingforge.truetube.web.PlayerMode
import kotlinx.coroutines.launch

/** Height of the mini player bar, like the official app. */
val MiniPlayerHeight = 64.dp
private val MiniWidth = MiniPlayerHeight * 16 / 9

/**
 * The player WebView as an overlay above the feed:
 *  - EXPANDED: full screen; swipe down on the video (portrait) or Back -> mini player.
 *  - MINI: bar above the tab bar (video thumbnail, title, channel, play/pause, close);
 *          tap or swipe up -> expanded, swipe down -> close.
 * The WebView keeps its place in the composition in both modes, so playback never restarts.
 */
@Composable
fun PlayerLayer(
    controller: PlayerController,
    extras: PlayerExtras,
    activity: ComponentActivity,
    miniBottomPadding: Dp,
) {
    val mode by controller.mode.collectAsStateWithLifecycle()
    if (mode == PlayerMode.HIDDEN) return
    val sbSegments by extras.segments.collectAsStateWithLifecycle()
    var toolsOpen by remember { mutableStateOf(false) }
    var toolsShownAt by remember { mutableLongStateOf(0L) }
    var seekHint by remember { mutableStateOf("") }
    var seekHintAt by remember { mutableLongStateOf(0L) }

    val player = controller.player
    val state by player.state.collectAsStateWithLifecycle()
    val video by player.video.collectAsStateWithLifecycle()
    val expanded = mode == PlayerMode.EXPANDED
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val density = LocalDensity.current
    /** Last pixel size of the WebView in the expanded player; reused (scaled) in mini mode. */
    var expandedSize by remember { mutableStateOf<IntSize?>(null) }
    val fullscreenWanted by controller.fullscreen.collectAsStateWithLifecycle()
    val pipState by controller.pip.collectAsStateWithLifecycle()
    // The PiP window's config change arrives before onPictureInPictureModeChanged: ask the activity
    // directly so the first PiP frame is already laid out as PiP.
    val pip = pipState || activity.isInPictureInPictureMode
    val fullscreen = expanded && fullscreenWanted && !pip
    /** Last WebView size outside PiP; PiP shows it frozen and scaled (no viewport change = no pause). */
    var liveSize by remember { mutableStateOf<IntSize?>(null) }
    var liveWasFullscreen by remember { mutableStateOf(false) }

    // Rotation drives fullscreen like in the official app.
    // (The 16:9 PiP window counts as "landscape" - that is not a rotation.)
    LaunchedEffect(portrait) { if (!activity.isInPictureInPictureMode) controller.onRotated(landscape = !portrait) }

    BackHandler(enabled = expanded && !pip) { controller.minimize() }
    // Registered later = higher priority: in fullscreen, Back leaves fullscreen first.
    BackHandler(enabled = fullscreen) { controller.setFullscreen(false, explicit = true) }

    ImmersiveMode(activity, fullscreen)
    val adjust = rememberAdjustState(activity)
    LaunchedEffect(fullscreen) { if (!fullscreen) adjust.resetBrightness() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullHeightPx = with(density) { maxHeight.toPx() }
        val screenWidth = maxWidth
        val progress = (drag.value / fullHeightPx).coerceIn(0f, 1f)

        val container = if (pip || fullscreen) {
            Modifier.fillMaxSize().background(Color.Black)
        } else if (expanded) {
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Follow the finger and shrink a little, like the official app.
                    translationY = drag.value
                    val s = 1f - 0.35f * progress
                    scaleX = s
                    scaleY = s
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
                }
                .background(Color.Black)
                .statusBarsPadding()
        } else {
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = miniBottomPadding)
                .fillMaxWidth()
                .height(MiniPlayerHeight)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onVerticalDrag = { _, dy -> total += dy },
                        onDragEnd = {
                            when {
                                total > 40.dp.toPx() -> controller.close()
                                total < -40.dp.toPx() -> controller.expand()
                            }
                        },
                    )
                }
                .clickable { controller.expand() }
        }

        Box(container) {
            Row(Modifier.fillMaxSize()) {
                // Same composition slot in both modes -> the WebView is never re-created.
                Box(
                    when {
                        pip -> Modifier.weight(1f).fillMaxHeight().clipToBounds()
                        fullscreen -> Modifier.weight(1f).fillMaxHeight()
                        expanded -> Modifier.weight(1f).fillMaxHeight().navigationBarsPadding()
                        else -> Modifier.width(MiniWidth).fillMaxHeight().clipToBounds()
                    },
                ) {
                    // Mini: m.youtube pauses as soon as its viewport changes size. So the WebView
                    // keeps exactly its expanded size and is only *drawn* scaled down; the clip
                    // shows the top 16:9 strip, which is the (fixed, sticky) player.
                    val fullWidth = screenWidth
                    val frozen = liveSize
                    val webModifier = if (pip && frozen != null) {
                        // PiP: same trick as the mini player - frozen size, scaled to the PiP width.
                        val s = screenWidth / with(density) { frozen.width.toDp() }
                        val pipHeightPx = fullHeightPx
                        Modifier
                            .wrapContentSize(Alignment.TopStart, unbounded = true)
                            .requiredSize(with(density) { frozen.width.toDp() }, with(density) { frozen.height.toDp() })
                            .graphicsLayer {
                                scaleX = s
                                scaleY = s
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                                // Fullscreen: the video is centred in the frozen page; portrait: it's the top strip.
                                translationY = if (liveWasFullscreen) (pipHeightPx - frozen.height * s) / 2f else 0f
                            }
                    } else if (expanded) {
                        Modifier.fillMaxSize().onSizeChanged {
                            if (it.height > 0 && !activity.isInPictureInPictureMode) {
                                liveSize = it
                                liveWasFullscreen = fullscreen
                                if (!fullscreen) expandedSize = it
                            }
                        }
                    } else {
                        val s = MiniWidth / fullWidth
                        val size = expandedSize
                        val w = with(density) { size?.width?.toDp() } ?: fullWidth
                        val h = with(density) { size?.height?.toDp() } ?: (fullWidth * 9 / 16)
                        Modifier
                            .wrapContentSize(Alignment.TopStart, unbounded = true)
                            .requiredSize(w, h)
                            .graphicsLayer {
                                scaleX = s
                                scaleY = s
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                            }
                    }
                    key(state.generation) {
                        AndroidView(
                            factory = { ctx ->
                                android.util.Log.i("TT.Player", "player AndroidView factory")
                                PlayerDragLayout(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                    addView(player.attach(activity))
                                    onDrag = { dy -> scope.launch { drag.snapTo(dy) } }
                                    onRelease = { dy, vy ->
                                        android.util.Log.i("TT.Player", "drag release dy=$dy vy=$vy of $fullHeightPx")
                                        scope.launch {
                                            if (dy > fullHeightPx * 0.18f || vy > 1800f) {
                                                drag.animateTo(fullHeightPx * 0.6f, tween(120))
                                                controller.minimize()
                                                drag.snapTo(0f)
                                            } else {
                                                drag.animateTo(0f, tween(180))
                                            }
                                        }
                                    }
                                }
                            },
                            update = { layout ->
                                layout.mode = when {
                                    pip -> PlayerDragLayout.GestureMode.NONE
                                    fullscreen -> PlayerDragLayout.GestureMode.FULLSCREEN
                                    expanded && portrait && !pip -> PlayerDragLayout.GestureMode.PORTRAIT
                                    else -> PlayerDragLayout.GestureMode.NONE
                                }
                                layout.onSwipeUp = { controller.setFullscreen(true, explicit = true) }
                                layout.onSwipeDownFullscreen = { controller.setFullscreen(false, explicit = true) }
                                layout.onAdjust = { kind, delta -> adjust.change(kind, delta) }
                                layout.onAdjustEnd = { adjust.end() }
                                layout.doubleTapSeconds = if (expanded && !pip) extras.settings.current.doubleTapSeconds else 0
                                layout.onDoubleTap = { forward ->
                                    val n = extras.settings.current.doubleTapSeconds
                                    player.send(BridgeCommand.PlayerTool("seek-by", (if (forward) n else -n).toString()))
                                    seekHint = if (forward) "+$n s" else "−$n s"
                                    seekHintAt = System.currentTimeMillis()
                                }
                                layout.onAnyTouch = { toolsShownAt = System.currentTimeMillis() }
                            },
                            onRelease = { layout ->
                                android.util.Log.i("TT.Player", "player AndroidView released")
                                player.detach(activity)
                                layout.removeAllViews()
                            },
                            modifier = webModifier,
                        )
                    }
                    // Mini: the video is only a thumbnail - taps go to the native bar, not the page.
                    if (!expanded) Box(Modifier.matchParentSize().clickable { controller.expand() })

                    // Native layer over the video (expanded / fullscreen, not PiP): SponsorBlock strip,
                    // tools button, double-tap hint. Independent of YouTube's player DOM.
                    if (expanded && !pip) {
                        val videoBox = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(screenWidth * 9 / 16)
                        Box(videoBox) {
                            SegmentStrip(sbSegments, video.videoId, video.duration, Modifier.align(Alignment.BottomStart))
                            ToolsButton(visibleSince = toolsShownAt, onClick = { toolsOpen = true },
                                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 52.dp))
                            SeekHint(seekHint, seekHintAt, Modifier.align(Alignment.Center))
                        }
                    }
                }

                if (!expanded) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Text(video.title.ifEmpty { state.title }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        if (video.channel.isNotEmpty()) {
                            Text(video.channel, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = controller::togglePlay, modifier = Modifier.align(Alignment.CenterVertically)) {
                        Icon(if (video.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
                    }
                    IconButton(onClick = controller::close, modifier = Modifier.align(Alignment.CenterVertically)) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                }
            }
            if (!expanded && video.duration > 0) {
                LinearProgressIndicator(
                    progress = { (video.position / video.duration).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp),
                    color = Color(0xFFFF0033),
                    trackColor = Color.Transparent,
                    drawStopIndicator = {},
                )
            }
            if (fullscreen) AdjustIndicator(adjust, Modifier.align(Alignment.Center))
        }
    }

    if (toolsOpen) {
        PlayerToolsSheet(
            player = player,
            video = video,
            onPip = { toolsOpen = false; enterPip(activity) },
            onDismiss = { toolsOpen = false },
        )
    }
}

// --- fullscreen helpers -------------------------------------------------------------------------

/** Hides status and navigation bars while [on] (swipe from the edge shows them briefly). */
@Composable
private fun ImmersiveMode(activity: ComponentActivity, on: Boolean) {
    DisposableEffect(on) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (on) controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** Brightness (window only, reset when leaving fullscreen) and media volume via swipe gestures. */
@Stable
class AdjustState(private val activity: ComponentActivity) {
    private val audio = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    private var volumeLevel = -1f

    var kind by mutableStateOf<PlayerDragLayout.Adjust?>(null)
        private set
    var value by mutableFloatStateOf(0f)
        private set
    var endedAt by mutableLongStateOf(0L)
        private set

    fun change(kind: PlayerDragLayout.Adjust, delta: Float) {
        this.kind = kind
        endedAt = 0L
        // A full swipe over the whole height covers the full range, like most players.
        when (kind) {
            PlayerDragLayout.Adjust.BRIGHTNESS -> {
                val attrs = activity.window.attributes
                val current = if (attrs.screenBrightness >= 0f) attrs.screenBrightness else systemBrightness()
                val next = (current + delta).coerceIn(0.01f, 1f)
                attrs.screenBrightness = next
                activity.window.attributes = attrs
                value = next
            }
            PlayerDragLayout.Adjust.VOLUME -> {
                if (volumeLevel < 0f) volumeLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat()
                volumeLevel = (volumeLevel + delta).coerceIn(0f, 1f)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volumeLevel * maxVolume).roundToInt(), 0)
                value = volumeLevel
            }
        }
    }

    fun end() {
        volumeLevel = -1f
        endedAt = System.currentTimeMillis()
    }

    fun hide() {
        kind = null
    }

    /** Back to the system brightness (called when leaving fullscreen). */
    fun resetBrightness() {
        val attrs = activity.window.attributes
        attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = attrs
    }

    private fun systemBrightness(): Float = runCatching {
        Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    }.getOrDefault(0.5f)
}

@Composable
private fun rememberAdjustState(activity: ComponentActivity): AdjustState {
    val state = remember(activity) { AdjustState(activity) }
    DisposableEffect(state) { onDispose { state.resetBrightness() } }
    return state
}

@Composable
private fun AdjustIndicator(state: AdjustState, modifier: Modifier = Modifier) {
    val kind = state.kind ?: return
    LaunchedEffect(state.endedAt) {
        if (state.endedAt > 0L) {
            delay(700)
            state.hide()
        }
    }
    Row(
        modifier
            .background(Color(0xB3000000), RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (kind == PlayerDragLayout.Adjust.BRIGHTNESS) Icons.Filled.BrightnessMedium else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            tint = Color.White,
        )
        Spacer(Modifier.width(12.dp))
        LinearProgressIndicator(
            progress = { state.value },
            modifier = Modifier.width(120.dp).height(4.dp),
            color = Color.White,
            trackColor = Color(0x55FFFFFF),
            drawStopIndicator = {},
        )
    }
}
