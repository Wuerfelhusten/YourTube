package com.moddingforge.yourtube.notify

import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Signed-in youtubei requests outside the WebView (background checks, the bell inbox).
 *
 * Uses the WebView's own Google session cookies and authenticates the way youtube.com does
 * (SAPISIDHASH). m.youtube's MWEB client gets no notifications at all, so these requests use the
 * desktop WEB client, which YouTube answers with the bell inbox and the subscriptions feed.
 */
class YouTubeApi(
    /** Active brand channel of the session (see Notifier.setPageId); null/empty = personal channel. */
    private val pageId: () -> String? = { null },
) {
    private fun cookies(): String? = runCatching { CookieManager.getInstance().getCookie(ORIGIN) }.getOrNull()

    private fun sapisid(cookies: String?): String? = cookies?.split(';')?.map { it.trim() }?.let { list ->
        (list.firstOrNull { it.startsWith("SAPISID=") } ?: list.firstOrNull { it.startsWith("__Secure-3PAPISID=") })
            ?.substringAfter('=')?.takeIf { it.isNotEmpty() }
    }

    val signedIn: Boolean get() = sapisid(cookies()) != null

    /** POST /youtubei/v1/[endpoint]; [fields] are extra top-level JSON members ("\"browseId\":\"…\""). */
    fun post(endpoint: String, fields: String = ""): String {
        val cookies = cookies() ?: throw NotSignedIn()
        val sapisid = sapisid(cookies) ?: throw NotSignedIn()
        val ts = System.currentTimeMillis() / 1000
        val hash = MessageDigest.getInstance("SHA-1").digest("$ts $sapisid $ORIGIN".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val locale = Locale.getDefault()
        val page = pageId()?.takeIf { it.isNotBlank() }
        val user = if (page != null) ""","user":{"onBehalfOfUser":"$page"}""" else ""
        val body = """{"context":{"client":{"clientName":"WEB","clientVersion":"$CLIENT_VERSION","hl":"${locale.language}","gl":"${locale.country.ifEmpty { "US" }}","platform":"DESKTOP"}$user}""" +
            (if (fields.isNotEmpty()) ",$fields" else "") + "}"
        val conn = (URL("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Cookie", cookies)
            setRequestProperty("Authorization", "SAPISIDHASH ${ts}_$hash")
            setRequestProperty("Origin", ORIGIN)
            setRequestProperty("X-Origin", ORIGIN)
            setRequestProperty("X-Goog-AuthUser", "0")
            if (page != null) setRequestProperty("X-Goog-PageId", page)
            setRequestProperty("X-Youtube-Client-Name", "1")
            setRequestProperty("X-Youtube-Client-Version", CLIENT_VERSION)
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code == 401 || code == 403) throw NotSignedIn()
            check(code == 200) { "youtubei $endpoint: HTTP $code" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun subscriptionFeed(): List<FeedVideo> = YouTubeData.parseSubscriptionFeed(post("browse", "\"browseId\":\"FEsubscriptions\""))

    /** The bell inbox: the desktop site's current request first (sections, replies, likes), the old menu as fallback. */
    fun inbox(): Inbox {
        val current = runCatching { YouTubeData.parseInbox(post("browse", "\"browseId\":\"FEnotifications_inbox\"")) }.getOrNull()
        if (current != null && current.items.isNotEmpty()) return current
        return legacyInbox()
    }

    private fun legacyInbox(): Inbox = YouTubeData.parseInbox(
        post("notification/get_notification_menu", "\"notificationsMenuRequestType\":\"NOTIFICATIONS_MENU_REQUEST_TYPE_INBOX\""),
    )

    fun unseenCount(): Int = YouTubeData.parseUnseenCount(post("notification/get_unseen_count"))

    class NotSignedIn : Exception("not signed in")

    companion object {
        const val ORIGIN = "https://www.youtube.com"
        /** youtube.com accepts older WEB client versions for a long time; update now and then. */
        const val CLIENT_VERSION = "2.20261105.01.00"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    }
}
