package com.moddingforge.yourtube.notify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** A video in the subscriptions feed. */
data class FeedVideo(val id: String, val title: String, val channel: String) {
    val url get() = "https://m.youtube.com/watch?v=$id"
    val thumbnail get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
}

/** An entry of YouTube's notification inbox (the bell). */
data class InboxItem(
    val id: String,
    val text: String,
    val time: String,
    val avatar: String?,
    val thumbnail: String?,
    /** m.youtube URL the entry opens. */
    val url: String?,
    /** Video the entry is about (for de-duplicating with feed notifications). */
    val videoId: String?,
    val read: Boolean,
)

/** The bell inbox: entries, or YouTube's own hint when there are none. */
data class Inbox(val items: List<InboxItem>, val emptyText: String?)

/**
 * Parsers for the youtubei responses YourTube's notifications use (WEB client):
 * browse FEsubscriptions, notification/get_notification_menu, notification/get_unseen_count.
 * They search the JSON for the known renderers instead of following exact paths, so wrapper
 * changes on YouTube's side don't break them.
 */
object YouTubeData {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseSubscriptionFeed(body: String): List<FeedVideo> {
        val out = LinkedHashMap<String, FeedVideo>()
        walk(json.parseToJsonElement(body)) { key, value ->
            when (key) {
                // Current layout: lockupViewModel { contentId, contentType, metadata.lockupMetadataViewModel }
                "lockupViewModel" -> {
                    val o = value as? JsonObject ?: return@walk
                    if (o.str("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return@walk
                    val id = o.str("contentId") ?: return@walk
                    val md = o.obj("metadata")?.obj("lockupMetadataViewModel") ?: return@walk
                    val title = md.obj("title")?.str("content") ?: return@walk
                    val channel = md.obj("metadata")?.obj("contentMetadataViewModel")?.arr("metadataRows")
                        ?.firstOrNull()?.jsonObject?.arr("metadataParts")?.firstOrNull()?.jsonObject
                        ?.obj("text")?.str("content").orEmpty()
                    out.putIfAbsent(id, FeedVideo(id, title, channel))
                }
                // Older layout: videoRenderer { videoId, title, ownerText / shortBylineText }
                "videoRenderer", "gridVideoRenderer" -> {
                    val o = value as? JsonObject ?: return@walk
                    val id = o.str("videoId") ?: return@walk
                    val title = text(o["title"]) ?: return@walk
                    val channel = text(o["ownerText"]) ?: text(o["shortBylineText"]) ?: ""
                    out.putIfAbsent(id, FeedVideo(id, title, channel))
                }
            }
        }
        return out.values.toList()
    }

    fun parseInbox(body: String): Inbox {
        val items = mutableListOf<InboxItem>()
        var empty: String? = null
        walk(json.parseToJsonElement(body)) { key, value ->
            val o = value as? JsonObject ?: return@walk
            when (key) {
                "notificationRenderer" -> {
                    val id = o.str("notificationId") ?: return@walk
                    val nav = o.obj("navigationEndpoint")
                    val videoId = nav?.obj("watchEndpoint")?.str("videoId")
                    val path = nav?.obj("commandMetadata")?.obj("webCommandMetadata")?.str("url")
                    val url = when {
                        videoId != null -> "https://m.youtube.com/watch?v=$videoId"
                        path != null && path.startsWith("/") -> "https://m.youtube.com$path"
                        else -> null
                    }
                    items += InboxItem(
                        id = id,
                        text = text(o["shortMessage"]).orEmpty(),
                        time = text(o["sentTimeText"]).orEmpty(),
                        avatar = lastThumb(o["thumbnail"]),
                        thumbnail = lastThumb(o["videoThumbnail"]),
                        url = url,
                        videoId = videoId,
                        read = (o["read"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    )
                }
                "backgroundPromoRenderer" -> empty = listOfNotNull(text(o["title"]), text(o["bodyText"])).joinToString("\n").ifEmpty { null }
            }
        }
        return Inbox(items.distinctBy { it.id }, empty.takeIf { items.isEmpty() })
    }

    fun parseUnseenCount(body: String): Int {
        var count = 0
        walk(json.parseToJsonElement(body)) { key, value ->
            if (key == "unseenCount") (value as? JsonPrimitive)?.intOrNull?.let { count = it }
        }
        return count
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Depth-first over all object members, calling [visit] with each key and value. */
    private fun walk(e: JsonElement, visit: (String, JsonElement) -> Unit) {
        when (e) {
            is JsonObject -> for ((k, v) in e) { visit(k, v); walk(v, visit) }
            is JsonArray -> for (v in e) walk(v, visit)
            else -> Unit
        }
    }

    /** YouTube text: { simpleText } or { runs: [{ text }] } or { content }. */
    private fun text(e: JsonElement?): String? {
        val o = e as? JsonObject ?: return null
        o.str("simpleText")?.let { return it }
        o.str("content")?.let { return it }
        return o.arr("runs")?.joinToString("") { it.jsonObject.str("text").orEmpty() }?.ifEmpty { null }
    }

    private fun lastThumb(e: JsonElement?): String? =
        (e as? JsonObject)?.arr("thumbnails")?.lastOrNull()?.jsonObject?.str("url")?.let { if (it.startsWith("//")) "https:$it" else it }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.arr(k: String) = (this[k] as? JsonArray)
}
