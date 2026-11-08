package com.moddingforge.yourtube.ui.settings

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ClosedCaptionDisabled
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.ViewTimeline
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moddingforge.yourtube.AppContainer
import com.moddingforge.yourtube.BuildConfig
import com.moddingforge.yourtube.R
import com.moddingforge.yourtube.adblock.AdblockEngine
import com.moddingforge.yourtube.adblock.AdblockUpdateWorker
import com.moddingforge.yourtube.adblock.EngineState
import com.moddingforge.yourtube.data.AppSettings
import com.moddingforge.yourtube.ui.player.segmentColor
import com.moddingforge.yourtube.ui.shell.LinkHandling
import com.moddingforge.yourtube.ui.shell.updateStatusText
import com.moddingforge.yourtube.update.UpdateState
import com.moddingforge.yourtube.web.WebViewHolder
import kotlinx.coroutines.delay

/** Sub pages of YourTube Settings. */
private enum class Page(val title: Int) {
    MAIN(R.string.settings_title),
    ADBLOCK(R.string.settings_adblock),
    PLAYBACK(R.string.settings_playback),
    PLAYER(R.string.settings_player),
    SPONSORBLOCK(R.string.settings_sponsorblock),
    FEED(R.string.settings_feed),
    CLEANUP(R.string.settings_cleanup),
    APPEARANCE(R.string.settings_app),
    ACCOUNT(R.string.settings_links_account),
    ABOUT(R.string.settings_about),
}

/**
 * YourTube Settings: an overview with one entry per area, each opening its own sub page
 * (SponsorBlock has a full page of its own). Reached via the entry at the top of YouTube's settings.
 */
@Composable
fun SettingsScreen(container: AppContainer, onClose: () -> Unit) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    BackHandler { if (page == Page.MAIN) onClose() else page = Page.MAIN }

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
    val update: ((AppSettings) -> AppSettings) -> Unit = { container.settings.update(it) }
    val updateState by container.updater.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        while (true) {
            blocked = container.feed.adblock.blockedRequests.get() + container.player.adblock.blockedRequests.get()
            delay(1_000)
        }
    }
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
            IconButton(onClick = { if (page == Page.MAIN) onClose() else page = Page.MAIN }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
            Text(stringResource(page.title), fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            when (page) {
                Page.MAIN -> {
                    Item(Icons.Outlined.Coffee, stringResource(R.string.settings_support), "ko-fi.com/wuerfelhusten") {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(KOFI_URL))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    val off = stringResource(R.string.filter_off)
                    NavItem(Icons.Outlined.Shield, Page.ADBLOCK,
                        if (!s.adblockEnabled) off else stringResource(R.string.summary_blocked, blocked)) { page = it }
                    NavItem(Icons.Outlined.PlayCircle, Page.PLAYBACK, stringResource(R.string.summary_playback)) { page = it }
                    NavItem(Icons.Outlined.Speed, Page.PLAYER, "${qualityLabel(s.qualityWifi)} · ${speedLabel(s.speed)}") { page = it }
                    NavItem(Icons.Outlined.FastForward, Page.SPONSORBLOCK,
                        if (!s.sponsorBlock) off else stringResource(R.string.summary_sponsor, s.sponsorModes.count { it.value != "off" })) { page = it }
                    val feedCount = listOf(s.filterShorts, s.filterPosts, s.filterMixes, s.filterWatched, s.filterMinDuration > 0,
                        s.filterKeywords.isNotBlank(), s.deArrow).count { it }
                    NavItem(Icons.Outlined.FilterAlt, Page.FEED, if (feedCount == 0) off else stringResource(R.string.summary_active, feedCount)) { page = it }
                    NavItem(Icons.Outlined.CleaningServices, Page.CLEANUP, stringResource(R.string.summary_cleanup)) { page = it }
                    NavItem(Icons.Outlined.Palette, Page.APPEARANCE, startPageLabel(s.startPage) + if (s.amoled) " · AMOLED" else "") { page = it }
                    NavItem(Icons.Outlined.ManageAccounts, Page.ACCOUNT, stringResource(R.string.summary_account)) { page = it }
                    NavItem(Icons.Outlined.Info, Page.ABOUT,
                        if (updateState is UpdateState.Available) updateStatusText(updateState) else "YourTube ${BuildConfig.VERSION_NAME}") { page = it }
                }

                Page.ADBLOCK -> {
                    SwitchItem(Icons.Outlined.Shield, stringResource(R.string.settings_adblock_switch),
                        stringResource(R.string.settings_adblock_switch_hint), s.adblockEnabled) { v -> update { it.copy(adblockEnabled = v) } }
                    SwitchItem(Icons.Outlined.Cookie, stringResource(R.string.settings_cookies),
                        stringResource(R.string.settings_cookies_hint), s.cookieNoticeBlocking, enabled = s.adblockEnabled) { v ->
                        update { it.copy(cookieNoticeBlocking = v) }
                    }
                    Section(stringResource(R.string.section_status))
                    val engineSummary = when (val e = engine) {
                        is EngineState.Ready -> stringResource(
                            R.string.settings_adblock_ready, e.lists,
                            if (e.listsUpdatedAt > 0) DateUtils.getRelativeTimeSpanString(e.listsUpdatedAt).toString()
                            else stringResource(R.string.settings_adblock_snapshot),
                        )
                        is EngineState.Unavailable -> stringResource(R.string.settings_adblock_unavailable, e.reason)
                        EngineState.Loading -> stringResource(R.string.settings_adblock_loading)
                    }
                    Item(Icons.Outlined.Shield, stringResource(R.string.settings_adblock_engine), engineSummary)
                    Item(Icons.Outlined.Block, stringResource(R.string.settings_adblock_blocked), blocked.toString())
                    Item(Icons.Outlined.Refresh, stringResource(R.string.settings_adblock_update),
                        stringResource(if (updateRequested) R.string.settings_adblock_update_started else R.string.settings_adblock_update_hint)) {
                        AdblockUpdateWorker.runNow(context)
                        updateRequested = true
                    }
                }

                Page.PLAYBACK -> {
                    Section(stringResource(R.string.section_background))
                    SwitchItem(Icons.Outlined.Headphones, stringResource(R.string.settings_background),
                        stringResource(R.string.settings_background_hint), s.backgroundPlayback) { v -> update { it.copy(backgroundPlayback = v) } }
                    SwitchItem(Icons.Outlined.PictureInPictureAlt, stringResource(R.string.settings_pip),
                        stringResource(R.string.settings_pip_hint), s.autoPip) { v -> update { it.copy(autoPip = v) } }
                    Section(stringResource(R.string.section_gestures))
                    SwitchItem(Icons.Outlined.ScreenRotation, stringResource(R.string.settings_rotate),
                        stringResource(R.string.settings_rotate_hint), s.rotateToFullscreen) { v -> update { it.copy(rotateToFullscreen = v) } }
                    Item(Icons.Outlined.TouchApp, stringResource(R.string.settings_double_tap),
                        stringResource(R.string.settings_seconds, s.doubleTapSeconds)) { picker = Picker.DOUBLE_TAP }
                    Section(stringResource(R.string.section_audio_captions))
                    SwitchItem(Icons.Outlined.RecordVoiceOver, stringResource(R.string.settings_original_audio),
                        stringResource(R.string.settings_original_audio_hint), s.originalAudio) { v -> update { it.copy(originalAudio = v) } }
                    SwitchItem(Icons.Outlined.ClosedCaptionDisabled, stringResource(R.string.settings_no_captions),
                        stringResource(R.string.settings_no_captions_hint), s.noAutoCaptions) { v -> update { it.copy(noAutoCaptions = v) } }
                }

                Page.PLAYER -> {
                    Section(stringResource(R.string.section_defaults))
                    Item(Icons.Outlined.Wifi, stringResource(R.string.settings_quality_wifi), qualityLabel(s.qualityWifi)) { picker = Picker.QUALITY_WIFI }
                    Item(Icons.Outlined.SignalCellularAlt, stringResource(R.string.settings_quality_mobile), qualityLabel(s.qualityMobile)) { picker = Picker.QUALITY_MOBILE }
                    Item(Icons.Outlined.Speed, stringResource(R.string.settings_speed), speedLabel(s.speed)) { picker = Picker.SPEED }
                    Section(stringResource(R.string.section_ratings))
                    SwitchItem(Icons.Outlined.ThumbDown, stringResource(R.string.settings_ryd),
                        stringResource(R.string.settings_ryd_hint), s.returnDislikes) { v -> update { it.copy(returnDislikes = v) } }
                }

                Page.SPONSORBLOCK -> SponsorBlockPage(s, update) { picker = it }

                Page.FEED -> {
                    Text(stringResource(R.string.feed_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    Section(stringResource(R.string.section_remove))
                    SwitchItem(Icons.Outlined.Bolt, stringResource(R.string.filter_shorts), null, s.filterShorts) { v -> update { it.copy(filterShorts = v) } }
                    SwitchItem(Icons.Outlined.Forum, stringResource(R.string.filter_posts), null, s.filterPosts) { v -> update { it.copy(filterPosts = v) } }
                    SwitchItem(Icons.AutoMirrored.Outlined.QueueMusic, stringResource(R.string.filter_mixes), null, s.filterMixes) { v -> update { it.copy(filterMixes = v) } }
                    SwitchItem(Icons.Outlined.TaskAlt, stringResource(R.string.filter_watched), stringResource(R.string.filter_watched_hint), s.filterWatched) { v ->
                        update { it.copy(filterWatched = v) }
                    }
                    Item(Icons.Outlined.Timer, stringResource(R.string.filter_min_duration),
                        if (s.filterMinDuration <= 0) stringResource(R.string.filter_off) else durationLabel(s.filterMinDuration)) { picker = Picker.MIN_DURATION }
                    Item(Icons.Outlined.Block, stringResource(R.string.filter_keywords),
                        s.filterKeywords.lines().filter { it.isNotBlank() }.joinToString(", ").ifEmpty { stringResource(R.string.filter_off) }) { editKeywords = true }
                    Section(stringResource(R.string.section_titles))
                    SwitchItem(Icons.Outlined.Title, stringResource(R.string.settings_dearrow), stringResource(R.string.settings_dearrow_hint), s.deArrow) { v ->
                        update { it.copy(deArrow = v) }
                    }
                }

                Page.CLEANUP -> {
                    Section(stringResource(R.string.section_layout))
                    SwitchItem(Icons.Outlined.Bolt, stringResource(R.string.settings_shorts_tab), null, s.showShortsTab) { v -> update { it.copy(showShortsTab = v) } }
                    SwitchItem(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.settings_hide_comments), null, s.hideComments) { v ->
                        update { it.copy(hideComments = v) }
                    }
                    Section(stringResource(R.string.section_brave_lists))
                    for (comp in extras) {
                        SwitchItem(Icons.Outlined.FilterList, listLabel(comp.uuid, comp.title), null, comp.uuid in s.extraLists, enabled = s.adblockEnabled) { v ->
                            update { it.copy(extraLists = if (v) it.extraLists + comp.uuid else it.extraLists - comp.uuid) }
                        }
                    }
                    Section(stringResource(R.string.section_advanced))
                    Item(Icons.Outlined.Code, stringResource(R.string.settings_custom_css),
                        if (s.customCss.isBlank()) stringResource(R.string.settings_custom_css_none)
                        else stringResource(R.string.settings_custom_css_lines, s.customCss.lines().count { it.isNotBlank() })) { editCss = true }
                }

                Page.APPEARANCE -> {
                    Item(Icons.Outlined.Home, stringResource(R.string.settings_start_page), startPageLabel(s.startPage)) { picker = Picker.START_PAGE }
                    Section(stringResource(R.string.section_colors))
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        SwitchItem(Icons.Outlined.Palette, stringResource(R.string.settings_material_you),
                            stringResource(R.string.settings_material_you_hint), s.materialYou) { v -> update { it.copy(materialYou = v) } }
                    }
                    SwitchItem(Icons.Outlined.DarkMode, stringResource(R.string.settings_amoled),
                        stringResource(R.string.settings_amoled_hint), s.amoled) { v -> update { it.copy(amoled = v) } }
                }

                Page.ACCOUNT -> {
                    Section(stringResource(R.string.settings_links))
                    linksEnabled?.let { enabled ->
                        Item(Icons.Outlined.Link, stringResource(R.string.links_hint_title),
                            stringResource(if (enabled) R.string.settings_links_on else R.string.settings_links_off)) { LinkHandling.openSettings(context) }
                    }
                    Item(Icons.Outlined.Notifications, stringResource(R.string.settings_notifications), stringResource(R.string.settings_notifications_hint)) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                    Section(stringResource(R.string.settings_account))
                    Item(Icons.AutoMirrored.Filled.Logout, stringResource(R.string.settings_sign_out), stringResource(R.string.settings_sign_out_hint)) {
                        confirmSignOut = true
                    }
                }

                Page.ABOUT -> {
                    Item(Icons.Outlined.Info, "YourTube ${BuildConfig.VERSION_NAME}",
                        stringResource(R.string.settings_about_text, runCatching { AdblockEngine.nativeVersion }.getOrDefault("?")))
                    val updater = container.updater
                    Item(Icons.Outlined.SystemUpdate, stringResource(R.string.settings_updates),
                        if (!updater.enabled) stringResource(R.string.update_off) else updateStatusText(updateState)) {
                        when (val u = updateState) {
                            is UpdateState.Available -> updater.download(u.release)
                            is UpdateState.Failed -> u.release?.let(updater::download) ?: updater.check()
                            is UpdateState.Downloading, is UpdateState.Installing, UpdateState.Checking -> Unit
                            else -> updater.check()
                        }
                    }
                    Item(Icons.Outlined.Coffee, stringResource(R.string.settings_support), "ko-fi.com/wuerfelhusten") {
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(KOFI_URL))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (picker) {
        Picker.QUALITY_WIFI -> ChoiceDialog(stringResource(R.string.settings_quality_wifi), QUALITIES, s.qualityWifi, { qualityLabel(it) },
            onDismiss = { picker = null }) { q -> update { it.copy(qualityWifi = q) }; picker = null }
        Picker.QUALITY_MOBILE -> ChoiceDialog(stringResource(R.string.settings_quality_mobile), QUALITIES, s.qualityMobile, { qualityLabel(it) },
            onDismiss = { picker = null }) { q -> update { it.copy(qualityMobile = q) }; picker = null }
        Picker.SPEED -> ChoiceDialog(stringResource(R.string.settings_speed), SPEEDS, s.speed, { speedLabel(it) },
            onDismiss = { picker = null }) { v -> update { it.copy(speed = v) }; picker = null }
        Picker.SPONSOR_MIN -> ChoiceDialog(stringResource(R.string.sb_min_length), listOf(0, 1, 2, 3, 5, 10), s.sponsorMinLength,
            { if (it == 0) stringResource(R.string.filter_off) else stringResource(R.string.settings_seconds, it) },
            onDismiss = { picker = null }) { v -> update { it.copy(sponsorMinLength = v) }; picker = null }
        Picker.MIN_DURATION -> ChoiceDialog(stringResource(R.string.filter_min_duration), MIN_DURATIONS, s.filterMinDuration,
            { if (it <= 0) stringResource(R.string.filter_off) else durationLabel(it) },
            onDismiss = { picker = null }) { v -> update { it.copy(filterMinDuration = v) }; picker = null }
        Picker.DOUBLE_TAP -> ChoiceDialog(stringResource(R.string.settings_double_tap), listOf(5, 10, 15, 20, 30, 60), s.doubleTapSeconds,
            { stringResource(R.string.settings_seconds, it) },
            onDismiss = { picker = null }) { v -> update { it.copy(doubleTapSeconds = v) }; picker = null }
        Picker.START_PAGE -> ChoiceDialog(stringResource(R.string.settings_start_page), listOf("home", "subscriptions", "library", "history"), s.startPage,
            { startPageLabel(it) },
            onDismiss = { picker = null }) { v -> update { it.copy(startPage = v) }; picker = null }
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
            confirmButton = { TextButton(onClick = { update { it.copy(filterKeywords = text) }; editKeywords = false }) { Text(stringResource(R.string.save)) } },
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
            confirmButton = { TextButton(onClick = { update { it.copy(customCss = text) }; editCss = false }) { Text(stringResource(R.string.save)) } },
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

// --- SponsorBlock page -------------------------------------------------------------------------

private val SPONSOR_MODES = listOf("auto", "button", "show", "off")

@Composable
private fun sponsorModeLabel(mode: String): String = stringResource(
    when (mode) {
        "auto" -> R.string.sb_mode_auto
        "button" -> R.string.sb_mode_button
        "show" -> R.string.sb_mode_show
        else -> R.string.sb_mode_off
    },
)

@Composable
private fun ColumnScope.SponsorBlockPage(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, onPicker: (Picker) -> Unit) {
    SwitchItem(Icons.Outlined.FastForward, stringResource(R.string.settings_sponsorblock),
        stringResource(R.string.settings_sponsorblock_hint), s.sponsorBlock) { v -> update { it.copy(sponsorBlock = v) } }

    Section(stringResource(R.string.sb_section_categories))
    for ((cat, labelId) in WebViewHolder.SPONSOR_LABELS) {
        var open by remember { mutableStateOf(false) }
        val mode = s.sponsorModes[cat] ?: "off"
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = s.sponsorBlock) { open = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val alpha = if (s.sponsorBlock) 1f else 0.4f
                Box(Modifier.padding(horizontal = 6.dp).size(12.dp).background(segmentColor(cat).copy(alpha = alpha), CircleShape))
                Spacer(Modifier.width(26.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(labelId), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                    Text(sponsorModeLabel(mode), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (m in SPONSOR_MODES) {
                    DropdownMenuItem(
                        text = { Text(sponsorModeLabel(m)) },
                        leadingIcon = { RadioButton(selected = m == mode, onClick = null) },
                        onClick = {
                            open = false
                            update { it.copy(sponsorModes = it.sponsorModes + (cat to m)) }
                        },
                    )
                }
            }
        }
    }

    Section(stringResource(R.string.sb_section_behaviour))
    SwitchItem(Icons.Outlined.Info, stringResource(R.string.sb_toast), stringResource(R.string.sb_toast_hint), s.sponsorToast, enabled = s.sponsorBlock) { v ->
        update { it.copy(sponsorToast = v) }
    }
    SwitchItem(Icons.Outlined.ViewTimeline, stringResource(R.string.sb_strip), stringResource(R.string.sb_strip_hint), s.sponsorStrip, enabled = s.sponsorBlock) { v ->
        update { it.copy(sponsorStrip = v) }
    }
    SwitchItem(Icons.Outlined.Repeat, stringResource(R.string.sb_skip_once), stringResource(R.string.sb_skip_once_hint), s.sponsorSkipOnce, enabled = s.sponsorBlock) { v ->
        update { it.copy(sponsorSkipOnce = v) }
    }
    Item(Icons.Outlined.Timer, stringResource(R.string.sb_min_length),
        if (s.sponsorMinLength == 0) stringResource(R.string.filter_off) else stringResource(R.string.settings_seconds, s.sponsorMinLength)) {
        if (s.sponsorBlock) onPicker(Picker.SPONSOR_MIN)
    }

    Section(stringResource(R.string.settings_about))
    Text(stringResource(R.string.sb_about), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun NavItem(icon: ImageVector, target: Page, summary: String, onOpen: (Page) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(target) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(target.title), style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val KOFI_URL = "https://ko-fi.com/wuerfelhusten"

private enum class Picker { QUALITY_WIFI, QUALITY_MOBILE, SPEED, SPONSOR_MIN, MIN_DURATION, DOUBLE_TAP, START_PAGE }

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
