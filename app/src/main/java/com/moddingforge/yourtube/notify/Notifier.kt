package com.moddingforge.yourtube.notify

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.moddingforge.yourtube.R
import com.moddingforge.yourtube.data.AppSettings
import com.moddingforge.yourtube.data.SettingsRepository
import com.moddingforge.yourtube.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date

/**
 * YourTube's own notifications (YouTube's push messages only reach the official app):
 *  - new videos in the subscriptions feed,
 *  - new entries of YouTube's bell inbox (replies, mentions, channels with the bell on),
 * checked periodically by [NotifyWorker]; plus the unread count for the bell in the top bar.
 *
 * The first check only remembers what is there, so enabling it never floods the shade.
 */
class Notifier(private val app: Application, private val settings: SettingsRepository) {
    val api = YouTubeApi()
    private val prefs = app.getSharedPreferences("notify", 0)
    private val mutex = Mutex()

    private val _unseen = MutableStateFlow(prefs.getInt(KEY_UNSEEN, 0))
    private val _acknowledged = MutableStateFlow(prefs.getInt(KEY_ACK, 0))
    /** Unread inbox entries YouTube reports, minus those already shown when the bell was opened. */
    val unseen: StateFlow<Int> = _unseen.asStateFlow()
    val acknowledged: StateFlow<Int> = _acknowledged.asStateFlow()

    private val _status = MutableStateFlow(prefs.getString(KEY_STATUS, "").orEmpty())
    /** Result of the last check, for the settings page. */
    val status: StateFlow<String> = _status.asStateFlow()

    data class Result(val videos: Int, val inbox: Int)

    /** One check: feed + inbox per settings, posts notifications for what is new. */
    suspend fun check(force: Boolean = false): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            val s = settings.current
            if (!force && !s.notifyVideos && !s.notifyInbox) return@withLock Result(0, 0)
            if (!api.signedIn) {
                setStatus(app.getString(R.string.notify_status_signed_out))
                return@withLock Result(0, 0)
            }
            ensureChannels()
            val notifiedVideos = mutableSetOf<String>()
            var videos = 0
            var inbox = 0
            if (s.notifyVideos) {
                val feed = api.subscriptionFeed().filter { passesFilters(it, s) }
                videos = handleFeed(feed, notifiedVideos)
            }
            if (s.notifyInbox) {
                inbox = handleInbox(api.inbox(), notifiedVideos)
            }
            refreshUnseenLocked()
            setStatus(app.getString(R.string.notify_status_checked, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()), videos + inbox))
            Log.i(TAG, "check: $videos new videos, $inbox inbox entries")
            Result(videos, inbox)
        }
    }

    /** Unread count for the bell (cheap; on app start / resume). */
    suspend fun refreshUnseen() = withContext(Dispatchers.IO) {
        runCatching { mutex.withLock { refreshUnseenLocked() } }.onFailure { Log.w(TAG, "unseen: ${it.message}") }
    }

    private fun refreshUnseenLocked() {
        if (!api.signedIn) return
        val count = api.unseenCount()
        _unseen.value = count
        // YouTube lowered the count (read elsewhere): forget the acknowledged surplus.
        if (_acknowledged.value > count) _acknowledged.value = count
        prefs.edit().putInt(KEY_UNSEEN, count).putInt(KEY_ACK, _acknowledged.value).apply()
    }

    /** The bell was opened: hide the badge until YouTube reports more unread entries. */
    fun acknowledge() {
        _acknowledged.value = _unseen.value
        prefs.edit().putInt(KEY_ACK, _acknowledged.value).apply()
    }

    suspend fun fetchInbox(): Inbox = withContext(Dispatchers.IO) { api.inbox() }

    // --- new videos ---------------------------------------------------------------------------

    private fun handleFeed(feed: List<FeedVideo>, notified: MutableSet<String>): Int {
        if (feed.isEmpty()) return 0
        val seen = loadList(KEY_SEEN_VIDEOS)
        val first = !prefs.getBoolean(KEY_INIT_VIDEOS, false)
        val fresh = if (first) emptyList() else feed.filter { it.id !in seen }
        saveList(KEY_SEEN_VIDEOS, feed.map { it.id } + seen)
        prefs.edit().putBoolean(KEY_INIT_VIDEOS, true).apply()
        if (!canNotify()) return 0
        // Oldest first, so the newest ends up on top of the shade.
        for (v in fresh.take(MAX_PER_CHECK).reversed()) {
            notified += v.id
            post(
                id = v.id.hashCode(),
                channel = CHANNEL_VIDEOS,
                title = v.channel.ifEmpty { app.getString(R.string.notify_new_video) },
                text = v.title,
                url = v.url,
                image = download(v.thumbnail),
                group = GROUP_VIDEOS,
            )
        }
        if (fresh.size > 1) postSummary(CHANNEL_VIDEOS, GROUP_VIDEOS, app.getString(R.string.notify_new_videos, fresh.size))
        return fresh.size
    }

    // --- bell inbox ---------------------------------------------------------------------------

    private fun handleInbox(inbox: Inbox, notifiedVideos: Set<String>): Int {
        if (inbox.items.isEmpty()) return 0
        val seen = loadList(KEY_SEEN_INBOX)
        val first = !prefs.getBoolean(KEY_INIT_INBOX, false)
        val fresh = if (first) emptyList() else inbox.items.filter { !it.read && it.id !in seen && it.videoId !in notifiedVideos }
        saveList(KEY_SEEN_INBOX, inbox.items.map { it.id } + seen)
        prefs.edit().putBoolean(KEY_INIT_INBOX, true).apply()
        if (!canNotify()) return 0
        for (n in fresh.take(MAX_PER_CHECK).reversed()) {
            post(
                id = n.id.hashCode(),
                channel = CHANNEL_INBOX,
                title = app.getString(R.string.app_name),
                text = n.text,
                url = n.url ?: "https://m.youtube.com/",
                image = n.thumbnail?.let(::download),
                group = GROUP_INBOX,
            )
        }
        if (fresh.size > 1) postSummary(CHANNEL_INBOX, GROUP_INBOX, app.getString(R.string.notify_inbox_count, fresh.size))
        return fresh.size
    }

    // --- posting ------------------------------------------------------------------------------

    fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(app).areNotificationsEnabled()
    }

    fun ensureChannels() {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_VIDEOS, app.getString(R.string.notify_channel_videos), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_INBOX, app.getString(R.string.notify_channel_inbox), NotificationManager.IMPORTANCE_DEFAULT))
    }

    @Suppress("MissingPermission") // checked in canNotify()
    private fun post(id: Int, channel: String, title: String, text: String, url: String, image: Bitmap?, group: String) {
        val open = Intent(Intent.ACTION_VIEW, url.toUri(), app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(app, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(app, R.color.tt_brand))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setGroup(group)
            .apply {
                if (image != null) {
                    setLargeIcon(image)
                    setStyle(NotificationCompat.BigPictureStyle().bigPicture(image).bigLargeIcon(null as Bitmap?).setSummaryText(text))
                } else {
                    setStyle(NotificationCompat.BigTextStyle().bigText(text))
                }
            }
            .build()
        NotificationManagerCompat.from(app).notify(id, n)
    }

    @Suppress("MissingPermission")
    private fun postSummary(channel: String, group: String, text: String) {
        val n = NotificationCompat.Builder(app, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(app, R.color.tt_brand))
            .setContentTitle(app.getString(R.string.app_name))
            .setContentText(text)
            .setGroup(group)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(app).notify(group.hashCode(), n)
    }

    private fun download(url: String): Bitmap? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try { conn.inputStream.use { BitmapFactory.decodeStream(it) } } finally { conn.disconnect() }
    }.getOrNull()

    // --- helpers ------------------------------------------------------------------------------

    /** Same title keyword filter as the feeds. */
    private fun passesFilters(v: FeedVideo, s: AppSettings): Boolean {
        val words = s.filterKeywords.lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val title = v.title.lowercase()
        return words.none { it in title }
    }

    private fun loadList(key: String) = prefs.getString(key, "").orEmpty().split(',').filter { it.isNotEmpty() }
    private fun saveList(key: String, ids: List<String>) = prefs.edit().putString(key, ids.distinct().take(MAX_REMEMBERED).joinToString(",")).apply()

    private fun setStatus(text: String) {
        _status.value = text
        prefs.edit().putString(KEY_STATUS, text).apply()
    }

    /** Debug builds: forget the [n] newest videos / inbox entries, so the next check notifies them. */
    fun debugForget(n: Int) {
        saveList(KEY_SEEN_VIDEOS, loadList(KEY_SEEN_VIDEOS).drop(n))
        saveList(KEY_SEEN_INBOX, loadList(KEY_SEEN_INBOX).drop(n))
    }

    companion object {
        private const val TAG = "TT.Notify"
        const val CHANNEL_VIDEOS = "new_videos"
        const val CHANNEL_INBOX = "youtube_inbox"
        private const val GROUP_VIDEOS = "yourtube_videos"
        private const val GROUP_INBOX = "yourtube_inbox"
        private const val MAX_PER_CHECK = 8
        private const val MAX_REMEMBERED = 400
        private const val KEY_SEEN_VIDEOS = "seen_videos"
        private const val KEY_SEEN_INBOX = "seen_inbox"
        private const val KEY_INIT_VIDEOS = "init_videos"
        private const val KEY_INIT_INBOX = "init_inbox"
        private const val KEY_UNSEEN = "unseen"
        private const val KEY_ACK = "acknowledged"
        private const val KEY_STATUS = "status"
    }
}
