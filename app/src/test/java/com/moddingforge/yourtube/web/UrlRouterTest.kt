package com.moddingforge.yourtube.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UrlRouterTest {
    // --- links from other apps -------------------------------------------------------------

    @Test fun shortLinkBecomesWatchPage() {
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", UrlRouter.normalizeIncoming("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=42", UrlRouter.normalizeIncoming("https://youtu.be/dQw4w9WgXcQ?t=42&si=abc"))
    }

    @Test fun desktopLinkMovesToMobileAndDropsTracking() {
        assertEquals(
            "https://m.youtube.com/watch?v=abc123&t=10",
            UrlRouter.normalizeIncoming("https://www.youtube.com/watch?v=abc123&feature=share&si=xyz&t=10"),
        )
    }

    @Test fun liveEmbedAndAppScheme() {
        assertEquals("https://m.youtube.com/watch?v=live1", UrlRouter.normalizeIncoming("https://www.youtube.com/live/live1?si=x"))
        assertEquals("https://m.youtube.com/watch?v=emb1&t=5", UrlRouter.normalizeIncoming("https://www.youtube-nocookie.com/embed/emb1?start=5"))
        assertEquals("https://m.youtube.com/watch?v=app1", UrlRouter.normalizeIncoming("vnd.youtube:app1"))
        assertEquals("https://m.youtube.com/watch?v=app1&t=10", UrlRouter.normalizeIncoming("vnd.youtube:app1?t=10"))
        assertEquals("https://m.youtube.com/@handle/videos", UrlRouter.normalizeIncoming("https://www.youtube.com/@handle/videos?si=x"))
    }

    @Test fun foreignLinksAreNotYouTube() {
        assertNull(UrlRouter.normalizeIncoming("https://example.com/watch?v=abc"))
        assertNull(UrlRouter.normalizeIncoming("https://notyoutube.com/watch?v=abc"))
        assertNull(UrlRouter.normalizeIncoming("mailto:someone@example.com"))
    }

    @Test fun findsLinkInSharedText() {
        assertEquals(
            "https://m.youtube.com/watch?v=abc123",
            UrlRouter.findYouTubeLink("Look at this! https://youtu.be/abc123?si=foo."),
        )
        assertNull(UrlRouter.findYouTubeLink("no link here, only https://example.com"))
    }

    @Test fun shareLinkIsShortAndClean() {
        assertEquals("https://youtu.be/abc123", UrlRouter.shareUrl("https://m.youtube.com/watch?v=abc123&pp=xyz"))
        assertEquals("https://youtu.be/abc123?t=30", UrlRouter.shareUrl("https://m.youtube.com/watch?v=abc123&t=30"))
        assertEquals("https://www.youtube.com/@channel", UrlRouter.shareUrl("https://m.youtube.com/@channel"))
    }

    // --- navigation inside the WebView ----------------------------------------------------------

    @Test fun youtubeStaysInApp() {
        assertEquals(UrlRouter.Decision.InApp, UrlRouter.route("https://m.youtube.com/feed/subscriptions"))
        assertEquals(UrlRouter.Decision.InApp, UrlRouter.route("https://accounts.google.com/ServiceLogin"))
        assertEquals(UrlRouter.Decision.InApp, UrlRouter.route("https://consent.youtube.com/m?continue=x"))
    }

    @Test fun onlyRealGoogleSignInHostsStayInApp() {
        for (url in listOf(
            "https://accounts.google.com/v3/signin", "https://accounts.google.de/ServiceLogin",
            "https://accounts.google.co.uk/x", "https://consent.google.com.br/x", "https://www.google.com/accounts/x",
        )) assertEquals(url, UrlRouter.Decision.InApp, UrlRouter.route(url))
        // Look-alikes must leave the app (opened in the browser, without the YouTube session).
        for (url in listOf(
            "https://accounts.google.example.com/signin", "https://accounts.google.com.evil.net/signin",
            "https://consent.google.attacker.io/x", "https://www.google.evil.com/accounts/x", "https://accounts.google.de.example/x",
        )) assertTrue(url, UrlRouter.route(url) is UrlRouter.Decision.External)
    }

    @Test fun shortLinkIsRewritten() {
        assertEquals(UrlRouter.Decision.Rewrite("https://m.youtube.com/watch?v=abc"), UrlRouter.route("https://youtu.be/abc"))
    }

    @Test fun externalLinksLeaveTheApp() {
        val d = UrlRouter.route("https://example.com/page")
        assertTrue(d is UrlRouter.Decision.External)
        val redirect = UrlRouter.route("https://www.youtube.com/redirect?q=https%3A%2F%2Fexample.org%2F")
        assertEquals("https://example.org/", (redirect as UrlRouter.Decision.External).uri.toString())
    }

    @Test fun intentLinkToYouTubeStaysInApp() {
        val d = UrlRouter.route("intent://www.youtube.com/watch?v=abc#Intent;scheme=https;package=com.google.android.youtube;end")
        assertEquals(UrlRouter.Decision.InApp, d)
    }
}
