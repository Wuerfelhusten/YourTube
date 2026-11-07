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
    activity: ComponentActivity,
    miniBottomPadding: Dp,
) {
    val mode by controller.mode.collectAsStateWithLifecycle()
    if (mode == PlayerMode.HIDDEN) return

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

    BackHandler(enabled = expanded) { controller.minimize() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullHeightPx = with(density) { maxHeight.toPx() }
        val screenWidth = maxWidth
        val progress = (drag.value / fullHeightPx).coerceIn(0f, 1f)

        val container = if (expanded) {
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
                    if (expanded) Modifier.weight(1f).fillMaxHeight().navigationBarsPadding()
                    else Modifier.width(MiniWidth).fillMaxHeight().clipToBounds(),
                ) {
                    // Mini: m.youtube pauses as soon as its viewport changes size. So the WebView
                    // keeps exactly its expanded size and is only *drawn* scaled down; the clip
                    // shows the top 16:9 strip, which is the (fixed, sticky) player.
                    val fullWidth = screenWidth
                    val webModifier = if (expanded) {
                        Modifier.fillMaxSize().onSizeChanged { if (it.height > 0) expandedSize = it }
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
                            update = { layout -> layout.dragEnabled = expanded && portrait },
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
        }
    }
}
