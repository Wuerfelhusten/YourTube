package com.moddingforge.truetube.ui.settings

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moddingforge.truetube.AppContainer
import com.moddingforge.truetube.BuildConfig
import com.moddingforge.truetube.R
import com.moddingforge.truetube.adblock.AdblockEngine
import com.moddingforge.truetube.adblock.AdblockUpdateWorker
import com.moddingforge.truetube.adblock.EngineState
import com.moddingforge.truetube.ui.shell.LinkHandling
import kotlinx.coroutines.delay

/**
 * TrueTube's own settings ("TrueTube Settings" entry at the top of YouTube's settings page and in
 * the top bar menu). First version: ad blocker status/update, link handling, account, about.
 * Playback and appearance switches follow in M9.
 */
@Composable
fun SettingsScreen(container: AppContainer, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val engine by container.filterRepository.state.collectAsStateWithLifecycle()
    var blocked by remember { mutableIntStateOf(0) }
    var linksEnabled by remember { mutableStateOf(LinkHandling.linksEnabled(context)) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var updateRequested by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            blocked = container.feed.adblock.blockedRequests.get() + container.player.adblock.blockedRequests.get()
            delay(1_000)
        }
    }
    // Coming back from the system "Open by default" screen.
    LifecycleResumeEffect(Unit) {
        linksEnabled = LinkHandling.linksEnabled(context)
        onPauseOrDispose { }
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
            Text(stringResource(R.string.settings_title), fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            // --- ad blocker ---
            Section(stringResource(R.string.settings_adblock))
            val engineSummary = when (val s = engine) {
                is EngineState.Ready -> stringResource(
                    R.string.settings_adblock_ready, s.lists,
                    if (s.listsUpdatedAt > 0) DateUtils.getRelativeTimeSpanString(s.listsUpdatedAt).toString()
                    else stringResource(R.string.settings_adblock_snapshot),
                )
                is EngineState.Unavailable -> stringResource(R.string.settings_adblock_unavailable, s.reason)
                EngineState.Loading -> stringResource(R.string.settings_adblock_loading)
            }
            Item(Icons.Outlined.Shield, stringResource(R.string.settings_adblock_engine), engineSummary)
            Item(Icons.Outlined.Shield, stringResource(R.string.settings_adblock_blocked), blocked.toString())
            Item(
                Icons.Outlined.Refresh,
                stringResource(R.string.settings_adblock_update),
                stringResource(if (updateRequested) R.string.settings_adblock_update_started else R.string.settings_adblock_update_hint),
            ) {
                AdblockUpdateWorker.runNow(context)
                updateRequested = true
            }

            // --- links ---
            Section(stringResource(R.string.settings_links))
            linksEnabled?.let { enabled ->
                Item(
                    Icons.Outlined.Link,
                    stringResource(R.string.links_hint_title),
                    stringResource(if (enabled) R.string.settings_links_on else R.string.settings_links_off),
                ) { LinkHandling.openSettings(context) }
            }

            // --- account ---
            Section(stringResource(R.string.settings_account))
            Item(Icons.AutoMirrored.Filled.Logout, stringResource(R.string.settings_sign_out), stringResource(R.string.settings_sign_out_hint)) {
                confirmSignOut = true
            }

            // --- about ---
            Section(stringResource(R.string.settings_about))
            Item(Icons.Outlined.Info, "TrueTube ${BuildConfig.VERSION_NAME}",
                stringResource(R.string.settings_about_text, runCatching { AdblockEngine.nativeVersion }.getOrDefault("?")))
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.settings_sign_out)) },
            text = { Text(stringResource(R.string.settings_sign_out_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    container.playerController.close()
                    container.feed.signOut()
                    onClose()
                }) { Text(stringResource(R.string.settings_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    Text(
        title,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Item(icon: ImageVector, title: String, summary: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
