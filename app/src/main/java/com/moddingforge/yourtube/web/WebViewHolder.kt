package com.moddingforge.yourtube.web

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
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
import com.moddingforge.yourtube.BuildConfig
import com.moddingforge.yourtube.adblock.EngineState
import com.moddingforge.yourtube.adblock.FilterRepository
import com.moddingforge.yourtube.data.SettingsRepository
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
 * YourTube uses two WebViews, like the official app separates browsing from the player:
 *  - FEED shows everything except the watch page (home, Shorts, subscriptions, search, channels).
 *  - PLAYER shows only /watch pages; it can be minimized to a mini player while the feed stays usable.
 */
enum class Role { FEED, PLAYER }

/** Now-playing info reported by the player page (30-player.js). */
data class VideoState(
    val videoId: String = "",
    val title: String = "",
    val channel: String = "",
    val playing: Boolean = false,
    val position: Double = 0.0,
    val duration: Double = 0.0,
)

/**
 * Owns one long-lived WebView. It is application-scoped so playback survives
 * rotation, PiP and activity recreation. The WebView's context is a MutableContextWrapper
 * that points at the current activity while attached, and at the application otherwise.
 */
class WebViewHolder(
    private val app: Application,
    private val json: Json,
    private val filters: FilterRepository,
    scope: CoroutineScope,
    val role: Role,
    private val settings: SettingsRepository,
) {
    private val contextWrapper = MutableContextWrapper(app)
    private var webView: WebView? = null

    private val _state = MutableStateFlow(WebState())
    val state: StateFlow<WebState> = _state.asStateFlow()

    private val _video = MutableStateFlow(VideoState())
    val video: StateFlow<VideoState> = _video.asStateFlow()

    /** Set by [PlayerController]: where watch links / non-watch links have to go. */
    var onOpenVideo: (String) -> Unit = {}
    var onOpenInFeed: (String) -> Unit = {}
    var onFullscreen: (Boolean) -> Unit = {}
    var onOpenSettings: () -> Unit = {}
    /** Page reported its YouTube session (active channel). */
    var onSession: (BridgeMessage.Session) -> Unit = {}

    /** Player: "Skip <category>" button wanted (SponsorBlock "button" mode), null = hidden. */
    private val _sponsorPrompt = MutableStateFlow<BridgeMessage.SponsorPrompt?>(null)
    val sponsorPrompt: StateFlow<BridgeMessage.SponsorPrompt?> = _sponsorPrompt.asStateFlow()

    /** Player: YouTube's controls are showing (segments are then drawn on its seekbar instead of the native strip). */
    private val _controlsVisible = MutableStateFlow(false)
    val controlsVisible: StateFlow<Boolean> = _controlsVisible.asStateFlow()

    fun sponsorLabel(category: String): String {
        val id = SPONSOR_LABELS[category] ?: return category
        return app.getString(id)
    }

    /** Native share sheet with a clean link (youtu.be for videos, no tracking parameters). */
    fun share(url: String?, title: String?) {
        val link = UrlRouter.shareUrl(url?.takeIf { it.isNotBlank() } ?: _state.value.url)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, link)
            .apply { if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title.removeSuffix(" - YouTube")) }
        val chooser = Intent.createChooser(send, null)
            // Don't offer "share to YourTube" while sharing from YourTube.
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(app, "com.moddingforge.yourtube.ui.MainActivity")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Log.i(TAG, "[$role] share $link")
        runCatching { contextWrapper.startActivity(chooser) }.onFailure { Log.w(TAG, "share failed", it) }
    }

    val bridge = Bridge(json, ::onBridgeMessage)
    val injector = ScriptInjector(app.assets, json, InjectConfig(version = BuildConfig.VERSION_CODE, debug = BuildConfig.DEBUG, role = role.name.lowercase()))
    val adblock = AdblockIntegration(filters, json, enabled = { settings.current.adblockEnabled })
    private val dataRules = DataRulesScript(json)

    /** Video ids reported by the page (for DeArrow); set by DeArrowService. */
    var onVideoIds: (List<String>) -> Unit = {}

    /** Feed filter / DeArrow settings changed: new rules apply from the next page load on. */
    fun refreshDataRules(reload: Boolean) {
        val wv = webView ?: return
        dataRules.apply(wv, FilterRules.from(settings.current))
        if (reload && wv.url != null && wv.url != "about:blank") wv.reload()
    }

    /** Ad blocker switched on/off: (un)register Brave's page scripts and reload so it applies. */
    fun refreshAdblock() {
        val wv = webView ?: return
        val engine = filters.engine
        if (settings.current.adblockEnabled && engine != null) adblock.install(wv, engine) else adblock.uninstall()
        if (wv.url != null && wv.url != "about:blank") wv.reload()
    }

    /** User CSS (hide comments, custom CSS from settings); re-applied on every page. */
    var userCss: String = ""
        set(value) {
            field = value
            bridge.send(BridgeCommand.UserCss(value))
        }

    /** Player: the activity is in picture-in-picture; keeps Chromium from suspending the video. */
    var pipActive = false

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
                flushPending()
            }
        }
    }

    /** Returns the WebView, re-parented for [activity]. Creates it on first use. */
    fun attach(activity: Activity): WebView {
        contextWrapper.baseContext = activity
        val wv = webView ?: create().also { webView = it }
        (wv.parent as? ViewGroup)?.removeView(wv)
        flushPending()
        return wv
    }

    fun detach(activity: Activity) {
        webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        if (contextWrapper.baseContext === activity) contextWrapper.baseContext = app
    }

    /** Start page from YourTube Settings. */
    fun startUrl(): String = when (settings.current.startPage) {
        "subscriptions" -> Tab.SUBSCRIPTIONS.url
        "library" -> Tab.YOU.url
        "history" -> "https://m.youtube.com/feed/history"
        else -> UrlRouter.HOME
    }

    fun loadIfEmpty(url: String = startUrl()) {
        val wv = webView ?: return
        if (wv.url == null && pendingInitialUrl == null) load(url)
    }

    /** Loads [url]; held back until the WebView exists and the adblock engine has settled. */
    fun load(url: String) {
        val wv = webView
        if (wv == null || !adblockSettled) {
            pendingInitialUrl = url
            _state.update { it.copy(url = url, progress = 5) }
        } else {
            wv.loadUrl(url)
        }
    }

    private fun flushPending() {
        val wv = webView ?: return
        if (!adblockSettled) return
        pendingInitialUrl?.let { url ->
            pendingInitialUrl = null
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

    fun send(command: BridgeCommand) = bridge.send(command)

    /** Commands that must survive page (re)loads: kept per type and re-sent when a page is ready. */
    private val sticky = LinkedHashMap<Class<*>, BridgeCommand>()

    fun sendSticky(command: BridgeCommand) {
        sticky[command.javaClass] = command
        bridge.send(command)
    }

    /** Player: fullscreen layout wanted; re-applied whenever a new page becomes ready. */
    var fullscreenMode = false
        set(value) {
            field = value
            bridge.send(BridgeCommand.PlayerFullscreen(value))
        }

    /** Player: mini layout wanted; re-applied whenever a new page becomes ready. */
    var miniMode = false
        set(value) {
            field = value
            bridge.send(BridgeCommand.PlayerMini(value))
        }

    /** Player only: stop playback and free the page (mini player closed). */
    fun clear() {
        bridge.send(BridgeCommand.PlayerControl("pause"))
        webView?.loadUrl("about:blank")
        webView?.clearHistory()
        _video.value = VideoState()
    }

    /**
     * Role routing for a navigation to [url]. Returns true if it was handed to the other
     * WebView (the caller must then cancel it here).
     */
    internal fun routeAway(url: String): Boolean {
        val page = Page.of(url)
        val host = android.net.Uri.parse(url).host.orEmpty().lowercase()
        val youTubeContent = UrlRouter.isYouTube(host) && host != "consent.youtube.com" && host != "accounts.youtube.com"
        return when (role) {
            Role.FEED -> if (page == Page.WATCH) { onOpenVideo(url); true } else false
            Role.PLAYER -> if (youTubeContent && page != Page.WATCH) { onOpenInFeed(url); true } else false
        }
    }

    /**
     * SPA navigations (pushState) don't pass shouldOverrideUrlLoading. If the page navigated
     * to something that belongs to the other WebView anyway, undo it here and hand it over.
     */
    private fun enforceRole(url: String) {
        if (url.startsWith("about:")) return
        if (routeAway(url)) {
            val wv = webView ?: return
            if (wv.canGoBack()) wv.goBack() else if (role == Role.FEED) wv.loadUrl(UrlRouter.HOME) else clear()
        }
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

    /** Player: keep running while the app is in the background (background playback / PiP). */
    var keepRunningInBackground = false

    fun onStop() {
        if (!keepRunningInBackground && !pipActive) webView?.onPause()
        CookieManager.getInstance().flush()
    }

    /** Requests made by YouTube's service worker bypass WebViewClient; route them through the engine too. */
    private fun hookServiceWorker() {
        // The service worker client is process-wide; one hook covers both WebViews.
        if (serviceWorkerHooked || !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) return
        ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = adblock.intercept(request)
        })
        serviceWorkerHooked = true
    }

    private fun create(): WebView {
        hookServiceWorker()
        val context: Context = contextWrapper
        return AppWebView(context).apply wv@{
            keepVisible = { keepRunningInBackground || pipActive }
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
            configureSettings(settings)

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(this@wv, true)
            }

            webViewClient = AppWebViewClient(this@WebViewHolder)
            webChromeClient = AppChromeClient(this@WebViewHolder)

            dataRules.apply(this, FilterRules.from(this@WebViewHolder.settings.current))
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
        enforceRole(url)
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
        dataRules.reset()
        _state.update { WebState(url = lastUrl.orEmpty(), generation = it.generation + 1) }
        pendingUrl = lastUrl
    }

    private var pendingUrl: String? = null

    /** URL to restore after a renderer crash, consumed once. */
    fun consumePendingUrl(): String = (pendingUrl ?: UrlRouter.HOME).also { pendingUrl = null }

    private fun onBridgeMessage(msg: BridgeMessage) {
        when (msg) {
            is BridgeMessage.Session -> onSession(msg)
            is BridgeMessage.Ready -> {
                Log.i(Bridge.TAG, "ready v${msg.version} @ ${msg.url}")
                _state.update { it.copy(bridgeReady = true) }
                pingSentAt = System.currentTimeMillis()
                bridge.send(BridgeCommand.Ping(pingSentAt))
                if (userCss.isNotEmpty()) bridge.send(BridgeCommand.UserCss(userCss))
                sticky.values.forEach(bridge::send)
                if (role == Role.PLAYER) {
                    bridge.send(BridgeCommand.PlayerMini(miniMode))
                    if (fullscreenMode) bridge.send(BridgeCommand.PlayerFullscreen(true))
                }
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
                Log.w(TAG, "[$role] fallback skipped a video ad (#$n) on ${_state.value.url}")
            }
            is BridgeMessage.Navigation -> {
                adblock.pageUrl = msg.url
                Log.i(Bridge.TAG, "[$role] navigation ${msg.url} | ${msg.title}")
                _state.update { it.copy(url = msg.url, title = msg.title, canGoBack = webView?.canGoBack() ?: false) }
                refreshSignIn()
                enforceRole(msg.url)
            }
            is BridgeMessage.Fullscreen -> onFullscreen(msg.on)
            is BridgeMessage.Share -> share(msg.url, msg.title)
            is BridgeMessage.VideoIds -> onVideoIds(msg.ids)
            is BridgeMessage.SponsorSkipped -> {
                Log.i(TAG, "SponsorBlock skipped ${msg.category} (${msg.seconds.toInt()} s)")
                _sponsorPrompt.value = null
                if (settings.current.sponsorToast) {
                    android.widget.Toast.makeText(app, app.getString(com.moddingforge.yourtube.R.string.sponsor_skipped, sponsorLabel(msg.category)), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            is BridgeMessage.SponsorPrompt -> _sponsorPrompt.value = msg.takeIf { it.category.isNotEmpty() }
            is BridgeMessage.PlayerControls -> _controlsVisible.value = msg.visible
            BridgeMessage.OpenSettings -> onOpenSettings()
            is BridgeMessage.OpenVideo -> onOpenVideo(msg.url)
            is BridgeMessage.OpenInFeed -> onOpenInFeed(msg.url)
            is BridgeMessage.VideoStateMsg -> _video.value = VideoState(
                videoId = msg.videoId, title = msg.title, channel = msg.channel,
                playing = msg.playing, position = msg.position, duration = msg.duration,
            )
            is BridgeMessage.Log -> Log.println(
                when (msg.level) { "error" -> Log.ERROR; "warn" -> Log.WARN; "debug" -> Log.DEBUG; else -> Log.INFO },
                "TT.JS", msg.message,
            )
        }
    }

    companion object {
        private const val TAG = "TT.WebView"
        private const val ENGINE_WAIT_MS = 3_000L
        private var serviceWorkerHooked = false

        /** SponsorBlock categories -> localized labels (also used by the settings screen). */
        val SPONSOR_LABELS = mapOf(
            "sponsor" to com.moddingforge.yourtube.R.string.sb_sponsor,
            "selfpromo" to com.moddingforge.yourtube.R.string.sb_selfpromo,
            "interaction" to com.moddingforge.yourtube.R.string.sb_interaction,
            "intro" to com.moddingforge.yourtube.R.string.sb_intro,
            "outro" to com.moddingforge.yourtube.R.string.sb_outro,
            "preview" to com.moddingforge.yourtube.R.string.sb_preview,
            "music_offtopic" to com.moddingforge.yourtube.R.string.sb_music_offtopic,
            "filler" to com.moddingforge.yourtube.R.string.sb_filler,
        )
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
