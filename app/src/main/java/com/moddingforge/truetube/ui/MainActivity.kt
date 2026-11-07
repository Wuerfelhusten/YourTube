package com.moddingforge.truetube.ui

import android.content.Intent
import android.os.Bundle
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        container.feed.onStart()
        container.player.onStart()
    }

    override fun onStop() {
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
