package com.moddingforge.yourtube.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PageTest {
    @Test fun recognisesPages() {
        assertEquals(Page.HOME, Page.of("https://m.youtube.com/"))
        assertEquals(Page.WATCH, Page.of("https://m.youtube.com/watch?v=abc"))
        assertEquals(Page.SHORTS, Page.of("https://m.youtube.com/shorts/abc"))
        assertEquals(Page.RESULTS, Page.of("https://m.youtube.com/results?search_query=cats"))
        assertEquals(Page.SUBSCRIPTIONS, Page.of("https://m.youtube.com/feed/subscriptions"))
        assertEquals(Page.YOU, Page.of("https://m.youtube.com/feed/library"))
        assertEquals(Page.OTHER, Page.of("https://m.youtube.com/@channel"))
        assertEquals(Page.OTHER, Page.of("https://example.com/watch?v=abc"))
    }

    @Test fun searchQueryOnlyOnResults() {
        assertEquals("cute cats", Page.searchQuery("https://m.youtube.com/results?search_query=cute+cats"))
        assertNull(Page.searchQuery("https://m.youtube.com/watch?v=abc"))
    }

    @Test fun chromeRules() {
        assertFalse(Page.WATCH.showsTopBar)
        assertFalse(Page.WATCH.showsTabBar)
        assertFalse(Page.SHORTS.allowsPullToRefresh)
        assertEquals(Tab.SUBSCRIPTIONS, Tab.of(Page.SUBSCRIPTIONS))
        assertNull(Tab.of(Page.WATCH))
    }
}
