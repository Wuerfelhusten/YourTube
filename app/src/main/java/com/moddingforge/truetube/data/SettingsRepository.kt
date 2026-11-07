package com.moddingforge.truetube.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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

/** All user settings of TrueTube ("TrueTube Settings"). Defaults = behaviour of the official app + ad blocking. */
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
    val sponsorCategories: Set<String> = setOf("sponsor", "selfpromo", "interaction"),
    val returnDislikes: Boolean = true,
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
        val sponsorCategories = stringSetPreferencesKey("sponsorblock_categories")
        val ryd = booleanPreferencesKey("return_dislikes")
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
        sponsorCategories = this[Keys.sponsorCategories] ?: setOf("sponsor", "selfpromo", "interaction"),
        returnDislikes = this[Keys.ryd] ?: true,
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
                p[Keys.sponsorCategories] = s.sponsorCategories
                p[Keys.ryd] = s.returnDislikes
            }
        }
    }
}
