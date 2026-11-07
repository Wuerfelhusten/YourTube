package com.moddingforge.truetube.ui.settings

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ClosedCaptionDisabled
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import com.moddingforge.truetube.web.WebViewHolder
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moddingforge.truetube.AppContainer
import com.moddingforge.truetube.BuildConfig
import com.moddingforge.truetube.R
import com.moddingforge.truetube.adblock.AdblockEngine
import com.moddingforge.truetube.adblock.AdblockUpdateWorker
import com.moddingforge.truetube.adblock.EngineState
import com.moddingforge.truetube.ui.shell.LinkHandling
import kotlinx.coroutines.delay

/**
 * TrueTube's own settings ("TrueTube Settings" entry at the top of YouTube's settings page and in
 * the top bar menu). First version: ad blocker status/update, link handling, account, about.
 * Playback and appearance switches follow in M9.
 */
@Composable
fun SettingsScreen(container: AppContainer, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val engine by container.filterRepository.state.collectAsStateWithLifecycle()
    var blocked by remember { mutableIntStateOf(0) }
    var linksEnabled by remember { mutableStateOf(LinkHandling.linksEnabled(context)) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var updateRequested by remember { mutableStateOf(false) }
    var editCss by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var editKeywords by remember { mutableStateOf(false) }
    val s by container.settings.settings.collectAsStateWithLifecycle()
    val extras by container.filterRepository.optionalComponents.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        while (true) {
            blocked = container.feed.adblock.blockedRequests.get() + container.player.adblock.blockedRequests.get()
            delay(1_000)
        }
    }
    // Coming back from the system "Open by default" screen.
    LifecycleResumeEffect(Unit) {
        linksEnabled = LinkHandling.linksEnabled(context)
        onPauseOrDispose { }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.settings_title), fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            // --- support (top of the list) ---
            Item(Icons.Outlined.Coffee, stringResource(R.string.settings_support), "ko-fi.com/wuerfelhusten") {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(KOFI_URL))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }

            // --- ad blocker ---
            Section(stringResource(R.string.settings_adblock))
            SwitchItem(Icons.Outlined.Shield, stringResource(R.string.settings_adblock_switch),
                stringResource(R.string.settings_adblock_switch_hint), s.adblockEnabled) { v ->
                container.settings.update { it.copy(adblockEnabled = v) }
            }
            SwitchItem(Icons.Outlined.Cookie, stringResource(R.string.settings_cookies),
                stringResource(R.string.settings_cookies_hint), s.cookieNoticeBlocking, enabled = s.adblockEnabled) { v ->
                container.settings.update { it.copy(cookieNoticeBlocking = v) }
            }
            val engineSummary = when (val s = engine) {
                is EngineState.Ready -> stringResource(
                    R.string.settings_adblock_ready, s.lists,
                    if (s.listsUpdatedAt > 0) DateUtils.getRelativeTimeSpanString(s.listsUpdatedAt).toString()
                    else stringResource(R.string.settings_adblock_snapshot),
                )
                is EngineState.Unavailable -> stringResource(R.string.settings_adblock_unavailable, s.reason)
                EngineState.Loading -> stringResource(R.string.settings_adblock_loading)
            }
            Item(Icons.Outlined.Shield, stringResource(R.string.settings_adblock_engine), engineSummary)
            Item(Icons.Outlined.Shield, stringResource(R.string.settings_adblock_blocked), blocked.toString())
            Item(
                Icons.Outlined.Refresh,
                stringResource(R.string.settings_adblock_update),
                stringResource(if (updateRequested) R.string.settings_adblock_update_started else R.string.settings_adblock_update_hint),
            ) {
                AdblockUpdateWorker.runNow(context)
                updateRequested = true
            }

            // --- playback ---
            Section(stringResource(R.string.settings_playback))
            SwitchItem(Icons.Outlined.Headphones, stringResource(R.string.settings_background),
                stringResource(R.string.settings_background_hint), s.backgroundPlayback) { v ->
                container.settings.update { it.copy(backgroundPlayback = v) }
            }
            SwitchItem(Icons.Outlined.PictureInPictureAlt, stringResource(R.string.settings_pip),
                stringResource(R.string.settings_pip_hint), s.autoPip) { v ->
                container.settings.update { it.copy(autoPip = v) }
            }
            SwitchItem(Icons.Outlined.ScreenRotation, stringResource(R.string.settings_rotate),
                stringResource(R.string.settings_rotate_hint), s.rotateToFullscreen) { v ->
                container.settings.update { it.copy(rotateToFullscreen = v) }
            }

            // --- player extras ---
            Section(stringResource(R.string.settings_player))
            Item(Icons.Outlined.Wifi, stringResource(R.string.settings_quality_wifi), qualityLabel(s.qualityWifi)) { picker = Picker.QUALITY_WIFI }
            Item(Icons.Outlined.SignalCellularAlt, stringResource(R.string.settings_quality_mobile), qualityLabel(s.qualityMobile)) { picker = Picker.QUALITY_MOBILE }
            Item(Icons.Outlined.Speed, stringResource(R.string.settings_speed), speedLabel(s.speed)) { picker = Picker.SPEED }
            SwitchItem(Icons.Outlined.FastForward, stringResource(R.string.settings_sponsorblock),
                stringResource(R.string.settings_sponsorblock_hint), s.sponsorBlock) { v ->
                container.settings.update { it.copy(sponsorBlock = v) }
            }
            if (s.sponsorBlock) {
                Item(Icons.Outlined.Category, stringResource(R.string.settings_sponsorblock_categories),
                    s.sponsorCategories.mapNotNull { WebViewHolder.SPONSOR_LABELS[it] }.map { stringResource(it) }.joinToString(", ")
                        .ifEmpty { stringResource(R.string.settings_custom_css_none) }) { picker = Picker.SPONSOR }
            }
            SwitchItem(Icons.Outlined.RecordVoiceOver, stringResource(R.string.settings_original_audio),
                stringResource(R.string.settings_original_audio_hint), s.originalAudio) { v ->
                container.settings.update { it.copy(originalAudio = v) }
            }
            SwitchItem(Icons.Outlined.ClosedCaptionDisabled, stringResource(R.string.settings_no_captions),
                stringResource(R.string.settings_no_captions_hint), s.noAutoCaptions) { v ->
                container.settings.update { it.copy(noAutoCaptions = v) }
            }
            Item(Icons.Outlined.TouchApp, stringResource(R.string.settings_double_tap),
                stringResource(R.string.settings_seconds, s.doubleTapSeconds)) { picker = Picker.DOUBLE_TAP }
            SwitchItem(Icons.Outlined.ThumbDown, stringResource(R.string.settings_ryd),
                stringResource(R.string.settings_ryd_hint), s.returnDislikes) { v ->
                container.settings.update { it.copy(returnDislikes = v) }
            }

            // --- feed filters (TrueTube data layer: removed before YouTube renders) ---
            Section(stringResource(R.string.settings_feed))
            SwitchItem(Icons.Outlined.Bolt, stringResource(R.string.filter_shorts), null, s.filterShorts) { v ->
                container.settings.update { it.copy(filterShorts = v) }
            }
            SwitchItem(Icons.Outlined.Forum, stringResource(R.string.filter_posts), null, s.filterPosts) { v ->
                container.settings.update { it.copy(filterPosts = v) }
            }
            SwitchItem(Icons.AutoMirrored.Outlined.QueueMusic, stringResource(R.string.filter_mixes), null, s.filterMixes) { v ->
                container.settings.update { it.copy(filterMixes = v) }
            }
            SwitchItem(Icons.Outlined.TaskAlt, stringResource(R.string.filter_watched), stringResource(R.string.filter_watched_hint), s.filterWatched) { v ->
                container.settings.update { it.copy(filterWatched = v) }
            }
            Item(Icons.Outlined.Timer, stringResource(R.string.filter_min_duration),
                if (s.filterMinDuration <= 0) stringResource(R.string.filter_off) else durationLabel(s.filterMinDuration)) { picker = Picker.MIN_DURATION }
            Item(Icons.Outlined.Block, stringResource(R.string.filter_keywords),
                s.filterKeywords.lines().filter { it.isNotBlank() }.joinToString(", ").ifEmpty { stringResource(R.string.filter_off) }) { editKeywords = true }
            SwitchItem(Icons.Outlined.Title, stringResource(R.string.settings_dearrow), stringResource(R.string.settings_dearrow_hint), s.deArrow) { v ->
                container.settings.update { it.copy(deArrow = v) }
            }

            // --- clean up YouTube (Brave's YouTube lists + TrueTube options) ---
            Section(stringResource(R.string.settings_cleanup))
            SwitchItem(Icons.Outlined.Bolt, stringResource(R.string.settings_shorts_tab), null, s.showShortsTab) { v ->
                container.settings.update { it.copy(showShortsTab = v) }
            }
            SwitchItem(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.settings_hide_comments), null, s.hideComments) { v ->
                container.settings.update { it.copy(hideComments = v) }
            }
            for (comp in extras) {
                SwitchItem(Icons.Outlined.FilterList, listLabel(comp.uuid, comp.title),
                    stringResource(R.string.settings_brave_list), comp.uuid in s.extraLists, enabled = s.adblockEnabled) { v ->
                    container.settings.update { it.copy(extraLists = if (v) it.extraLists + comp.uuid else it.extraLists - comp.uuid) }
                }
            }
            Item(Icons.Outlined.Code, stringResource(R.string.settings_custom_css),
                if (s.customCss.isBlank()) stringResource(R.string.settings_custom_css_none)
                else stringResource(R.string.settings_custom_css_lines, s.customCss.lines().count { it.isNotBlank() })) {
                editCss = true
            }

            // --- app ---
            Section(stringResource(R.string.settings_app))
            Item(Icons.Outlined.Home, stringResource(R.string.settings_start_page), startPageLabel(s.startPage)) { picker = Picker.START_PAGE }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                SwitchItem(Icons.Outlined.Palette, stringResource(R.string.settings_material_you),
                    stringResource(R.string.settings_material_you_hint), s.materialYou) { v ->
                    container.settings.update { it.copy(materialYou = v) }
                }
            }
            SwitchItem(Icons.Outlined.DarkMode, stringResource(R.string.settings_amoled),
                stringResource(R.string.settings_amoled_hint), s.amoled) { v ->
                container.settings.update { it.copy(amoled = v) }
            }

            // --- links ---
            Section(stringResource(R.string.settings_links))
            linksEnabled?.let { enabled ->
                Item(
                    Icons.Outlined.Link,
                    stringResource(R.string.links_hint_title),
                    stringResource(if (enabled) R.string.settings_links_on else R.string.settings_links_off),
                ) { LinkHandling.openSettings(context) }
            }

            // --- account ---
            Section(stringResource(R.string.settings_account))
            Item(Icons.AutoMirrored.Filled.Logout, stringResource(R.string.settings_sign_out), stringResource(R.string.settings_sign_out_hint)) {
                confirmSignOut = true
            }

            // --- about ---
            Section(stringResource(R.string.settings_about))
            Item(Icons.Outlined.Info, "TrueTube ${BuildConfig.VERSION_NAME}",
                stringResource(R.string.settings_about_text, runCatching { AdblockEngine.nativeVersion }.getOrDefault("?")))
            Spacer(Modifier.height(24.dp))
        }
    }

    when (picker) {
        Picker.QUALITY_WIFI -> ChoiceDialog(stringResource(R.string.settings_quality_wifi), QUALITIES, s.qualityWifi, { qualityLabel(it) },
            onDismiss = { picker = null }) { q -> container.settings.update { it.copy(qualityWifi = q) }; picker = null }
        Picker.QUALITY_MOBILE -> ChoiceDialog(stringResource(R.string.settings_quality_mobile), QUALITIES, s.qualityMobile, { qualityLabel(it) },
            onDismiss = { picker = null }) { q -> container.settings.update { it.copy(qualityMobile = q) }; picker = null }
        Picker.SPEED -> ChoiceDialog(stringResource(R.string.settings_speed), SPEEDS, s.speed, { speedLabel(it) },
            onDismiss = { picker = null }) { v -> container.settings.update { it.copy(speed = v) }; picker = null }
        Picker.SPONSOR -> MultiChoiceDialog(stringResource(R.string.settings_sponsorblock_categories),
            WebViewHolder.SPONSOR_LABELS.keys.toList(), s.sponsorCategories, { stringResource(WebViewHolder.SPONSOR_LABELS.getValue(it)) },
            onDismiss = { picker = null }) { set -> container.settings.update { it.copy(sponsorCategories = set) }; picker = null }
        Picker.MIN_DURATION -> ChoiceDialog(stringResource(R.string.filter_min_duration), MIN_DURATIONS, s.filterMinDuration,
            { if (it <= 0) stringResource(R.string.filter_off) else durationLabel(it) },
            onDismiss = { picker = null }) { v -> container.settings.update { it.copy(filterMinDuration = v) }; picker = null }
        Picker.DOUBLE_TAP -> ChoiceDialog(stringResource(R.string.settings_double_tap), listOf(5, 10, 15, 20, 30, 60), s.doubleTapSeconds,
            { stringResource(R.string.settings_seconds, it) },
            onDismiss = { picker = null }) { v -> container.settings.update { it.copy(doubleTapSeconds = v) }; picker = null }
        Picker.START_PAGE -> ChoiceDialog(stringResource(R.string.settings_start_page), listOf("home", "subscriptions", "library", "history"), s.startPage,
            { startPageLabel(it) },
            onDismiss = { picker = null }) { v -> container.settings.update { it.copy(startPage = v) }; picker = null }
        null -> Unit
    }

    if (editKeywords) {
        var text by remember { mutableStateOf(s.filterKeywords) }
        AlertDialog(
            onDismissRequest = { editKeywords = false },
            title = { Text(stringResource(R.string.filter_keywords)) },
            text = {
                Column {
                    Text(stringResource(R.string.filter_keywords_hint), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().height(160.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { container.settings.update { it.copy(filterKeywords = text) }; editKeywords = false }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = { TextButton(onClick = { editKeywords = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (editCss) {
        var text by remember { mutableStateOf(s.customCss) }
        AlertDialog(
            onDismissRequest = { editCss = false },
            title = { Text(stringResource(R.string.settings_custom_css)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_custom_css_hint), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        placeholder = { Text("ytm-rich-section-renderer { display: none !important; }", fontFamily = FontFamily.Monospace) },
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    container.settings.update { it.copy(customCss = text) }
                    editCss = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { editCss = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.settings_sign_out)) },
            text = { Text(stringResource(R.string.settings_sign_out_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    container.playerController.close()
                    container.feed.signOut()
                    onClose()
                }) { Text(stringResource(R.string.settings_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private const val KOFI_URL = "https://ko-fi.com/wuerfelhusten"

private enum class Picker { QUALITY_WIFI, QUALITY_MOBILE, SPEED, SPONSOR, MIN_DURATION, DOUBLE_TAP, START_PAGE }

@Composable
private fun startPageLabel(p: String): String = when (p) {
    "subscriptions" -> stringResource(R.string.tab_subscriptions)
    "library" -> stringResource(R.string.tab_you)
    "history" -> stringResource(R.string.start_history)
    else -> stringResource(R.string.start_home)
}

private val MIN_DURATIONS = listOf(0, 60, 120, 180, 300, 600)

@Composable
private fun durationLabel(seconds: Int): String =
    if (seconds < 60) stringResource(R.string.filter_shorter_than_s, seconds)
    else stringResource(R.string.filter_shorter_than_min, seconds / 60)

private val QUALITIES = listOf("auto", "hd2160", "hd1440", "hd1080", "hd720", "large", "medium", "small", "tiny")
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

@Composable
private fun qualityLabel(q: String): String = when (q) {
    "auto" -> stringResource(R.string.quality_auto)
    "hd2160" -> "2160p (4K)"
    "hd1440" -> "1440p"
    "hd1080" -> "1080p"
    "hd720" -> "720p"
    "large" -> "480p"
    "medium" -> "360p"
    "small" -> "240p"
    "tiny" -> "144p"
    else -> q
}

private fun speedLabel(v: Float): String = (if (v % 1f == 0f) v.toInt().toString() else v.toString()).replace('.', ',') + "×"

@Composable
private fun <T> ChoiceDialog(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (o in options) {
                    Row(Modifier.fillMaxWidth().clickable { onPick(o) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = o == selected, onClick = { onPick(o) })
                        Spacer(Modifier.width(8.dp))
                        Text(label(o))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MultiChoiceDialog(title: String, options: List<String>, selected: Set<String>, label: @Composable (String) -> String, onDismiss: () -> Unit, onDone: (Set<String>) -> Unit) {
    var current by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (o in options) {
                    val on = o in current
                    Row(Modifier.fillMaxWidth().clickable { current = if (on) current - o else current + o }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = on, onCheckedChange = { current = if (it) current + o else current - o })
                        Spacer(Modifier.width(8.dp))
                        Text(label(o))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(current) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Brave's catalog titles are English; show them localized (fallback: the catalog title). */
@Composable
private fun listLabel(uuid: String, title: String): String {
    val id = when (uuid.lowercase().substringBefore('-')) {
        "9e8ec586" -> R.string.list_yt_shorts
        "5c6a6c95" -> R.string.list_yt_playables
        "2d57aded" -> R.string.list_yt_recommended
        "7f11b964" -> R.string.list_yt_autodubbed
        "d579f370" -> R.string.list_yt_endscreen
        "49958da7" -> R.string.list_yt_membersonly
        "bd308b90" -> R.string.list_yt_distracting
        "3afa9d35" -> R.string.list_yt_thumbnails
        else -> null
    }
    return if (id != null) stringResource(id) else title
}

@Composable
private fun SwitchItem(
    icon: ImageVector,
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alpha = if (enabled) 1f else 0.4f
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    Text(
        title,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Item(icon: ImageVector, title: String, summary: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
