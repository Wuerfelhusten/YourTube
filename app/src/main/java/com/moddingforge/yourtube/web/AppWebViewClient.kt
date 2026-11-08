package com.moddingforge.yourtube.web

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

class AppWebViewClient(private val holder: WebViewHolder) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val url = request.url.toString()
        return when (val decision = UrlRouter.route(url)) {
            // Watch pages belong to the player WebView, everything else to the feed.
            UrlRouter.Decision.InApp -> holder.routeAway(url)
            is UrlRouter.Decision.Rewrite -> {
                view.loadUrl(decision.url)
                true
            }
            is UrlRouter.Decision.External -> {
                openExternal(view, decision)
                true
            }
            UrlRouter.Decision.Ignore -> true
        }
    }

    private fun openExternal(view: WebView, decision: UrlRouter.Decision.External) {
        val intent = Intent(Intent.ACTION_VIEW, decision.uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            view.context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app for ${decision.uri}", e)
        }
    }

    /** Brave engine network check. Called on WebView background threads. */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        holder.adblock.intercept(request)

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        holder.onPageStarted(view, url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        holder.onPageFinished(url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        holder.onHistoryChanged(view, url)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        holder.onRenderProcessGone(view)
        return true // handled - don't crash the app
    }

    companion object {
        private const val TAG = "TT.Client"
    }
}
