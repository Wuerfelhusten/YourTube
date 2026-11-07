package com.moddingforge.truetube

import android.app.Application
import android.webkit.WebView
import com.moddingforge.truetube.adblock.AdblockUpdateWorker
import com.moddingforge.truetube.adblock.FilterRepository
import com.moddingforge.truetube.playback.PlaybackConnector
import com.moddingforge.truetube.web.PlayerController
import com.moddingforge.truetube.web.Role
import com.moddingforge.truetube.web.WebViewHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import com.moddingforge.truetube.data.AppSettings
import com.moddingforge.truetube.data.SettingsRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class TrueTubeApp : Application() {
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

/** Manual DI container. Everything here lives as long as the process. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }
    val settings = SettingsRepository(app, appScope)
    val filterRepository = FilterRepository(app, json, appScope, debug = BuildConfig.DEBUG, settings = settings)
    /** Feed WebView: everything except watch pages. */
    val feed = WebViewHolder(app, json, filterRepository, appScope, Role.FEED, settings)
    /** Player WebView: watch pages only (full screen or mini player). */
    val player = WebViewHolder(app, json, filterRepository, appScope, Role.PLAYER, settings)
    val playerController = PlayerController(feed, player, settings)
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val playbackConnector = PlaybackConnector(app, playerController, settings, appScope)

    init {
        // Settings that act on the pages live.
        appScope.launch(Dispatchers.Main) {
            settings.settings.map { it.adblockEnabled }.distinctUntilChanged().drop(1).collect {
                feed.refreshAdblock()
                player.refreshAdblock()
            }
        }
        appScope.launch(Dispatchers.Main) {
            settings.settings.map { userCss(it) }.distinctUntilChanged().collect { css ->
                feed.userCss = css
                player.userCss = css
            }
        }
    }

    private fun userCss(s: AppSettings): String = buildString {
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
