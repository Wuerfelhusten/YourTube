package com.moddingforge.truetube.web

import androidx.core.net.toUri

/** What kind of YouTube page is shown; drives the native chrome (top bar, tabs, status bar). */
enum class Page {
    HOME, SHORTS, SUBSCRIPTIONS, YOU, WATCH, RESULTS, OTHER;

    /** The official app shows no top bar on the watch page and in Shorts. */
    val showsTopBar get() = this != WATCH && this != SHORTS

    /** The official app hides the tab bar while a video is open. */
    val showsTabBar get() = this != WATCH

    /** Pull-to-refresh only on feed-like pages (not over the player / Shorts swiper). */
    val allowsPullToRefresh get() = this != WATCH && this != SHORTS

    companion object {
        fun of(url: String): Page {
            val uri = url.toUri()
            val host = uri.host.orEmpty()
            if (!UrlRouter.isYouTube(host)) return OTHER
            val path = uri.path.orEmpty()
            return when {
                path.startsWith("/watch") -> WATCH
                path.startsWith("/shorts") -> SHORTS
                path.startsWith("/results") -> RESULTS
                path.isEmpty() || path == "/" -> HOME
                path.startsWith("/feed/subscriptions") -> SUBSCRIPTIONS
                path.startsWith("/feed/library") || path.startsWith("/feed/you") -> YOU
                else -> OTHER
            }
        }

        /** Search query of a /results URL, for showing it in the native search bar. */
        fun searchQuery(url: String): String? =
            url.toUri().takeIf { Page.of(url) == RESULTS }?.getQueryParameter("search_query")
    }
}

/** Bottom tabs, in the same order as m.youtube's pivot bar (used for in-page navigation). */
enum class Tab(val pivotIndex: Int, val url: String, val page: Page) {
    HOME(0, "https://m.youtube.com/", Page.HOME),
    SHORTS(1, "https://m.youtube.com/shorts", Page.SHORTS),
    SUBSCRIPTIONS(2, "https://m.youtube.com/feed/subscriptions", Page.SUBSCRIPTIONS),
    YOU(3, "https://m.youtube.com/feed/library", Page.YOU);

    companion object {
        fun of(page: Page): Tab? = entries.firstOrNull { it.page == page }
    }
}
