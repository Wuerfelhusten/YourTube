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
    val filterRepository = FilterRepository(app, json, appScope, debug = BuildConfig.DEBUG)
    /** Feed WebView: everything except watch pages. */
    val feed = WebViewHolder(app, json, filterRepository, appScope, Role.FEED)
    /** Player WebView: watch pages only (full screen or mini player). */
    val player = WebViewHolder(app, json, filterRepository, appScope, Role.PLAYER)
    val playerController = PlayerController(feed, player)
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val playbackConnector = PlaybackConnector(app, playerController, appScope)
}
