package com.moddingforge.truetube.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.moddingforge.truetube.web.OrientationLock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.moddingforge.truetube.BuildConfig
import com.moddingforge.truetube.TrueTubeApp
import com.moddingforge.truetube.adblock.AdblockUpdateWorker
import com.moddingforge.truetube.data.SearchHistory
import com.moddingforge.truetube.data.SearchSuggestions
import com.moddingforge.truetube.ui.shell.AppShell
import com.moddingforge.truetube.ui.theme.TrueTubeTheme
import com.moddingforge.truetube.web.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = (application as TrueTubeApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val suggestions = SearchSuggestions(container.json)
        val history = SearchHistory(this)
        setContent {
            TrueTubeTheme {
                AppShell(container.playerController, this, suggestions, history)
            }
        }
        handleDebugIntent(intent)

        // Fullscreen toggled by the user forces an orientation (see PlayerController).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.playerController.orientationLock.collect { lock ->
                    requestedOrientation = when (lock) {
                        OrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        OrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        OrientationLock.NONE -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                    // The sensor only reports changes: if the phone is already held upright (or we
                    // never got a reading), hand back to the sensor right away.
                    if (lock == OrientationLock.PORTRAIT && autoRotateOn() && (lastDegrees < 0 || isPortrait(lastDegrees))) {
                        delay(800)
                        if (container.playerController.orientationLock.value == OrientationLock.PORTRAIT) {
                            container.playerController.releaseOrientation()
                        }
                    }
                }
            }
        }
    }

    /**
     * Once the phone is physically held the way a forced orientation wants it, hand control back
     * to the sensor - so rotating back later leaves/enters fullscreen naturally (official app).
     * With system auto-rotate off, the lock simply stays until the user toggles fullscreen again.
     */
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                lastDegrees = degrees
                if (!autoRotateOn()) return
                when (container.playerController.orientationLock.value) {
                    OrientationLock.LANDSCAPE -> if (isLandscape(degrees)) container.playerController.releaseOrientation()
                    OrientationLock.PORTRAIT -> if (isPortrait(degrees)) container.playerController.releaseOrientation()
                    OrientationLock.NONE -> Unit
                }
            }
        }
    }

    /** Last physical orientation reported by the sensor, -1 = none yet. */
    private var lastDegrees = -1

    private fun autoRotateOn() = Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
    private fun isPortrait(deg: Int) = deg <= 20 || deg >= 340
    private fun isLandscape(deg: Int) = deg in 70..110 || deg in 250..290

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        container.feed.onStart()
        container.player.onStart()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onStop() {
        orientationListener.disable()
        container.feed.onStop()
        container.player.onStop()
        super.onStop()
    }

    /**
     * Debug builds only (`adb shell am start -n <pkg>/.ui.MainActivity --es <extra> <value>`):
     *  - tt_url <url>                 open a URL (watch URLs open in the player)
     *  - tt_eval <js>                 evaluate JS in the feed, result in logcat (TT.Eval)
     *  - tt_eval_player <js>          same, in the player WebView
     *  - tt_adblock_update now|force  run the list update worker / update + force an engine rebuild
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        intent.getStringExtra("tt_url")?.let { url -> window.decorView.post { container.playerController.navigate(url) } }
        intent.getStringExtra("tt_eval")?.let { js -> window.decorView.post { container.feed.debugEval(js) } }
        intent.getStringExtra("tt_eval_player")?.let { js -> window.decorView.post { container.player.debugEval(js) } }
        when (intent.getStringExtra("tt_adblock_update")) {
            "now" -> AdblockUpdateWorker.runNow(this)
            "force" -> container.appScope.launch { container.filterRepository.updateLists(forceRebuild = true) }
        }
    }

    @Suppress("unused") private val roles = Role.entries
}
