package com.moddingforge.truetube.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moddingforge.truetube.R
import com.moddingforge.truetube.update.UpdateState
import com.moddingforge.truetube.update.Updater

/** Floating card above the feed: "TrueTube x.y is available [Update]", then download progress and install. */
@Composable
fun UpdateBanner(updater: Updater, modifier: Modifier = Modifier) {
    val state by updater.state.collectAsStateWithLifecycle()
    var dismissedFor by rememberSaveable { mutableStateOf("") }
    val visible = when (val s = state) {
        is UpdateState.Available -> s.release.version != dismissedFor
        is UpdateState.Downloading, is UpdateState.Installing, is UpdateState.Failed -> true
        else -> false
    }
    AnimatedVisibility(visible, enter = slideInVertically { it }, exit = slideOutVertically { it }, modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            UpdateCardContent(state, updater, onDismiss = { (state as? UpdateState.Available)?.let { dismissedFor = it.release.version } })
        }
    }
}

@Composable
private fun UpdateCardContent(state: UpdateState, updater: Updater, onDismiss: () -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SystemUpdate, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = updateStatusText(state),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when (state) {
                is UpdateState.Available -> {
                    FilledTonalButton(onClick = { updater.download(state.release) }) { Text(stringResource(R.string.update_button)) }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.close)) }
                }
                is UpdateState.Downloading -> TextButton(onClick = updater::cancel) { Text(stringResource(R.string.cancel)) }
                is UpdateState.Failed -> {
                    TextButton(onClick = { state.release?.let(updater::download) ?: updater.check() }) { Text(stringResource(R.string.update_retry)) }
                    IconButton(onClick = updater::reset) { Icon(Icons.Outlined.Close, stringResource(R.string.close)) }
                }
                is UpdateState.Installing -> TextButton(onClick = updater::reset) { Text(stringResource(R.string.close)) }
                else -> Unit
            }
        }
        if (state is UpdateState.Downloading) {
            val p = state.progress
            if (p >= 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(end = 10.dp, top = 2.dp, bottom = 6.dp))
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(end = 10.dp, top = 2.dp, bottom = 6.dp))
        }
    }
}

/** One-line status, shared with the settings screen. */
@Composable
fun updateStatusText(state: UpdateState): String = when (state) {
    UpdateState.Idle -> stringResource(R.string.update_check)
    UpdateState.Checking -> stringResource(R.string.update_checking)
    UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
    is UpdateState.Available -> stringResource(R.string.update_available, state.release.version)
    is UpdateState.Downloading ->
        if (state.progress >= 0f) stringResource(R.string.update_downloading, (state.progress * 100).toInt())
        else stringResource(R.string.update_downloading_unknown)
    is UpdateState.Installing -> stringResource(R.string.update_installing)
    is UpdateState.Failed -> stringResource(R.string.update_failed, state.message)
}
