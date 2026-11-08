package com.moddingforge.yourtube.ui.branding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moddingforge.yourtube.R
import com.moddingforge.yourtube.data.AppSettings

/** What the top bar shows on the left. */
data class BrandSpec(val mode: String, val text: String, val logo: ImageBitmap?) {
    val visible get() = mode in BUILT_IN || (mode == "custom" && (text.isNotBlank() || logo != null))

    private companion object {
        /** Built-in brands: YourTube (default), YouTube, YouTube Premium. */
        val BUILT_IN = setOf("default", "youtube", "premium")
    }
}

/** Brand from the settings; the custom image is loaded again whenever its stamp changes. */
@Composable
fun rememberBrand(s: AppSettings): BrandSpec {
    val context = LocalContext.current
    val logo = remember(s.brandMode, s.brandLogo) {
        if (s.brandMode == "custom" && s.brandLogo > 0) Branding.loadLogo(context)?.asImageBitmap() else null
    }
    return BrandSpec(s.brandMode, s.brandText, logo)
}

/** Logo + name: YourTube, YouTube, YouTube Premium, or the user's own text and image. Nothing when off. */
@Composable
fun BrandMark(brand: BrandSpec, modifier: Modifier = Modifier) {
    if (!brand.visible) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val hasIcon = when (brand.mode) {
            "default" -> {
                Icon(painterResource(R.drawable.ic_logo), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(28.dp))
                true
            }
            "youtube", "premium" -> {
                Icon(painterResource(R.drawable.ic_brand_youtube), contentDescription = null, tint = Color.Unspecified,
                    modifier = Modifier.size(width = 29.dp, height = 20.dp))
                true
            }
            else -> brand.logo?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)))
                true
            } ?: false
        }
        val text = when (brand.mode) {
            "default" -> "YourTube"
            "youtube" -> "YouTube"
            "premium" -> "Premium"
            else -> brand.text.trim()
        }
        if (text.isNotEmpty()) {
            if (hasIcon) Spacer(Modifier.width(if (brand.mode == "youtube" || brand.mode == "premium") 4.dp else 6.dp))
            Text(text, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.5).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
