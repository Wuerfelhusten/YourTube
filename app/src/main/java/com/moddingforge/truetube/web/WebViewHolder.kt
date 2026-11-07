package com.moddingforge.truetube.web

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.moddingforge.truetube.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/** Snapshot of the WebView for the native UI. */
data class WebState(
    val url: String = "",
    val title: String = "",
    /** 0..100, 100 = idle. */
    val progress: Int = 100,
    val canGoBack: Boolean = false,
    val bridgeReady: Boolean = false,
    /** Round-trip time of the last ping, -1 if none yet. */
    val bridgePingMs: Long = -1,
    /** Bumped when the WebView had to be recreated (renderer crash). */
    val generation: Int = 0,
)

/**
 * Owns the single, long-lived WebView. It is application-scoped so playback survives
 * rotation, PiP and activity recreation. The WebView's context is a MutableContextWrapper
 * that points at the current activity while attached, and at the application otherwise.
 */
class WebViewHolder(
    private val app: Application,
    private val json: Json,
) {
    private val contextWrapper = MutableContextWrapper(app)
    private var webView: WebView? = null

    private val _state = MutableStateFlow(WebState())
    val state: StateFlow<WebState> = _state.asStateFlow()

    val bridge = Bridge(json, ::onBridgeMessage)
    val injector = ScriptInjector(app.assets, json, InjectConfig(version = BuildConfig.VERSION_CODE, debug = BuildConfig.DEBUG))

    private var pingSentAt = 0L

    /** Returns the WebView, re-parented for [activity]. Creates it on first use. */
    fun attach(activity: Activity): WebView {
        contextWrapper.baseContext = activity
        val wv = webView ?: create().also { webView = it }
        (wv.parent as? ViewGroup)?.removeView(wv)
        return wv
    }

    fun detach(activity: Activity) {
        webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        if (contextWrapper.baseContext === activity) contextWrapper.baseContext = app
    }

    fun loadIfEmpty(url: String = UrlRouter.HOME) {
        val wv = webView ?: return
        if (wv.url == null) wv.loadUrl(url)
    }

    fun load(url: String) {
        webView?.loadUrl(url)
    }

    /** @return true if the WebView navigated back. */
    fun goBack(): Boolean {
        val wv = webView ?: return false
        if (!wv.canGoBack()) return false
        wv.goBack()
        return true
    }

    fun onStart() {
        webView?.onResume()
    }

    fun onStop() {
        // M6 will keep the WebView running while a video plays in the background.
        webView?.onPause()
        CookieManager.getInstance().flush()
    }

    private fun create(): WebView {
        val context: Context = contextWrapper
        return WebView(context).apply wv@{
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
            configureSettings(settings)

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(this@wv, true)
            }

            webViewClient = TrueWebViewClient(this@WebViewHolder)
            webChromeClient = TrueChromeClient(this@WebViewHolder)

            injector.install(this)
            bridge.install(this)
        }
    }

    // REQUESTED_WITH_HEADER_ALLOW_LIST is deprecated but still the only way to drop the header (needed for M2 login).
    @Suppress("DEPRECATION")
    private fun configureSettings(s: WebSettings) {
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.userAgentString = UserAgent.build(app)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // YouTube has its own dark theme; it follows prefers-color-scheme (driven by the app theme).
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            // Don't send X-Requested-With: <package> - Google uses it to detect embedded WebViews.
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }
    }

    // --- callbacks from clients -------------------------------------------------------------

    internal fun onPageStarted(view: WebView, url: String) {
        bridge.reset()
        _state.update { it.copy(url = url, bridgeReady = false) }
        injector.injectFallback(view)
    }

    internal fun onHistoryChanged(view: WebView, url: String) {
        _state.update { it.copy(url = url, canGoBack = view.canGoBack()) }
    }

    internal fun onProgress(progress: Int) {
        _state.update { it.copy(progress = progress) }
    }

    internal fun onTitle(title: String) {
        _state.update { it.copy(title = title) }
    }

    /** Renderer died (OOM or crash). Drop the WebView and let the UI create a fresh one. */
    internal fun onRenderProcessGone(view: WebView) {
        Log.e(TAG, "WebView renderer gone - recreating")
        val lastUrl = view.url
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        if (webView === view) webView = null
        bridge.reset()
        _state.update { WebState(url = lastUrl.orEmpty(), generation = it.generation + 1) }
        pendingUrl = lastUrl
    }

    private var pendingUrl: String? = null

    /** URL to restore after a renderer crash, consumed once. */
    fun consumePendingUrl(): String = (pendingUrl ?: UrlRouter.HOME).also { pendingUrl = null }

    private fun onBridgeMessage(msg: BridgeMessage) {
        when (msg) {
            is BridgeMessage.Ready -> {
                Log.i(Bridge.TAG, "ready v${msg.version} @ ${msg.url}")
                _state.update { it.copy(bridgeReady = true) }
                pingSentAt = System.currentTimeMillis()
                bridge.send(BridgeCommand.Ping(pingSentAt))
            }
            is BridgeMessage.Pong -> {
                val rtt = System.currentTimeMillis() - msg.nonce
                Log.i(Bridge.TAG, "pong after ${rtt}ms")
                _state.update { it.copy(bridgePingMs = rtt) }
            }
            is BridgeMessage.Navigation -> {
                Log.i(Bridge.TAG, "navigation ${msg.url} | ${msg.title}")
                _state.update { it.copy(url = msg.url, title = msg.title, canGoBack = webView?.canGoBack() ?: false) }
            }
            is BridgeMessage.Log -> Log.println(
                when (msg.level) { "error" -> Log.ERROR; "warn" -> Log.WARN; "debug" -> Log.DEBUG; else -> Log.INFO },
                "TT.JS", msg.message,
            )
        }
    }

    companion object {
        private const val TAG = "TT.WebView"
    }
}

/** Default WebView UA without the "; wv" and "Version/4.0" markers, so it looks like Chrome Mobile. */
object UserAgent {
    private val versionToken = Regex("""Version/\d+(\.\d+)*\s""")

    fun build(context: Context): String =
        WebSettings.getDefaultUserAgent(context)
            .replace("; wv)", ")")
            .replace(versionToken, "")
}
