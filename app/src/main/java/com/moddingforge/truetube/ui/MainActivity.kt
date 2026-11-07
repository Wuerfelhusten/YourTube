package com.moddingforge.truetube.ui

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.moddingforge.truetube.R
import com.moddingforge.truetube.web.PlayerMode
import com.moddingforge.truetube.web.UrlRouter
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

private const val PIP_ACTION_TOGGLE = "com.moddingforge.truetube.PIP_TOGGLE"

class MainActivity : ComponentActivity() {

    private val container get() = (application as TrueTubeApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val suggestions = SearchSuggestions(container.json)
        val history = SearchHistory(this)
        setContent {
            TrueTubeTheme {
                AppShell(container, this, suggestions, history)
            }
        }
        handleDebugIntent(intent)
        // A fresh launch from a link; on recreation (e.g. after process death) the intent is old.
        if (savedInstanceState == null) handleLinkIntent(intent)

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

        // PiP parameters follow the player: auto-enter only while a video plays in the expanded player.
        lifecycleScope.launch {
            combine(
                container.playerController.mode,
                container.player.video.map { it.playing }.distinctUntilChanged(),
                container.settings.settings.map { it.autoPip }.distinctUntilChanged(),
            ) { _, _, _ -> }
                .collect { updatePipParams() }
        }

        // Background playback needs the media notification (Android 13+ asks once, on the first video).
        lifecycleScope.launch {
            container.playerController.mode.first { it != PlayerMode.HIDDEN }
            requestNotificationPermission()
        }

        pipReceiver.register()
    }

    override fun onDestroy() {
        pipReceiver.unregister()
        super.onDestroy()
    }

    // --- picture-in-picture --------------------------------------------------------------------

    private fun pipParams(): PictureInPictureParams {
        val playing = container.player.video.value.playing
        val action = RemoteAction(
            Icon.createWithResource(this, if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play),
            getString(if (playing) R.string.pause else R.string.play),
            getString(if (playing) R.string.pause else R.string.play),
            PendingIntent.getBroadcast(
                this, 1, Intent(PIP_ACTION_TOGGLE).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(action))
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(container.playerController.canEnterPip) }
            .build()
    }

    private fun updatePipParams() {
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    /** Android < 12 has no auto-enter: enter PiP when the user leaves (Home / recents). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && container.playerController.canEnterPip) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        container.playerController.setPip(isInPictureInPictureMode)
        if (!isInPictureInPictureMode) {
            // PiP window closed (not expanded back into the app): stop like the official app.
            window.decorView.postDelayed({
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) container.playerController.pause()
            }, 500)
        }
    }

    private val pipReceiver = PipReceiver()

    private inner class PipReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PIP_ACTION_TOGGLE) container.playerController.togglePlay()
        }

        fun register() = ContextCompat.registerReceiver(
            this@MainActivity, this, IntentFilter(PIP_ACTION_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        fun unregister() = runCatching { unregisterReceiver(this) }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        handleLinkIntent(intent)
    }

    /** YouTube link opened from another app, or text shared to TrueTube. */
    private fun handleLinkIntent(intent: Intent?) {
        intent ?: return
        val url = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let(UrlRouter::normalizeIncoming)
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(UrlRouter::findYouTubeLink)
            else -> null
        } ?: return
        android.util.Log.i("TT.Links", "open ${intent.action}: $url")
        window.decorView.post { container.playerController.navigate(url) }
    }

    override fun onStart() {
        super.onStart()
        container.feed.onStart()
        container.player.onStart()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onStop() {
        orientationListener.disable()
        // "Background playback" off: leaving the app pauses the video, like the official app without Premium.
        if (!container.settings.current.backgroundPlayback && !isInPictureInPictureMode) container.playerController.pause()
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
