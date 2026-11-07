package com.moddingforge.truetube.web

import android.content.res.AssetManager
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Values handed to every injected script as `TT_CONFIG`. */
@Serializable
data class InjectConfig(val version: Int, val debug: Boolean)

/**
 * Bundles all .js files in assets/inject (sorted by file name) into one script and registers it to run
 * at document start, before any YouTube script.
 */
class ScriptInjector(
    private val assets: AssetManager,
    private val json: Json,
    private val config: InjectConfig,
) {
    /** true if the script runs via DocumentStartScript, false if we must fall back to onPageStarted. */
    var documentStart = false
        private set

    val bundle: String by lazy { buildBundle() }

    fun install(webView: WebView) {
        documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStart) {
            WebViewCompat.addDocumentStartJavaScript(webView, bundle, ORIGINS)
        } else {
            Log.w(TAG, "DOCUMENT_START_SCRIPT not supported - falling back to onPageStarted injection")
        }
    }

    /** Fallback for old WebViews. Runs too late for ad pruning, but keeps the bridge working. */
    fun injectFallback(webView: WebView) {
        if (!documentStart) webView.evaluateJavascript(bundle, null)
    }

    private fun buildBundle(): String {
        val files = assets.list(DIR).orEmpty().filter { it.endsWith(".js") }.sorted()
        val cfg = json.encodeToString(InjectConfig.serializer(), config)
        val sb = StringBuilder()
        sb.append("(function(){'use strict';\n")
        sb.append("if (window.__TT_LOADED__) return; window.__TT_LOADED__ = true;\n")
        sb.append("const TT_CONFIG = Object.freeze(").append(cfg).append(");\n")
        for (file in files) {
            val source = assets.open("$DIR/$file").bufferedReader().use { it.readText() }
            // Each file is isolated so one broken script cannot take down the others.
            sb.append("try {\n").append(source).append("\n} catch (e) { console.error('[TrueTube] ")
            sb.append(file).append(" failed', e); }\n")
        }
        sb.append("})();")
        Log.d(TAG, "Bundled ${files.size} scripts (${sb.length} chars): $files")
        return sb.toString()
    }

    companion object {
        private const val TAG = "TT.Inject"
        private const val DIR = "inject"
        val ORIGINS = setOf("https://youtube.com", "https://*.youtube.com")
    }
}
