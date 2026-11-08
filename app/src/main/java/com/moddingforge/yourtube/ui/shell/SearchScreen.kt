package com.moddingforge.yourtube.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NorthWest
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moddingforge.yourtube.R
import com.moddingforge.yourtube.data.SearchHistory
import com.moddingforge.yourtube.data.SearchSuggestions
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay

/**
 * Native search screen like the official app: query field, history (clock icon) and live
 * suggestions; the arrow on the right puts a suggestion into the field without searching.
 */
@Composable
fun SearchScreen(
    initialQuery: String,
    suggestions: SearchSuggestions,
    history: SearchHistory,
    onSearch: (String) -> Unit,
    onClose: () -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue(initialQuery, TextRange(initialQuery.length))) }
    var remote by remember { mutableStateOf(emptyList<String>()) }
    var past by remember { mutableStateOf(history.all()) }
    val focus = remember { FocusRequester() }

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(field.text) {
        delay(150) // debounce typing
        remote = suggestions.fetch(field.text)
    }

    fun submit(q: String) {
        if (q.isBlank()) return
        history.add(q)
        onSearch(q)
    }

    val shownHistory = past.filter { field.text.isBlank() || it.startsWith(field.text, ignoreCase = true) }.take(if (field.text.isBlank()) 15 else 3)
    val shownRemote = remote.filterNot { r -> shownHistory.any { it.equals(r, ignoreCase = true) } }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            // Compact pill field like the official app (Material's TextField needs 56dp).
            Row(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (field.text.isEmpty()) {
                        Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = field,
                        onValueChange = { field = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit(field.text) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (field.text.isNotEmpty()) {
                    IconButton(onClick = { field = TextFieldValue("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear))
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(shownHistory, key = { "h:$it" }) { q ->
                SuggestionRow(Icons.Outlined.History, q,
                    onClick = { submit(q) },
                    onFill = { field = TextFieldValue("$q ", TextRange(q.length + 1)) },
                    onLongClick = { history.remove(q); past = history.all() })
            }
            items(shownRemote, key = { "s:$it" }) { q ->
                SuggestionRow(Icons.Outlined.Search, q,
                    onClick = { submit(q) },
                    onFill = { field = TextFieldValue("$q ", TextRange(q.length + 1)) })
            }
        }
    }
}

@Composable
private fun SuggestionRow(icon: ImageVector, text: String, onClick: () -> Unit, onFill: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp)
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (onLongClick != null) {
            IconButton(onClick = onLongClick) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove_from_history), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onFill) {
            Icon(Icons.Outlined.NorthWest, contentDescription = stringResource(R.string.use_suggestion), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
