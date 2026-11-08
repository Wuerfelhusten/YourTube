package com.moddingforge.yourtube

import android.app.Application
import android.webkit.WebView
import com.moddingforge.yourtube.adblock.AdblockUpdateWorker
import com.moddingforge.yourtube.adblock.FilterRepository
import com.moddingforge.yourtube.playback.PlaybackConnector
import com.moddingforge.yourtube.playback.PlayerExtras
import com.moddingforge.yourtube.playback.DeArrowService
import com.moddingforge.yourtube.web.FilterRules
import com.moddingforge.yourtube.web.PlayerController
import com.moddingforge.yourtube.web.Role
import com.moddingforge.yourtube.web.WebViewHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import com.moddingforge.yourtube.data.AppSettings
import com.moddingforge.yourtube.data.SettingsRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class YourTubeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Allows inspecting the WebView via chrome://inspect on the PC.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        // Build the adblock engine in the background right away.
        container.filterRepository.load()
        AdblockUpdateWorker.schedule(this)
    }
}

/** One-shot requests from outside the shell (shortcuts) to the Compose UI. */
sealed interface ShellRequest {
    data object OpenSearch : ShellRequest
    /** A link from outside (notification, share, other app): close search, inbox and settings. */
    data object CloseOverlays : ShellRequest
}

/** Manual DI container. Everything here lives as long as the process. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }
    val settings = SettingsRepository(app, appScope)
    /** Pending request for the shell; consumed (set to null) by AppShell. */
    val shellRequests = kotlinx.coroutines.flow.MutableStateFlow<ShellRequest?>(null)
    val filterRepository = FilterRepository(app, json, appScope, debug = BuildConfig.DEBUG, settings = settings)
    /** Feed WebView: everything except watch pages. */
    val feed = WebViewHolder(app, json, filterRepository, appScope, Role.FEED, settings)
    /** Player WebView: watch pages only (full screen or mini player). */
    val player = WebViewHolder(app, json, filterRepository, appScope, Role.PLAYER, settings)
    val playerController = PlayerController(feed, player, settings)
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val playbackConnector = PlaybackConnector(app, playerController, settings, appScope)
    val playerExtras = PlayerExtras(app, player, settings, json, appScope)
    val deArrow = DeArrowService(listOf(feed, player), settings, json, appScope)
    val updater = com.moddingforge.yourtube.update.Updater(app, json, appScope, settings)
    val notifier = com.moddingforge.yourtube.notify.Notifier(app, settings).also { n ->
        // The active channel (brand accounts) for the app's own youtubei requests.
        feed.onSession = { if (it.loggedIn) n.setPageId(it.pageId) }
        player.onSession = { if (it.loggedIn) n.setPageId(it.pageId) }
    }

    init {
        // Settings that act on the pages live.
        appScope.launch(Dispatchers.Main) {
            settings.settings.map { it.adblockEnabled }.distinctUntilChanged().drop(1).collect {
                feed.refreshAdblock()
                player.refreshAdblock()
            }
        }
        appScope.launch(Dispatchers.Main) {
            settings.settings.map { FilterRules.from(it) }.distinctUntilChanged().drop(1).collect {
                feed.refreshDataRules(reload = true)
                player.refreshDataRules(reload = false)
            }
        }
        appScope.launch(Dispatchers.Main) {
            settings.settings.map { userCss(it) }.distinctUntilChanged().collect { css ->
                feed.userCss = css
                player.userCss = css
            }
        }
        // Background notification checks follow the settings (and survive app restarts).
        appScope.launch {
            settings.settings.map { listOf(it.notifyVideos, it.notifyInbox, it.notifyInterval, it.notifyWifiOnly) }.distinctUntilChanged().collect {
                com.moddingforge.yourtube.notify.NotifyWorker.schedule(app, settings.current)
            }
        }
    }

    private fun userCss(s: AppSettings): String = buildString {
        if (s.amoled) {
            // YouTube dark theme -> pure black (its colours are CSS variables).
            append("@media (prefers-color-scheme: dark) { html { --yt-spec-base-background: #000 !important; --yt-spec-general-background-a: #000 !important; ")
            append("--yt-spec-brand-background-solid: #000 !important; --yt-spec-raised-background: #0f0f0f !important; } html, body, ytm-app { background: #000 !important; } ")
            append("html { --yt-spec-brand-background-primary: #000 !important; --yt-spec-brand-background-secondary: #000 !important; } ")
            append("ytm-feed-filter-chip-bar-renderer, #filter-chip-bar, .frosted-glass, ytm-channel-list-sub-menu-renderer, ")
            append("ytm-pivot-bar-renderer, ytm-mobile-topbar-renderer { background: #000 !important; backdrop-filter: none !important; } }\n")
        }
        if (s.hideComments) {
            // The comment teaser under the video is a "metadata carousel" in current m.youtube.
            append("yt-video-metadata-carousel-view-model, ")
            append("ytm-comments-entry-point-header-renderer, ytm-comment-section-renderer, ")
            append("ytm-item-section-renderer[section-identifier=\"comment-item-section\"], ")
            append("ytm-engagement-panel-section-list-renderer[target-id*=\"comment\"] { display: none !important; }\n")
        }
        append(s.customCss)
    }
}
