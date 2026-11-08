package com.moddingforge.yourtube.ui.shell

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moddingforge.yourtube.R
import com.moddingforge.yourtube.notify.Inbox
import com.moddingforge.yourtube.notify.InboxItem
import com.moddingforge.yourtube.notify.Notifier
import com.moddingforge.yourtube.notify.YouTubeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Bell in the top bar, with the number of unread YouTube notifications. */
@Composable
fun NotificationBell(count: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(badge = {
            if (count > 0) Badge { Text(if (count > 9) "9+" else count.toString()) }
        }) {
            Icon(Icons.Outlined.Notifications, stringResource(R.string.notifications_title))
        }
    }
}

/** YouTube's notification inbox as a native screen (m.youtube itself has none). */
@Composable
fun NotificationsScreen(notifier: Notifier, onOpen: (String) -> Unit, onSettings: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var reload by remember { mutableIntStateOf(0) }
    var inbox by remember { mutableStateOf<Inbox?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val errorText = stringResource(R.string.notifications_error)
    val signedOutText = stringResource(R.string.notify_status_signed_out)
    LaunchedEffect(reload) {
        error = null
        runCatching { notifier.fetchInbox() }
            .onSuccess { inbox = it }
            .onFailure { error = if (it is YouTubeApi.NotSignedIn) signedOutText else errorText }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.notifications_title), fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            IconButton(onClick = { inbox = null; reload++ }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh)) }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title)) }
        }
        val current = inbox
        when {
            error != null -> Message(error!!)
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            current.items.isEmpty() -> Message(current.emptyText ?: stringResource(R.string.notifications_empty))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                current.items.forEachIndexed { i, item ->
                    val header = item.section?.takeIf { it != current.items.getOrNull(i - 1)?.section }
                    if (header != null) item(key = "section-$i") {
                        Text(header, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (i == 0) 8.dp else 20.dp, bottom = 4.dp))
                    }
                    item(key = item.id) {
                        InboxRow(item) { item.url?.let(onOpen) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Icon(Icons.Outlined.NotificationsNone, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InboxRow(item: InboxItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Unread dot like the official app.
        Box(Modifier.padding(top = 18.dp).size(6.dp).clip(CircleShape)
            .background(if (item.read) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(6.dp))
        if (item.avatar != null) RemoteImage(item.avatar, Modifier.size(44.dp).clip(CircleShape))
        else Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            // Likes and other account events come without an avatar (YouTube shows a thumbs-up).
            Icon(Icons.Outlined.ThumbUp, null, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(item.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.thumbnail != null) {
            Spacer(Modifier.width(12.dp))
            RemoteImage(item.thumbnail, Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
        }
    }
}

// --- tiny image loader (avatars and thumbnails of the inbox) ----------------------------------

private val imageCache = LruCache<String, ImageBitmap>(64)

@Composable
fun RemoteImage(url: String?, modifier: Modifier) {
    val image by produceState(url?.let { imageCache.get(it) }, url) {
        if (url == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                try { conn.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() } } finally { conn.disconnect() }
            }.getOrNull()
        }?.also { imageCache.put(url, it) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}
