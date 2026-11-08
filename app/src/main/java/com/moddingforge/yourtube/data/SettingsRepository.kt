package com.moddingforge.yourtube.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** All user settings of YourTube ("YourTube Settings"). Defaults = behaviour of the official app + ad blocking. */
data class AppSettings(
    // ad blocking (Brave engine)
    val adblockEnabled: Boolean = true,
    val cookieNoticeBlocking: Boolean = true,
    /** Optional Brave catalog components (YouTube clean-up lists) by uuid. */
    val extraLists: Set<String> = emptySet(),
    // playback
    val backgroundPlayback: Boolean = true,
    val autoPip: Boolean = true,
    val rotateToFullscreen: Boolean = true,
    // appearance
    val showShortsTab: Boolean = true,
    val hideComments: Boolean = false,
    val customCss: String = "",
    // player extras
    /** YouTube quality names: auto, hd2160, hd1440, hd1080, hd720, large, medium, small, tiny */
    val qualityWifi: String = "auto",
    val qualityMobile: String = "auto",
    val speed: Float = 1f,
    val sponsorBlock: Boolean = true,
    /** SponsorBlock action per category: auto | button | show | off. */
    val sponsorModes: Map<String, String> = DEFAULT_SPONSOR_MODES,
    /** Toast after an automatic skip. */
    val sponsorToast: Boolean = true,
    /** Coloured segment strip under the video. */
    val sponsorStrip: Boolean = true,
    /** Ignore segments shorter than this (seconds). */
    val sponsorMinLength: Int = 0,
    /** Skip a segment only once (seeking back into it plays it). */
    val sponsorSkipOnce: Boolean = true,
    val returnDislikes: Boolean = true,
    // data-layer feed filters (05-data-filter.js) + DeArrow
    val filterShorts: Boolean = false,
    val filterPosts: Boolean = false,
    val filterMixes: Boolean = false,
    val filterWatched: Boolean = false,
    /** Hide videos shorter than this (seconds); 0 = off. */
    val filterMinDuration: Int = 0,
    /** Hide videos whose title or channel contains one of these (one per line, case-insensitive). */
    val filterKeywords: String = "",
    val deArrow: Boolean = false,
    // player behaviour
    val originalAudio: Boolean = true,
    val noAutoCaptions: Boolean = false,
    val doubleTapSeconds: Int = 10,
    // app
    /** home | subscriptions | library | history */
    val startPage: String = "home",
    val materialYou: Boolean = false,
    val amoled: Boolean = false,
    // branding
    /** Top bar brand: default (YourTube logo + name) | custom (own text / image) | off */
    val brandMode: String = "default",
    val brandText: String = "",
    /** Change stamp of the custom logo image (files/branding/logo.png); 0 = no image. */
    val brandLogo: Long = 0,
    /** Launcher icon variant (activity-alias), see ui/settings/AppIcons.kt. */
    val appIcon: String = "default",
)

/** SponsorBlock defaults: skip what clearly is not content, offer a button for the rest. */
val DEFAULT_SPONSOR_MODES = linkedMapOf(
    "sponsor" to "auto",
    "selfpromo" to "auto",
    "interaction" to "auto",
    "intro" to "button",
    "outro" to "button",
    "preview" to "show",
    "music_offtopic" to "button",
    "filler" to "show",
)

private val Context.settingsStore by preferencesDataStore(name = "settings")

/**
 * DataStore-backed settings, exposed as a hot [StateFlow] so every part of the app can react live.
 * The first value is read synchronously once at startup (tiny file) so the engine and WebViews
 * start with the right configuration.
 */
class SettingsRepository(context: Context, private val scope: CoroutineScope) {
    private val store = context.applicationContext.settingsStore

    private object Keys {
        val adblock = booleanPreferencesKey("adblock_enabled")
        val cookies = booleanPreferencesKey("cookie_notice_blocking")
        val extraLists = stringSetPreferencesKey("extra_lists")
        val background = booleanPreferencesKey("background_playback")
        val autoPip = booleanPreferencesKey("auto_pip")
        val rotate = booleanPreferencesKey("rotate_to_fullscreen")
        val shortsTab = booleanPreferencesKey("show_shorts_tab")
        val hideComments = booleanPreferencesKey("hide_comments")
        val customCss = stringPreferencesKey("custom_css")
        val qualityWifi = stringPreferencesKey("quality_wifi")
        val qualityMobile = stringPreferencesKey("quality_mobile")
        val speed = floatPreferencesKey("speed")
        val sponsorBlock = booleanPreferencesKey("sponsorblock")
        val sponsorModes = stringSetPreferencesKey("sponsorblock_modes")
        val sponsorToast = booleanPreferencesKey("sponsorblock_toast")
        val sponsorStrip = booleanPreferencesKey("sponsorblock_strip")
        val sponsorMinLength = intPreferencesKey("sponsorblock_min_length")
        val sponsorSkipOnce = booleanPreferencesKey("sponsorblock_skip_once")
        val ryd = booleanPreferencesKey("return_dislikes")
        val fShorts = booleanPreferencesKey("filter_shorts")
        val fPosts = booleanPreferencesKey("filter_posts")
        val fMixes = booleanPreferencesKey("filter_mixes")
        val fWatched = booleanPreferencesKey("filter_watched")
        val fMinDur = intPreferencesKey("filter_min_duration")
        val fKeywords = stringPreferencesKey("filter_keywords")
        val deArrow = booleanPreferencesKey("dearrow")
        val origAudio = booleanPreferencesKey("original_audio")
        val noCaptions = booleanPreferencesKey("no_auto_captions")
        val dtSeconds = intPreferencesKey("double_tap_seconds")
        val startPage = stringPreferencesKey("start_page")
        val materialYou = booleanPreferencesKey("material_you")
        val amoled = booleanPreferencesKey("amoled")
        val brandMode = stringPreferencesKey("brand_mode")
        val brandText = stringPreferencesKey("brand_text")
        val brandLogo = longPreferencesKey("brand_logo")
        val appIcon = stringPreferencesKey("app_icon")
    }

    private fun Preferences.toSettings() = AppSettings(
        adblockEnabled = this[Keys.adblock] ?: true,
        cookieNoticeBlocking = this[Keys.cookies] ?: true,
        extraLists = this[Keys.extraLists] ?: emptySet(),
        backgroundPlayback = this[Keys.background] ?: true,
        autoPip = this[Keys.autoPip] ?: true,
        rotateToFullscreen = this[Keys.rotate] ?: true,
        showShortsTab = this[Keys.shortsTab] ?: true,
        hideComments = this[Keys.hideComments] ?: false,
        customCss = this[Keys.customCss] ?: "",
        qualityWifi = this[Keys.qualityWifi] ?: "auto",
        qualityMobile = this[Keys.qualityMobile] ?: "auto",
        speed = this[Keys.speed] ?: 1f,
        sponsorBlock = this[Keys.sponsorBlock] ?: true,
        sponsorModes = this[Keys.sponsorModes]?.associate { it.substringBefore(":") to it.substringAfter(":") }
            ?.let { DEFAULT_SPONSOR_MODES + it } ?: DEFAULT_SPONSOR_MODES,
        sponsorToast = this[Keys.sponsorToast] ?: true,
        sponsorStrip = this[Keys.sponsorStrip] ?: true,
        sponsorMinLength = this[Keys.sponsorMinLength] ?: 0,
        sponsorSkipOnce = this[Keys.sponsorSkipOnce] ?: true,
        returnDislikes = this[Keys.ryd] ?: true,
        filterShorts = this[Keys.fShorts] ?: false,
        filterPosts = this[Keys.fPosts] ?: false,
        filterMixes = this[Keys.fMixes] ?: false,
        filterWatched = this[Keys.fWatched] ?: false,
        filterMinDuration = this[Keys.fMinDur] ?: 0,
        filterKeywords = this[Keys.fKeywords] ?: "",
        deArrow = this[Keys.deArrow] ?: false,
        originalAudio = this[Keys.origAudio] ?: true,
        noAutoCaptions = this[Keys.noCaptions] ?: false,
        doubleTapSeconds = this[Keys.dtSeconds] ?: 10,
        startPage = this[Keys.startPage] ?: "home",
        materialYou = this[Keys.materialYou] ?: false,
        amoled = this[Keys.amoled] ?: false,
        brandMode = this[Keys.brandMode] ?: "default",
        brandText = this[Keys.brandText] ?: "",
        brandLogo = this[Keys.brandLogo] ?: 0L,
        appIcon = this[Keys.appIcon] ?: "default",
    )

    private val initial: AppSettings = runBlocking { store.data.first().toSettings() }

    val settings: StateFlow<AppSettings> =
        store.data.map { it.toSettings() }.stateIn(scope, SharingStarted.Eagerly, initial)

    val current: AppSettings get() = settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch {
            store.edit { p ->
                val s = transform(p.toSettings())
                p[Keys.adblock] = s.adblockEnabled
                p[Keys.cookies] = s.cookieNoticeBlocking
                p[Keys.extraLists] = s.extraLists
                p[Keys.background] = s.backgroundPlayback
                p[Keys.autoPip] = s.autoPip
                p[Keys.rotate] = s.rotateToFullscreen
                p[Keys.shortsTab] = s.showShortsTab
                p[Keys.hideComments] = s.hideComments
                p[Keys.customCss] = s.customCss
                p[Keys.qualityWifi] = s.qualityWifi
                p[Keys.qualityMobile] = s.qualityMobile
                p[Keys.speed] = s.speed
                p[Keys.sponsorBlock] = s.sponsorBlock
                p[Keys.sponsorModes] = s.sponsorModes.map { (k, v) -> "$k:$v" }.toSet()
                p[Keys.sponsorToast] = s.sponsorToast
                p[Keys.sponsorStrip] = s.sponsorStrip
                p[Keys.sponsorMinLength] = s.sponsorMinLength
                p[Keys.sponsorSkipOnce] = s.sponsorSkipOnce
                p[Keys.ryd] = s.returnDislikes
                p[Keys.fShorts] = s.filterShorts
                p[Keys.fPosts] = s.filterPosts
                p[Keys.fMixes] = s.filterMixes
                p[Keys.fWatched] = s.filterWatched
                p[Keys.fMinDur] = s.filterMinDuration
                p[Keys.fKeywords] = s.filterKeywords
                p[Keys.deArrow] = s.deArrow
                p[Keys.origAudio] = s.originalAudio
                p[Keys.noCaptions] = s.noAutoCaptions
                p[Keys.dtSeconds] = s.doubleTapSeconds
                p[Keys.startPage] = s.startPage
                p[Keys.materialYou] = s.materialYou
                p[Keys.amoled] = s.amoled
                p[Keys.brandMode] = s.brandMode
                p[Keys.brandText] = s.brandText
                p[Keys.brandLogo] = s.brandLogo
                p[Keys.appIcon] = s.appIcon
            }
        }
    }
}
