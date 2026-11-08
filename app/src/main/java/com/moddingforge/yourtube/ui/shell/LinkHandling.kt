package com.moddingforge.yourtube.ui.shell

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.moddingforge.yourtube.R

/**
 * YouTube links are not verifiable app links for us (we don't own the domains). Since Android 12
 * they only open in YourTube after the user adds them under "Open by default".
 */
object LinkHandling {
    private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")

    /** null = not applicable (Android < 12, links work via the chooser). */
    fun linksEnabled(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return null
        val state = runCatching { manager.getDomainVerificationUserState(context.packageName) }.getOrNull() ?: return null
        val selected = state.hostToStateMap.filterValues {
            it == DomainVerificationUserState.DOMAIN_STATE_SELECTED || it == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        }.keys
        return HOSTS.all { it in selected }
    }

    fun openSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, "package:${context.packageName}".toUri())
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private const val PREFS = "link_handling"
    private const val KEY_HINT_SHOWN = "hint_shown"

    fun shouldShowHint(context: Context): Boolean =
        linksEnabled(context) == false &&
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HINT_SHOWN, false)

    fun markHintShown(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HINT_SHOWN, true).apply()
    }
}

/** One-time hint on first start: "Open YouTube links in YourTube?" */
@Composable
fun LinkHint() {
    val context = LocalContext.current
    var show by remember { mutableStateOf(LinkHandling.shouldShowHint(context)) }
    if (!show) return
    val dismiss = {
        LinkHandling.markHintShown(context)
        show = false
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.links_hint_title)) },
        text = { Text(stringResource(R.string.links_hint_text)) },
        confirmButton = {
            TextButton(onClick = { dismiss(); LinkHandling.openSettings(context) }) { Text(stringResource(R.string.links_hint_open)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.later)) } },
    )
}
