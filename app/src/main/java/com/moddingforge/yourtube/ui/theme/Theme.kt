package com.moddingforge.yourtube.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Brand = Color(0xFF7C4DFF)
private val BrandDark = Color(0xFFB39DFF)

private val LightColors = lightColorScheme(
    primary = Brand,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = BrandDark,
    background = Color(0xFF0F0F0F),
    surface = Color(0xFF0F0F0F),
)

@Composable
fun YourTubeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    /** Pure black backgrounds in dark mode (AMOLED). */
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val colors = if (darkTheme && amoled) base.copy(background = Color.Black, surface = Color.Black, surfaceContainer = Color.Black, surfaceContainerLow = Color.Black) else base
    MaterialTheme(colorScheme = colors, content = content)
}
