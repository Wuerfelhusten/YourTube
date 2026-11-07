package com.moddingforge.truetube.web

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri

/** Decides where a navigation goes: stay in the WebView, rewrite it, or hand it to another app. */
object UrlRouter {
    const val HOME = "https://m.youtube.com/"

    /** Same entry point m.youtube's own "Sign in" button uses; lands back on m.youtube.com. */
    const val SIGN_IN = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm" +
        "%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"

    sealed interface Decision {
        /** Let the WebView load the URL as is. */
        data object InApp : Decision
        /** Load a different (normalized) URL in the WebView instead. */
        data class Rewrite(val url: String) : Decision
        /** Open in the browser or another app. */
        data class External(val uri: Uri) : Decision
        /** Drop the navigation. */
        data object Ignore : Decision
    }

    fun route(url: String): Decision {
        val uri = url.toUri()
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> routeWeb(uri)
            "intent" -> routeIntent(url)
            "about", "data", "blob", "javascript" -> Decision.InApp
            null -> Decision.Ignore
            else -> Decision.External(uri) // mailto:, tel:, market:, vnd.youtube:, ...
        }
    }

    private fun routeWeb(uri: Uri): Decision {
        val host = uri.host?.lowercase() ?: return Decision.Ignore
        return when {
            host == "youtu.be" -> {
                val id = uri.pathSegments.firstOrNull() ?: return Decision.Rewrite(HOME)
                Decision.Rewrite(watchUrl(id, uri.getQueryParameter("t")))
            }
            isYouTube(host) -> {
                // youtube.com/redirect?q=<target> wraps external links (descriptions, comments).
                if (uri.path == "/redirect") {
                    val target = uri.getQueryParameter("q")
                    if (target != null) Decision.External(target.toUri()) else Decision.Ignore
                } else {
                    Decision.InApp
                }
            }
            isGoogleAuthOrConsent(host, uri.path.orEmpty()) -> Decision.InApp
            else -> Decision.External(uri)
        }
    }

    /** YouTube uses intent:// links for "open in app" prompts. Keep YouTube targets in TrueTube. */
    private fun routeIntent(url: String): Decision {
        val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()
            ?: return Decision.Ignore
        val data = intent.data
        val dataHost = data?.host?.lowercase()
        if (data != null && dataHost != null && (isYouTube(dataHost) || dataHost == "youtu.be")) {
            return routeWeb(data.buildUpon().scheme("https").build())
        }
        val fallback = intent.getStringExtra("browser_fallback_url")
        return if (fallback != null) route(fallback) else Decision.Ignore
    }

    fun isYouTube(host: String) = host == "youtube.com" || host.endsWith(".youtube.com")

    /** Google sign-in and the EU cookie consent pages must stay inside the WebView (shared cookies). */
    private fun isGoogleAuthOrConsent(host: String, path: String): Boolean {
        if (host.startsWith("accounts.google.") || host.startsWith("consent.google.")) return true
        if (host == "accounts.youtube.com" || host == "consent.youtube.com") return true
        // Account recovery / "verify it's you" interstitials during sign-in.
        if (host == "gds.google.com" || (host == "myaccount.google.com" && path.startsWith("/signinoptions"))) return true
        // Some sign-in steps run on www.google.<tld>/accounts/... or /signin/...
        if (host.startsWith("www.google.") && (path.startsWith("/accounts") || path.startsWith("/signin"))) return true
        return false
    }

    /** Tracking parameters YouTube adds to shared links. */
    private val trackingParams = setOf("si", "feature", "pp", "app", "ab_channel", "embeds_referring_euri", "source_ve_path")

    /**
     * Turns a link coming from outside (other apps, share sheet) into the m.youtube URL TrueTube
     * loads, or null if it isn't a YouTube link. Handles youtu.be, www/music/youtube.com,
     * /live/ID, /embed/ID, /v/ID, vnd.youtube:ID and strips tracking parameters.
     */
    fun normalizeIncoming(raw: String): String? {
        val uri = raw.trim().toUri()
        when (uri.scheme?.lowercase()) {
            "vnd.youtube" -> {
                val id = (uri.schemeSpecificPart ?: "").trimStart('/').substringBefore('?').ifEmpty { return null }
                return watchUrl(id, uri.getQueryParameter("t"))
            }
            "http", "https" -> Unit
            else -> return null
        }
        val host = uri.host?.lowercase() ?: return null
        if (host == "youtu.be") {
            val id = uri.pathSegments.firstOrNull() ?: return HOME
            return watchUrl(id, uri.getQueryParameter("t"))
        }
        if (!isYouTube(host) && host != "youtube-nocookie.com" && !host.endsWith(".youtube-nocookie.com")) return null

        val segments = uri.pathSegments
        // /live/ID, /embed/ID, /v/ID -> regular watch page
        if (segments.size >= 2 && segments[0] in setOf("live", "embed", "v")) {
            return watchUrl(segments[1], uri.getQueryParameter("t") ?: uri.getQueryParameter("start"))
        }
        val clean = android.net.Uri.Builder().scheme("https").authority("m.youtube.com").path(uri.path ?: "/")
        for (name in uri.queryParameterNames) {
            if (name in trackingParams) continue
            for (value in uri.getQueryParameters(name)) clean.appendQueryParameter(name, value)
        }
        return clean.build().toString()
    }

    /** First YouTube link inside a shared text ("Check this out: https://youtu.be/..."). */
    fun findYouTubeLink(text: String): String? =
        Regex("""(https?://|vnd\.youtube:)\S+""").findAll(text).map { it.value.trimEnd('.', ',', ')', '!', '"') }
            .firstNotNullOfOrNull { normalizeIncoming(it) }

    /** Clean, shareable link for a page (youtu.be for videos, like the official app). */
    fun shareUrl(url: String): String {
        // YouTube may already hand us youtu.be/...?si=... - normalize first, then build the short link.
        val normalized = normalizeIncoming(url) ?: url
        val uri = normalized.toUri()
        val id = uri.getQueryParameter("v")
        if (Page.of(normalized) == Page.WATCH && id != null) {
            val t = uri.getQueryParameter("t")
            return "https://youtu.be/$id" + if (t != null) "?t=$t" else ""
        }
        return normalized.replace("://m.youtube.com", "://www.youtube.com")
    }

    fun watchUrl(videoId: String, start: String? = null): String =
        buildString {
            append("https://m.youtube.com/watch?v=").append(Uri.encode(videoId))
            if (!start.isNullOrBlank()) append("&t=").append(Uri.encode(start))
        }
}
