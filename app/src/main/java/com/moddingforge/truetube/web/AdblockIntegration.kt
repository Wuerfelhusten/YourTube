package com.moddingforge.truetube.web

import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.moddingforge.truetube.BuildConfig
import com.moddingforge.truetube.adblock.AdblockEngine
import com.moddingforge.truetube.adblock.FilterRepository
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Connects Brave's adblock engine to the WebView the way Brave does it:
 *  1. network requests -> Engine.check (shouldInterceptRequest)
 *  2. scriptlets (injected_script) -> DocumentStart script per host, before YouTube's scripts
 *  3. cosmetic hide selectors + procedural filters -> handed to assets/inject/10-cosmetic.js
 *  4. generic class/id hiding -> on demand via the bridge (cosmetic-classids)
 */
class AdblockIntegration(
    private val repo: FilterRepository,
    private val json: Json,
    /** "Ad blocker" switch in TrueTube Settings. */
    private val enabled: () -> Boolean = { true },
) {
    /** Main-frame URL, used as source URL for third-party checks. Written on the UI thread. */
    @Volatile var pageUrl: String = UrlRouter.HOME

    val blockedRequests = AtomicInteger()
    val skippedAds = AtomicInteger()

    private val handlers = mutableListOf<ScriptHandler>()
    private val exceptionsByHost = ConcurrentHashMap<String, Set<String>>()

    private val engine: AdblockEngine? get() = if (enabled()) repo.engine else null

    /** Removes the per-host scripts (ad blocker switched off). Takes effect on the next page load. */
    fun uninstall() {
        handlers.forEach { it.remove() }
        reset()
    }

    /** Registers the per-host scriptlet/cosmetic scripts. Call on the UI thread. */
    fun install(webView: WebView, engine: AdblockEngine) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        if (!enabled()) {
            uninstall()
            return
        }
        if (installedFor?.get() === webView && installedEngine === engine) return
        installedFor = java.lang.ref.WeakReference(webView)
        installedEngine = engine
        handlers.forEach { it.remove() }
        handlers.clear()
        for (host in HOSTS) {
            val res = engine.cosmetic("https://$host/")
            exceptionsByHost[host] = res.exceptions
            val script = buildHostScript(host, res.injectedScript, res.hideSelectors, res.proceduralActions, res.generichide)
            handlers += WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("https://$host"))
            Log.i(TAG, "host $host: scriptlets ${res.injectedScript.length} chars, ${res.hideSelectors.size} hide, " +
                "${res.proceduralActions.size} procedural, generichide=${res.generichide}")
        }
    }

    /** Forget registered scripts (the WebView they belonged to is gone). */
    fun reset() {
        handlers.clear()
        installedFor = null
        installedEngine = null
    }

    private var installedFor: java.lang.ref.WeakReference<WebView>? = null
    private var installedEngine: AdblockEngine? = null

    private fun buildHostScript(
        host: String,
        scriptlets: String,
        hide: Set<String>,
        procedural: Set<String>,
        generichide: Boolean,
    ): String {
        val data = buildJsonObject {
            put("host", host)
            put("hide", JsonArray(hide.map { JsonPrimitive(it) }))
            // procedural entries are JSON strings already; embed them as objects
            put("procedural", JsonArray(procedural.mapNotNull { runCatching { json.parseToJsonElement(it) }.getOrNull() }))
            put("generichide", generichide)
        }
        return buildString {
            append("(function(){\n")
            append("try {\n").append(scriptlets).append("\n} catch (e) { console.error('[TrueTube] scriptlets failed', e); }\n")
            append("(window.__TT_COSMETIC__ = window.__TT_COSMETIC__ || []).push(").append(data.toString()).append(");\n")
            append("if (window.TrueTube && window.TrueTube.cosmeticFlush) window.TrueTube.cosmeticFlush();\n")
            append("})();")
        }
    }

    /** Generic hide selectors for classes/ids the page reported. */
    fun classIdSelectors(host: String, classes: List<String>, ids: List<String>): List<String> {
        val e = engine ?: return emptyList()
        return e.hiddenClassIdSelectors(classes, ids, exceptionsByHost[host] ?: emptySet())
    }

    // --- network ------------------------------------------------------------------------------

    /** @return a replacement response to block/redirect, or null to load normally. Any thread. */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.isForMainFrame) return null
        val url = request.url
        val scheme = url.scheme
        if (scheme != "http" && scheme != "https") return null
        val e = engine ?: return null

        val type = guessRequestType(url, request.requestHeaders)
        val result = try {
            e.check(url.toString(), pageUrl, type)
        } catch (t: Throwable) {
            Log.w(TAG, "check failed for $url", t)
            null
        } ?: return null

        if (!result.block) return null
        blockedRequests.incrementAndGet()
        if (BuildConfig.DEBUG) Log.v(TAG, "blocked [$type] ${url.toString().take(160)}")
        return result.redirect?.let(::dataUrlResponse) ?: emptyResponse()
    }

    private fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", 200, "OK", CORS, ByteArrayInputStream(ByteArray(0)))

    /** adblock-rust returns redirect bodies as data: URLs. */
    private fun dataUrlResponse(dataUrl: String): WebResourceResponse? {
        if (!dataUrl.startsWith("data:")) return null
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return null
        val meta = dataUrl.substring(5, comma)
        val isBase64 = meta.endsWith(";base64")
        val mime = meta.removeSuffix(";base64").substringBefore(';').ifEmpty { "text/plain" }
        val payload = dataUrl.substring(comma + 1)
        val bytes = if (isBase64) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray()
        return WebResourceResponse(mime, "utf-8", 200, "OK", CORS, ByteArrayInputStream(bytes))
    }

    /** WebView doesn't tell us the resource type; infer it like a browser would label it. */
    private fun guessRequestType(url: Uri, headers: Map<String, String>): String {
        val path = url.path.orEmpty().lowercase()
        val ext = path.substringAfterLast('.', "")
        val accept = (headers["Accept"] ?: headers["accept"]).orEmpty()
        return when {
            ext == "js" || ext == "mjs" -> "script"
            ext == "css" || accept.startsWith("text/css") -> "stylesheet"
            ext in IMAGE_EXT || accept.startsWith("image/") -> "image"
            ext in FONT_EXT -> "font"
            ext in MEDIA_EXT || path.contains("/videoplayback") -> "media"
            accept.startsWith("text/html") -> "sub_frame"
            path.contains("/generate_204") || path.contains("/ptracking") || path.contains("/api/stats/") -> "ping"
            else -> "xhr"
        }
    }

    companion object {
        private const val TAG = "TT.Adblock"

        /** Hosts that get scriptlets + cosmetics (all frames on these origins). */
        val HOSTS = listOf("m.youtube.com", "www.youtube.com", "consent.youtube.com")

        private val CORS = mapOf("Access-Control-Allow-Origin" to "*")
        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif")
        private val FONT_EXT = setOf("woff", "woff2", "ttf", "otf")
        private val MEDIA_EXT = setOf("mp4", "webm", "m4a", "mp3", "m3u8", "ts")
    }
}
