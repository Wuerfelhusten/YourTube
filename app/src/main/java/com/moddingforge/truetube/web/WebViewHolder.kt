package com.moddingforge.truetube.web

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.moddingforge.truetube.BuildConfig
import com.moddingforge.truetube.adblock.EngineState
import com.moddingforge.truetube.adblock.FilterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    /** A Google account is signed in to YouTube (based on the session cookies). */
    val signedIn: Boolean = false,
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
    private val filters: FilterRepository,
    scope: CoroutineScope,
) {
    private val contextWrapper = MutableContextWrapper(app)
    private var webView: WebView? = null

    private val _state = MutableStateFlow(WebState())
    val state: StateFlow<WebState> = _state.asStateFlow()

    val bridge = Bridge(json, ::onBridgeMessage)
    val injector = ScriptInjector(app.assets, json, InjectConfig(version = BuildConfig.VERSION_CODE, debug = BuildConfig.DEBUG))
    val adblock = AdblockIntegration(filters, json)

    private var pingSentAt = 0L

    /** First URL to load, held back until the adblock engine is ready (or failed / timed out). */
    private var pendingInitialUrl: String? = null
    private var adblockSettled = false

    init {
        scope.launch(Dispatchers.Main.immediate) {
            // Give the engine a moment so the very first page already gets Brave's scriptlets.
            withTimeoutOrNull(ENGINE_WAIT_MS) { filters.state.first { it !is EngineState.Loading } }
            filters.state.collect { st ->
                adblockSettled = true
                if (st is EngineState.Ready) webView?.let { adblock.install(it, st.engine) }
                pendingInitialUrl?.let { url ->
                    pendingInitialUrl = null
                    webView?.loadUrl(url)
                }
            }
        }
    }

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
        if (wv.url == null && pendingInitialUrl == null) load(url)
    }

    fun load(url: String) {
        val wv = webView ?: return
        if (!adblockSettled) {
            pendingInitialUrl = url
            _state.update { it.copy(progress = 5) }
        } else {
            wv.loadUrl(url)
        }
    }

    /**
     * Bottom tab pressed. Same tab again: scroll to top, or reload if already at the top
     * (like the official app). Otherwise navigate in-page via m.youtube's pivot bar.
     */
    fun selectTab(tab: Tab) {
        val wv = webView ?: return
        val current = Page.of(_state.value.url)
        when {
            current == tab.page && wv.scrollY > 0 -> bridge.send(BridgeCommand.ScrollTop)
            current == tab.page -> wv.reload()
            _state.value.bridgeReady && UrlRouter.isYouTube(android.net.Uri.parse(_state.value.url).host.orEmpty()) ->
                bridge.send(BridgeCommand.NavTab(tab.pivotIndex, tab.url))
            else -> load(tab.url)
        }
    }

    fun reload() {
        webView?.reload()
    }

    fun search(query: String) {
        load("https://m.youtube.com/results?search_query=" + android.net.Uri.encode(query.trim()))
    }

    /** Debug builds: evaluate JS in the page and log the (JSON) result in chunks under TT.Eval. */
    fun debugEval(js: String) {
        if (!BuildConfig.DEBUG) return
        webView?.evaluateJavascript(js) { result ->
            result.orEmpty().chunked(3000).forEachIndexed { i, part -> Log.i("TT.Eval", "[$i] $part") }
        }
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
        refreshSignIn()
    }

    /** Opens Google's sign-in page for YouTube; Google redirects back to m.youtube.com afterwards. */
    fun signIn() {
        load(UrlRouter.SIGN_IN)
    }

    /** Removes all cookies and web storage (= signs out) and reloads the start page. */
    fun signOut() {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            refreshSignIn()
            load(UrlRouter.HOME)
        }
    }

    /** Re-reads the YouTube session cookies. Cheap; called after every page load / navigation. */
    fun refreshSignIn() {
        val signedIn = Account.isSignedIn(CookieManager.getInstance())
        if (signedIn != _state.value.signedIn) {
            Log.i(TAG, if (signedIn) "signed in" else "signed out")
            _state.update { it.copy(signedIn = signedIn) }
            // Persist the fresh session immediately instead of waiting for onStop.
            CookieManager.getInstance().flush()
        }
    }

    fun onStop() {
        // M6 will keep the WebView running while a video plays in the background.
        webView?.onPause()
        CookieManager.getInstance().flush()
    }

    private var serviceWorkerHooked = false

    /** Requests made by YouTube's service worker bypass WebViewClient; route them through the engine too. */
    private fun hookServiceWorker() {
        if (serviceWorkerHooked || !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) return
        ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = adblock.intercept(request)
        })
        serviceWorkerHooked = true
    }

    private fun create(): WebView {
        hookServiceWorker()
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
            // Order matters: the core bundle first, then Brave's per-host scripts.
            filters.engine?.let { adblock.install(this, it) }
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
        adblock.pageUrl = url
        bridge.reset()
        _state.update { it.copy(url = url, bridgeReady = false) }
        injector.injectFallback(view)
    }

    internal fun onPageFinished(url: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "page finished: $url")
        refreshSignIn()
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
        adblock.reset()
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
            is BridgeMessage.CosmeticClassIds -> {
                val selectors = adblock.classIdSelectors(msg.host, msg.classes, msg.ids)
                if (selectors.isNotEmpty()) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "generic hide: ${selectors.size} selectors for ${msg.classes.size} classes/${msg.ids.size} ids")
                    bridge.send(BridgeCommand.CosmeticHide(selectors))
                }
            }
            BridgeMessage.AdSkipped -> {
                val n = adblock.skippedAds.incrementAndGet()
                Log.w(TAG, "fallback skipped a video ad (#$n) on ${_state.value.url}")
            }
            is BridgeMessage.Navigation -> {
                adblock.pageUrl = msg.url
                Log.i(Bridge.TAG, "navigation ${msg.url} | ${msg.title}")
                _state.update { it.copy(url = msg.url, title = msg.title, canGoBack = webView?.canGoBack() ?: false) }
                refreshSignIn()
            }
            is BridgeMessage.Log -> Log.println(
                when (msg.level) { "error" -> Log.ERROR; "warn" -> Log.WARN; "debug" -> Log.DEBUG; else -> Log.INFO },
                "TT.JS", msg.message,
            )
        }
    }

    companion object {
        private const val TAG = "TT.WebView"
        private const val ENGINE_WAIT_MS = 3_000L
    }
}

/** Sign-in state derived from Google's session cookies on youtube.com. */
object Account {
    private const val COOKIE_URL = "https://www.youtube.com"
    // SAPISID / __Secure-3PAPISID only exist for a signed-in Google session.
    private val sessionCookie = Regex("""(^|;\s*)(SAPISID|__Secure-3PAPISID)=""")

    fun isSignedIn(cookies: CookieManager): Boolean =
        cookies.getCookie(COOKIE_URL)?.let { sessionCookie.containsMatchIn(it) } ?: false
}

/** Default WebView UA without the "; wv" and "Version/4.0" markers, so it looks like Chrome Mobile. */
object UserAgent {
    private val versionToken = Regex("""Version/\d+(\.\d+)*\s""")

    fun build(context: Context): String =
        WebSettings.getDefaultUserAgent(context)
            .replace("; wv)", ")")
            .replace(versionToken, "")
}
