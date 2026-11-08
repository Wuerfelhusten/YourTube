package com.moddingforge.yourtube.ui.branding

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.graphics.Color
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.moddingforge.yourtube.R
import com.moddingforge.yourtube.ui.MainActivity
import java.io.File

/** A launcher icon variant: one activity-alias in the manifest (`.Launcher<Suffix>`). */
data class AppIcon(
    val id: String,
    val alias: String,
    val label: Int,
    /** Preview: background colour and foreground drawable (the adaptive icon's layers). */
    val background: Color,
    val foreground: Int,
)

/**
 * User branding: the top bar mark (YourTube / own text and image / off) and the launcher icon.
 *
 * Android has no API to give an app an arbitrary launcher icon at runtime. So the launcher icon is
 * one of the variants shipped in the APK (activity-aliases, exactly one enabled), and a free
 * choice of image goes onto the home screen as a pinned shortcut.
 */
object Branding {
    val ICONS = listOf(
        AppIcon("default", ".LauncherDefault", R.string.icon_default, Color(0xFF1B1530), R.drawable.ic_launcher_foreground),
        AppIcon("light", ".LauncherLight", R.string.icon_light, Color(0xFFFFFFFF), R.drawable.ic_launcher_fg_light),
        AppIcon("black", ".LauncherBlack", R.string.icon_black, Color(0xFF000000), R.drawable.ic_launcher_fg_black),
        AppIcon("ocean", ".LauncherOcean", R.string.icon_ocean, Color(0xFF0B2233), R.drawable.ic_launcher_fg_ocean),
        AppIcon("sunset", ".LauncherSunset", R.string.icon_sunset, Color(0xFF2A1410), R.drawable.ic_launcher_fg_sunset),
        AppIcon("plain", ".LauncherPlain", R.string.icon_plain, Color(0xFF1B1530), R.drawable.ic_launcher_fg_plain),
        AppIcon("youtube", ".LauncherYoutube", R.string.icon_youtube, Color(0xFFFFFFFF), R.drawable.ic_launcher_fg_youtube),
    )

    // --- top bar logo image ---------------------------------------------------------------------

    fun logoFile(context: Context) = File(File(context.filesDir, "branding"), "logo.png")

    /** Decodes the picked image, scales it down (max 256 px) and stores it as the top bar logo. */
    fun saveLogo(context: Context, uri: Uri): Boolean = runCatching {
        val bitmap = decode(context, uri, 256)
        val file = logoFile(context).apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }.getOrDefault(false)

    fun deleteLogo(context: Context) {
        logoFile(context).delete()
    }

    fun loadLogo(context: Context): Bitmap? =
        logoFile(context).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }

    // --- launcher icon --------------------------------------------------------------------------

    /** Enables the alias of [id] and disables all others. The launcher may need a moment to update. */
    fun applyIcon(context: Context, id: String) {
        val pm = context.packageManager
        val target = ICONS.firstOrNull { it.id == id } ?: ICONS.first()
        // Enable the new one first, so there is never a moment without a launcher entry.
        setAlias(pm, context, target.alias, true)
        for (icon in ICONS) if (icon != target) setAlias(pm, context, icon.alias, false)
    }

    private fun setAlias(pm: PackageManager, context: Context, alias: String, enabled: Boolean) {
        val component = ComponentName(context.packageName, "com.moddingforge.yourtube$alias")
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (pm.getComponentEnabledSetting(component) != state) {
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }

    // --- home screen shortcut with an own image --------------------------------------------------

    fun canPinShortcut(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the launcher to pin a shortcut that opens YourTube, with the picked image as icon. */
    fun pinShortcut(context: Context, uri: Uri, label: String): Boolean = runCatching {
        val square = squareCrop(decode(context, uri, 512), 256)
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val info = ShortcutInfoCompat.Builder(context, "custom-icon-${System.currentTimeMillis()}")
            .setShortLabel(label)
            .setIcon(IconCompat.createWithBitmap(square))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }.getOrDefault(false)

    // --- images ---------------------------------------------------------------------------------

    private fun decode(context: Context, uri: Uri, maxSize: Int): Bitmap {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val s = info.size
                val scale = minOf(1f, maxSize.toFloat() / maxOf(s.width, s.height))
                decoder.setTargetSize((s.width * scale).toInt().coerceAtLeast(1), (s.height * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
        val scale = minOf(1f, maxSize.toFloat() / maxOf(bitmap.width, bitmap.height))
        return if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    }

    private fun squareCrop(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val cropped = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(cropped, size, size, true)
    }
}
