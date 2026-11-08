package com.moddingforge.yourtube.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures follow the structure of real youtubei WEB responses (trimmed). */
class YouTubeDataTest {
    @Test fun subscriptionFeedLockups() {
        val body = """
        {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"richGridRenderer":{"contents":[
          {"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"tycyv2eoOaU","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
            "metadata":{"lockupMetadataViewModel":{"title":{"content":"MrBeast Lab: Ep. 1"},
              "metadata":{"contentMetadataViewModel":{"metadataRows":[
                {"metadataParts":[{"text":{"content":"Beast Animations und MrBeast"}}]},
                {"metadataParts":[{"text":{"content":"1 Mio. Aufrufe"}},{"text":{"content":"vor 2 Stunden"}}]}]}}}}}}}},
          {"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"PLlist","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
            "metadata":{"lockupMetadataViewModel":{"title":{"content":"A playlist"}}}}}}},
          {"richItemRenderer":{"content":{"videoRenderer":{"videoId":"old12345678","title":{"runs":[{"text":"Old "},{"text":"style"}]},
            "ownerText":{"runs":[{"text":"Some Channel"}]}}}}},
          {"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"tycyv2eoOaU","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
            "metadata":{"lockupMetadataViewModel":{"title":{"content":"duplicate"}}}}}}}
        ]}}}}]}}}
        """
        val videos = YouTubeData.parseSubscriptionFeed(body)
        assertEquals(listOf("tycyv2eoOaU", "old12345678"), videos.map { it.id })
        assertEquals(FeedVideo("tycyv2eoOaU", "MrBeast Lab: Ep. 1", "Beast Animations und MrBeast"), videos[0])
        assertEquals("Old style", videos[1].title)
        assertEquals("Some Channel", videos[1].channel)
        assertEquals("https://m.youtube.com/watch?v=tycyv2eoOaU", videos[0].url)
    }

    @Test fun inboxWithEntries() {
        val body = """
        {"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"sections":[{"multiPageMenuNotificationSectionRenderer":{"items":[
          {"notificationRenderer":{"notificationId":"n1","read":false,
            "thumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/a=s48"},{"url":"https://yt3.ggpht.com/a=s88"}]},
            "videoThumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/abc/default.jpg"}]},
            "shortMessage":{"simpleText":"Channel hat hochgeladen: Neues Video"},
            "sentTimeText":{"simpleText":"vor 3 Stunden"},
            "navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"url":"/watch?v=abc"}},"watchEndpoint":{"videoId":"abc"}}}},
          {"notificationRenderer":{"notificationId":"n2","read":true,
            "shortMessage":{"runs":[{"text":"Jemand hat geantwortet: "},{"text":"Danke!"}]},
            "sentTimeText":{"simpleText":"vor 1 Tag"},
            "navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"url":"/watch?v=xyz&lc=Ugz"}}}}}
        ]}}]}}}}]}
        """
        val inbox = YouTubeData.parseInbox(body)
        assertNull(inbox.emptyText)
        assertEquals(2, inbox.items.size)
        val first = inbox.items[0]
        assertEquals("Channel hat hochgeladen: Neues Video", first.text)
        assertEquals("https://m.youtube.com/watch?v=abc", first.url)
        assertEquals("abc", first.videoId)
        assertEquals("https://yt3.ggpht.com/a=s88", first.avatar)
        assertFalse(first.read)
        val reply = inbox.items[1]
        assertEquals("Jemand hat geantwortet: Danke!", reply.text)
        assertEquals("https://m.youtube.com/watch?v=xyz&lc=Ugz", reply.url)
        assertTrue(reply.read)
    }

    /** browse FEnotifications_inbox (desktop since 2025): sections, comment replies, Shorts. */
    @Test fun inboxWithSectionsRepliesAndShorts() {
        val body = """
        {"onResponseReceivedEndpoints":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"sections":[
          {"multiPageMenuNotificationSectionRenderer":{"notificationSectionTitle":{"simpleText":"Wichtig"},"items":[
            {"notificationRenderer":{"notificationId":"a1","read":false,"shortMessage":{"simpleText":"Anni hat ein Video hochgeladen"},
              "navigationEndpoint":{"watchEndpoint":{"videoId":"vid1"}}}}]}},
          {"multiPageMenuNotificationSectionRenderer":{"notificationSectionTitle":{"simpleText":"Weitere Benachrichtigungen"},"items":[
            {"notificationRenderer":{"notificationId":"r1","read":true,
              "shortMessage":{"runs":[{"text":"@nicks2601"},{"text":" hat geantwortet: „"},{"text":"What's up?"}]},
              "navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"sendPost":true,"apiUrl":"/youtubei/v1/notification/get_notification_menu"}},
                "getCommentsFromInboxCommand":{"videoId":"YS80F2zjV9c","linkedCommentId":"UgzW.AbyD"}}}},
            {"notificationRenderer":{"notificationId":"s1","read":false,"shortMessage":{"simpleText":"Short hochgeladen"},
              "navigationEndpoint":{"reelWatchEndpoint":{"videoId":"short1"}}}}]}}
        ]}}}}]}
        """
        val items = YouTubeData.parseInbox(body).items
        assertEquals(listOf("a1", "r1", "s1"), items.map { it.id })
        assertEquals(listOf("Wichtig", "Weitere Benachrichtigungen", "Weitere Benachrichtigungen"), items.map { it.section })
        assertEquals("@nicks2601 hat geantwortet: „What's up?", items[1].text)
        assertEquals("https://m.youtube.com/watch?v=YS80F2zjV9c&lc=UgzW.AbyD", items[1].url)
        assertEquals("YS80F2zjV9c", items[1].videoId)
        assertEquals("https://m.youtube.com/shorts/short1", items[2].url)
    }

    @Test fun emptyInboxShowsYouTubesHint() {
        val body = """{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"sections":[{"backgroundPromoRenderer":{
            "title":{"simpleText":"Hier findest du deine Benachrichtigungen"},"bodyText":{"simpleText":"Abonniere Kanäle."}}}]}}}}]}"""
        val inbox = YouTubeData.parseInbox(body)
        assertTrue(inbox.items.isEmpty())
        assertEquals("Hier findest du deine Benachrichtigungen\nAbonniere Kanäle.", inbox.emptyText)
    }

    @Test fun unseenCount() {
        assertEquals(7, YouTubeData.parseUnseenCount("""{"responseContext":{},"unseenCount":7}"""))
        assertEquals(0, YouTubeData.parseUnseenCount("""{"responseContext":{}}"""))
    }
}
