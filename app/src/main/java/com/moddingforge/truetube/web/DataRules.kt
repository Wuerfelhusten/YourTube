package com.moddingforge.truetube.web

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.moddingforge.truetube.data.AppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Rules for 05-data-filter.js, exposed to the page as window.__TT_RULES__. */
@Serializable
data class FilterRules(
    val hideShorts: Boolean,
    val hidePosts: Boolean,
    val hideMixes: Boolean,
    val hideWatched: Boolean,
    val minDuration: Int,
    val keywords: List<String>,
    val dearrow: Boolean,
) {
    companion object {
        fun from(s: AppSettings) = FilterRules(
            hideShorts = s.filterShorts,
            hidePosts = s.filterPosts,
            hideMixes = s.filterMixes,
            hideWatched = s.filterWatched,
            minDuration = s.filterMinDuration,
            keywords = s.filterKeywords.lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() },
            dearrow = s.deArrow,
        )
    }
}

/**
 * Keeps one DocumentStart script per WebView that defines window.__TT_RULES__. It is re-registered
 * when the rules change, so they are in place before YouTube parses ytInitialData on the next page
 * load - a bridge message would arrive too late for that.
 */
class DataRulesScript(private val json: Json) {
    private var handler: ScriptHandler? = null
    private var current: FilterRules? = null

    fun apply(webView: WebView, rules: FilterRules) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        if (rules == current && handler != null) return
        handler?.remove()
        val js = "window.__TT_RULES__ = Object.freeze(" + json.encodeToString(FilterRules.serializer(), rules) + ");"
        handler = WebViewCompat.addDocumentStartJavaScript(webView, js, ScriptInjector.ORIGINS)
        current = rules
    }

    fun reset() {
        handler = null
        current = null
    }
}
