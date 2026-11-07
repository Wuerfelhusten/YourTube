package com.moddingforge.truetube.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moddingforge.truetube.BuildConfig
import com.moddingforge.truetube.TrueTubeApp
import com.moddingforge.truetube.ui.theme.TrueTubeTheme
import com.moddingforge.truetube.web.WebState
import com.moddingforge.truetube.web.WebViewHolder

class MainActivity : ComponentActivity() {

    private val holder: WebViewHolder
        get() = (application as TrueTubeApp).container.webViewHolder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrueTubeTheme {
                BrowserScreen(holder, this)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        holder.onStart()
    }

    override fun onStop() {
        holder.onStop()
        super.onStop()
    }
}

@Composable
private fun BrowserScreen(holder: WebViewHolder, activity: ComponentActivity) {
    val state by holder.state.collectAsStateWithLifecycle()

    // Back: WebView history first, then send the app to the background (don't finish it).
    BackHandler {
        if (!holder.goBack()) activity.moveTaskToBack(true)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // A new generation means the renderer crashed and the WebView was recreated.
        key(state.generation) {
            AndroidView(
                factory = {
                    holder.attach(activity).also {
                        if (state.generation > 0) holder.load(holder.consumePendingUrl()) else holder.loadIfEmpty()
                    }
                },
                onRelease = { holder.detach(activity) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (state.progress in 1..99) {
            LinearProgressIndicator(
                progress = { state.progress / 100f },
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            )
        }

        if (BuildConfig.DEBUG) DebugChip(state, Modifier.align(Alignment.TopEnd).padding(top = 4.dp))
    }
}

/** Debug-only overlay: bridge status and the last URL the bridge reported. */
@Composable
private fun DebugChip(state: WebState, modifier: Modifier = Modifier) {
    val bridge = when {
        !state.bridgeReady -> "bridge: -"
        state.bridgePingMs >= 0 -> "bridge: ok ${state.bridgePingMs}ms"
        else -> "bridge: ready"
    }
    Text(
        text = "$bridge | ${state.url.removePrefix("https://").take(40)}",
        color = Color.White,
        fontSize = 9.sp,
        maxLines = 1,
        modifier = modifier
            .widthIn(max = 220.dp)
            .padding(4.dp)
            .background(Color(0xAA000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
