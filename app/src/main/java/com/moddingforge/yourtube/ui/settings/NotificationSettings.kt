package com.moddingforge.yourtube.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.NotificationImportant
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moddingforge.yourtube.AppContainer
import com.moddingforge.yourtube.R
import com.moddingforge.yourtube.data.AppSettings
import com.moddingforge.yourtube.notify.NotifyWorker

private val INTERVALS = listOf(15, 30, 60, 180, 360)

@Composable
private fun intervalLabel(minutes: Int) =
    if (minutes < 60) stringResource(R.string.notify_every_minutes, minutes)
    else pluralHours(minutes / 60)

@Composable
private fun pluralHours(h: Int) = if (h == 1) stringResource(R.string.notify_every_hour) else stringResource(R.string.notify_every_hours, h)

/** "Notifications" page: new videos, YouTube's inbox, interval, network, check now. */
@Composable
internal fun ColumnScope.NotificationSettings(container: AppContainer, s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    val notifier = container.notifier
    val status by notifier.status.collectAsStateWithLifecycle()
    val web by container.feed.state.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(notifier.canNotify()) }
    var pickInterval by remember { mutableStateOf(false) }
    var asked by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(asked) {
        allowed = notifier.canNotify()
        onPauseOrDispose { }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { asked++ ; allowed = notifier.canNotify() }
    val enabled = s.notifyVideos || s.notifyInbox

    Text(stringResource(R.string.notify_hint), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

    if (!web.signedIn) {
        Item(Icons.Outlined.NotificationImportant, stringResource(R.string.notify_status_signed_out), stringResource(R.string.notify_sign_in_hint))
    } else if (enabled && !allowed) {
        Item(Icons.Outlined.NotificationImportant, stringResource(R.string.notify_allow), stringResource(R.string.notify_allow_hint)) {
            if (Build.VERSION.SDK_INT >= 33 && asked == 0) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else openSystemSettings(context)
        }
    }

    Section(stringResource(R.string.notify_section_what))
    SwitchItem(Icons.Outlined.Subscriptions, stringResource(R.string.notify_videos), stringResource(R.string.notify_videos_hint), s.notifyVideos) { v ->
        update { it.copy(notifyVideos = v) }
        if (v && !allowed && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    SwitchItem(Icons.Outlined.Inbox, stringResource(R.string.notify_inbox), stringResource(R.string.notify_inbox_hint), s.notifyInbox) { v ->
        update { it.copy(notifyInbox = v) }
        if (v && !allowed && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Section(stringResource(R.string.notify_section_when))
    Item(Icons.Outlined.Schedule, stringResource(R.string.notify_interval), intervalLabel(s.notifyInterval)) { if (enabled) pickInterval = true }
    SwitchItem(Icons.Outlined.Wifi, stringResource(R.string.notify_wifi_only), stringResource(R.string.notify_wifi_only_hint), s.notifyWifiOnly, enabled = enabled) { v ->
        update { it.copy(notifyWifiOnly = v) }
    }
    Item(Icons.Outlined.Refresh, stringResource(R.string.notify_check_now), status.ifEmpty { stringResource(R.string.notify_never_checked) }) {
        if (enabled) NotifyWorker.runNow(context)
    }
    Item(Icons.Outlined.Notifications, stringResource(R.string.settings_notifications), stringResource(R.string.notify_system_hint)) {
        openSystemSettings(context)
    }

    if (pickInterval) {
        ChoiceDialog(stringResource(R.string.notify_interval), INTERVALS, s.notifyInterval, { intervalLabel(it) },
            onDismiss = { pickInterval = false }) { v -> update { it.copy(notifyInterval = v) }; pickInterval = false }
    }
}

private fun openSystemSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
