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

    fun watchUrl(videoId: String, start: String? = null): String =
        buildString {
            append("https://m.youtube.com/watch?v=").append(Uri.encode(videoId))
            if (!start.isNullOrBlank()) append("&t=").append(Uri.encode(start))
        }
}
