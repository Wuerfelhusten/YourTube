package com.moddingforge.truetube.ui

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.moddingforge.truetube.adblock.AdblockUpdateWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * Debug builds only (`adb shell am start -n <pkg>/.ui.MainActivity --es <extra> <value>`):
     *  - tt_url <url>                 open a URL
     *  - tt_adblock_update now|force  run the list update worker / update + force an engine rebuild
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        intent.getStringExtra("tt_url")?.let { url -> window.decorView.post { holder.load(url) } }
        when (intent.getStringExtra("tt_adblock_update")) {
            "now" -> AdblockUpdateWorker.runNow(this)
            "force" -> container.appScope.launch { container.filterRepository.updateLists(forceRebuild = true) }
        }
    }

    private val container get() = (application as TrueTubeApp).container

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

        if (BuildConfig.DEBUG) DebugChip(state, holder, Modifier.align(Alignment.TopEnd).padding(top = 4.dp))
    }
}

/** Debug-only overlay: bridge status and the last URL the bridge reported. */
@Composable
private fun DebugChip(state: WebState, holder: WebViewHolder, modifier: Modifier = Modifier) {
    // Counters change on background threads; poll them once a second (debug only).
    var blocked by remember { mutableIntStateOf(0) }
    var skipped by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            blocked = holder.adblock.blockedRequests.get()
            skipped = holder.adblock.skippedAds.get()
            delay(1_000)
        }
    }
    val bridge = when {
        !state.bridgeReady -> "bridge: -"
        state.bridgePingMs >= 0 -> "bridge: ok ${state.bridgePingMs}ms"
        else -> "bridge: ready"
    }
    Text(
        text = "$bridge | ${if (state.signedIn) "acc: ok" else "acc: -"} | ab: $blocked/$skipped | ${state.url.removePrefix("https://").take(24)}",
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
