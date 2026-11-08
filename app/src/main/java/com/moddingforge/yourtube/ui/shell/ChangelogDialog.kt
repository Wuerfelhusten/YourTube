package com.moddingforge.yourtube.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moddingforge.yourtube.R
import com.moddingforge.yourtube.update.Changelog
import com.moddingforge.yourtube.update.ChangelogEntry
import com.moddingforge.yourtube.update.Updater

/** Changelog entries in a dialog: one block per version, newest first. */
@Composable
fun ChangelogDialog(title: String, entries: List<ChangelogEntry>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (entries.isEmpty()) Text(stringResource(R.string.changelog_empty))
                entries.forEachIndexed { i, e ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    if (entries.size > 1) {
                        Text(stringResource(R.string.changelog_version, e.version), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(Changelog.plain(e.text), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

/** Shown once after every update with what changed since the version the user last saw. */
@Composable
fun WhatsNewPopup(updater: Updater) {
    val entries by updater.whatsNew.collectAsStateWithLifecycle()
    val list = entries ?: return
    val version = list.firstOrNull()?.version ?: com.moddingforge.yourtube.BuildConfig.VERSION_NAME
    ChangelogDialog(stringResource(R.string.whats_new_title, version), list, onDismiss = updater::markWhatsNewSeen)
}
