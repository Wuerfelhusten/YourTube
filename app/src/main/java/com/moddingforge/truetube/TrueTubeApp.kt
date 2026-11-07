package com.moddingforge.truetube

import android.app.Application
import android.webkit.WebView
import com.moddingforge.truetube.web.WebViewHolder
import kotlinx.serialization.json.Json

class TrueTubeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Allows inspecting the WebView via chrome://inspect on the PC.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }
}

/** Manual DI container. Everything here lives as long as the process. */
class AppContainer(app: Application) {
    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }
    val webViewHolder = WebViewHolder(app, json)
}
