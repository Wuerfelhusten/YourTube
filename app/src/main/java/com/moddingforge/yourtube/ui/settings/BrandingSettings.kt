package com.moddingforge.yourtube.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.WebAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moddingforge.yourtube.R
import com.moddingforge.yourtube.data.AppSettings
import com.moddingforge.yourtube.ui.MainActivity
import com.moddingforge.yourtube.ui.branding.AppIcon
import com.moddingforge.yourtube.ui.branding.BrandMark
import com.moddingforge.yourtube.ui.branding.Branding
import com.moddingforge.yourtube.ui.branding.rememberBrand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BRAND_MODES = listOf("default", "youtube", "premium", "custom", "off")

@Composable
private fun brandModeLabel(mode: String) = stringResource(
    when (mode) {
        "youtube" -> R.string.brand_youtube
        "premium" -> R.string.brand_premium
        "custom" -> R.string.brand_custom
        "off" -> R.string.brand_off
        else -> R.string.brand_default
    },
)

/** "App" page: top bar branding (YourTube / own text and image / off) and the launcher icon. */
@Composable
internal fun ColumnScope.BrandingSettings(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickMode by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(false) }

    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Branding.saveLogo(context, uri) }
            if (ok) update { it.copy(brandMode = "custom", brandLogo = System.currentTimeMillis()) }
            else Toast.makeText(context, R.string.brand_image_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val shortcutPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val label = s.brandText.trim().takeIf { s.brandMode == "custom" && it.isNotEmpty() } ?: context.getString(R.string.app_name)
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Branding.pinShortcut(context, uri, label) }
            if (!ok) Toast.makeText(context, R.string.brand_image_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val imageOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    // --- top bar ---
    Section(stringResource(R.string.section_branding))
    BrandPreview(rememberBrand(s))
    Item(Icons.Outlined.WebAsset, stringResource(R.string.brand_top_bar), brandModeLabel(s.brandMode)) { pickMode = true }
    if (s.brandMode == "custom") {
        Item(Icons.Outlined.TextFields, stringResource(R.string.brand_text),
            s.brandText.ifBlank { stringResource(R.string.brand_text_none) }) { editText = true }
        Item(Icons.Outlined.Image, stringResource(R.string.brand_image),
            stringResource(if (s.brandLogo > 0) R.string.brand_image_set else R.string.brand_image_none)) {
            logoPicker.launch(imageOnly)
        }
        if (s.brandLogo > 0) {
            Item(Icons.Outlined.Delete, stringResource(R.string.brand_image_remove), "") {
                Branding.deleteLogo(context)
                update { it.copy(brandLogo = 0) }
            }
        }
    }

    // --- launcher icon ---
    Section(stringResource(R.string.section_app_icon))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (icon in Branding.ICONS) {
            IconChoice(icon, selected = icon.id == s.appIcon) {
                if (icon.id == s.appIcon) return@IconChoice
                Branding.applyIcon(context, icon.id)
                MainActivity.publishShortcuts(context, icon.id)
                update { it.copy(appIcon = icon.id) }
                Toast.makeText(context, R.string.icon_changed, Toast.LENGTH_LONG).show()
            }
        }
    }
    Text(stringResource(R.string.icon_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    if (Branding.canPinShortcut(context)) {
        Item(Icons.Outlined.AddToHomeScreen, stringResource(R.string.icon_shortcut), stringResource(R.string.icon_shortcut_hint)) {
            shortcutPicker.launch(imageOnly)
        }
    }

    if (pickMode) {
        ChoiceDialog(stringResource(R.string.brand_top_bar), BRAND_MODES, s.brandMode, { brandModeLabel(it) },
            onDismiss = { pickMode = false }) { m -> update { it.copy(brandMode = m) }; pickMode = false }
    }
    if (editText) {
        var text by remember { mutableStateOf(s.brandText) }
        AlertDialog(
            onDismissRequest = { editText = false },
            title = { Text(stringResource(R.string.brand_text)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(30) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { update { it.copy(brandText = text.trim()) }; editText = false }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editText = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** How the top bar will look. */
@Composable
private fun BrandPreview(brand: com.moddingforge.yourtube.ui.branding.BrandSpec) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(56.dp),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            if (brand.visible) BrandMark(brand)
            else Text(stringResource(R.string.brand_preview_empty), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IconChoice(icon: AppIcon, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        Box(
            Modifier.size(56.dp).border(if (selected) 3.dp else 1.dp, ring, CircleShape).padding(4.dp)
                .clip(CircleShape).background(icon.background),
            contentAlignment = Alignment.Center,
        ) {
            // The foreground layer is drawn on a 108dp canvas with the visible part in the middle 72dp.
            Image(painterResource(icon.foreground), contentDescription = null, modifier = Modifier.size(72.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(icon.label), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
    }
}
