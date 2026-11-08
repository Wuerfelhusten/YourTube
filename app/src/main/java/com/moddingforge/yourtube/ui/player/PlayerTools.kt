package com.moddingforge.yourtube.ui.player

import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClosedCaptionDisabled
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.moddingforge.yourtube.R
import com.moddingforge.yourtube.web.BridgeCommand
import com.moddingforge.yourtube.web.VideoState
import com.moddingforge.yourtube.web.WebViewHolder
import kotlinx.coroutines.delay

/** SponsorBlock segment colours (the community's usual palette). */
private val SEGMENT_COLORS = mapOf(
    "sponsor" to Color(0xFF00D400),
    "selfpromo" to Color(0xFFFFFF00),
    "interaction" to Color(0xFFCC00FF),
    "intro" to Color(0xFF00FFFF),
    "outro" to Color(0xFF0202ED),
    "preview" to Color(0xFF008FD6),
    "music_offtopic" to Color(0xFFFF9900),
    "filler" to Color(0xFF7300FF),
)

/**
 * Thin native strip at the bottom edge of the video showing where SponsorBlock segments are.
 * Lives in YourTube's own layer, so it never depends on YouTube's seekbar markup.
 */
@Composable
fun SegmentStrip(segments: Pair<String, List<BridgeCommand.Segment>>, videoId: String, duration: Double, modifier: Modifier = Modifier) {
    val (id, list) = segments
    if (id != videoId || list.isEmpty() || duration <= 0) return
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        for (s in list) {
            val x0 = (s.start / duration).toFloat().coerceIn(0f, 1f) * size.width
            val x1 = (s.end / duration).toFloat().coerceIn(0f, 1f) * size.width
            drawRect(SEGMENT_COLORS[s.category] ?: Color.White, topLeft = androidx.compose.ui.geometry.Offset(x0, 0f),
                size = androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(2f), size.height))
        }
    }
}

/** Small native button on the video; shows for 3 s after any touch on the player, like player controls. */
@Composable
fun ToolsButton(visibleSince: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(visibleSince) {
        if (visibleSince == 0L) return@LaunchedEffect
        visible = true
        delay(3_000)
        visible = false
    }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp).background(Color(0x66000000), CircleShape)) {
            Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.tools_title), tint = Color.White)
        }
    }
}

/** SponsorBlock "button" mode: "Skip <category> ›" while playback is inside such a segment. */
@Composable
fun SkipButton(
    prompt: com.moddingforge.yourtube.web.BridgeMessage.SponsorPrompt?,
    label: (String) -> String,
    onSkip: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(prompt != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val p = prompt ?: return@AnimatedVisibility
        Row(
            Modifier
                .background(Color(0xCC000000), RoundedCornerShape(18.dp))
                .clickable { onSkip(p.end) }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(Modifier.size(10.dp).background(SEGMENT_COLORS[p.category] ?: Color.White, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sponsor_skip_button, label(p.category)), color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Public colour lookup for the settings screen. */
fun segmentColor(category: String): Color = SEGMENT_COLORS[category] ?: Color.White

/** "+10 s" bubble after a native double tap. */
@Composable
fun SeekHint(text: String, at: Long, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(at) {
        if (at == 0L) return@LaunchedEffect
        visible = true
        delay(700)
        visible = false
    }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

private val TOOL_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
private val TOOL_QUALITIES = listOf("auto" to null, "hd1080" to "1080p", "hd720" to "720p", "large" to "480p", "medium" to "360p", "small" to "240p")

/** Native tools for the current video: speed, quality, loop, link at current time, captions off, PiP. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerToolsSheet(player: WebViewHolder, video: VideoState, onPip: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var speed by rememberSaveable { mutableStateOf(1f) }
    var quality by rememberSaveable { mutableStateOf("auto") }
    var loop by rememberSaveable(video.videoId) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(stringResource(R.string.tools_speed), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 20.dp))
            ChipRow {
                for (v in TOOL_SPEEDS) {
                    FilterChip(selected = speed == v, onClick = {
                        speed = v
                        player.send(BridgeCommand.PlayerTool("speed", v.toString()))
                    }, label = { Text((if (v % 1f == 0f) v.toInt().toString() else v.toString()).replace('.', ',') + "×") })
                }
            }
            Text(stringResource(R.string.tools_quality), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 20.dp))
            ChipRow {
                for ((q, label) in TOOL_QUALITIES) {
                    FilterChip(selected = quality == q, onClick = {
                        quality = q
                        player.send(BridgeCommand.PlayerTool("quality", q))
                    }, label = { Text(label ?: stringResource(R.string.tools_auto)) })
                }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.tools_loop)) },
                leadingContent = { Icon(Icons.Outlined.Repeat, null) },
                trailingContent = {
                    Switch(checked = loop, onCheckedChange = {
                        loop = it
                        player.send(BridgeCommand.PlayerTool("loop", if (it) "on" else "off"))
                    })
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.tools_copy_link)) },
                supportingContent = { Text(timeLabel(video.position)) },
                leadingContent = { Icon(Icons.Outlined.Link, null) },
                modifier = Modifier.clickableNoRipple {
                    copyLink(context, video)
                    onDismiss()
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.tools_captions_off)) },
                leadingContent = { Icon(Icons.Outlined.ClosedCaptionDisabled, null) },
                modifier = Modifier.clickableNoRipple {
                    player.send(BridgeCommand.PlayerTool("captions-off"))
                    onDismiss()
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_pip)) },
                leadingContent = { Icon(Icons.Outlined.PictureInPictureAlt, null) },
                modifier = Modifier.clickableNoRipple(onPip),
            )
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
        Spacer(Modifier.width(8.dp))
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit) = this.clickable(onClick = onClick)

private fun timeLabel(seconds: Double): String {
    val s = seconds.toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun copyLink(context: Context, video: VideoState) {
    if (video.videoId.isEmpty()) return
    val t = video.position.toInt()
    val link = "https://youtu.be/${video.videoId}" + if (t > 0) "?t=$t" else ""
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText(video.title, link))
    Toast.makeText(context, context.getString(R.string.tools_copied, timeLabel(video.position)), Toast.LENGTH_SHORT).show()
}

fun enterPip(activity: ComponentActivity) {
    runCatching { activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
}
