package com.moddingforge.truetube.adblock

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** assets/adblock/manifest.json, written by tools/adblock/build-snapshot.mjs. */
@Serializable
data class SnapshotManifest(
    val format: Int,
    val generatedAt: String,
    val resources: String,
    val components: List<Component>,
) {
    @Serializable
    data class Component(
        val uuid: String,
        val title: String,
        val defaultEnabled: Boolean,
        val permissionMask: Int,
        val lists: List<ListRef>,
    )

    @Serializable
    data class ListRef(val url: String, val title: String = "", val file: String)
}

sealed interface EngineState {
    data object Loading : EngineState
    data class Ready(val engine: AdblockEngine, val lists: Int, val loadMs: Long, val selfTestOk: Boolean) : EngineState
    data class Unavailable(val reason: String) : EngineState
}

/**
 * Owns the adblock engine: unpacks the bundled snapshot into app storage, builds the engine
 * in the background and publishes it via [state]. List updates and the engine cache follow in M3c.
 */
class FilterRepository(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope,
    private val debug: Boolean,
) {
    private val _state = MutableStateFlow<EngineState>(EngineState.Loading)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** The current engine, or null while loading / unavailable. Cheap; safe from any thread. */
    val engine: AdblockEngine? get() = (_state.value as? EngineState.Ready)?.engine

    private val snapshotDir get() = File(context.filesDir, "adblock/snapshot")

    fun load() {
        scope.launch(Dispatchers.IO) {
            if (debug) jniSmokeTest()
            _state.value = try {
                buildEngine()
            } catch (e: Throwable) {
                Log.e(TAG, "Adblock engine unavailable", e)
                EngineState.Unavailable(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun buildEngine(): EngineState = withContext(Dispatchers.IO) {
        val manifest = unpackSnapshot()
        val start = SystemClock.elapsedRealtime()

        val enabled = manifest.components.filter { it.defaultEnabled }
        val lists = enabled.flatMap { comp -> comp.lists.map { File(snapshotDir, it.file).path to comp.permissionMask } }
            .distinctBy { it.first }
        val engine = AdblockEngine.fromFiles(lists, File(snapshotDir, manifest.resources).path, json, debug = false)
        val loadMs = SystemClock.elapsedRealtime() - start

        val selfTest = selfTest(engine)
        Log.i(TAG, "Engine ready (adblock-rust ${AdblockEngine.nativeVersion}): ${enabled.size} components, " +
            "${lists.size} lists, built in ${loadMs}ms, self-test ${if (selfTest) "OK" else "FAILED"}")
        EngineState.Ready(engine, lists.size, loadMs, selfTest)
    }

    /**
     * Copies assets/adblock into filesDir (Rust reads plain files). Re-copies only when the
     * bundled snapshot is newer than the unpacked one.
     */
    private fun unpackSnapshot(): SnapshotManifest {
        val bundled = try {
            context.assets.open("$ASSET_DIR/manifest.json").bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            throw IllegalStateException("No adblock snapshot bundled - run: node tools/adblock/build-snapshot.mjs", e)
        }
        val manifest = json.decodeFromString(SnapshotManifest.serializer(), bundled)

        val marker = File(snapshotDir, "manifest.json")
        val current = marker.takeIf { it.exists() }?.readText()
            ?.let { runCatching { json.decodeFromString(SnapshotManifest.serializer(), it) }.getOrNull() }
        if (current?.generatedAt == manifest.generatedAt) return manifest

        val start = SystemClock.elapsedRealtime()
        snapshotDir.deleteRecursively()
        copyAssetTree(ASSET_DIR, snapshotDir)
        Log.i(TAG, "Unpacked snapshot ${manifest.generatedAt} in ${SystemClock.elapsedRealtime() - start}ms")
        return manifest
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            children.forEach { copyAssetTree("$assetPath/$it", File(target, it)) }
        }
    }

    /** Verifies the JNI layer with a one-rule engine, independent of the snapshot. */
    private fun jniSmokeTest() {
        try {
            AdblockEngine.fromRules("||doubleclick.net^", json).use { e ->
                val hit = e.check("https://ad.doubleclick.net/x.js", "https://m.youtube.com/", "script")
                val miss = e.check("https://m.youtube.com/", "https://m.youtube.com/", "document")
                Log.i(TAG, "JNI smoke test (adblock-rust ${AdblockEngine.nativeVersion}): " +
                    "doubleclick=${hit?.block} m.youtube=${miss?.block ?: false}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "JNI smoke test failed", e)
        }
    }

    /** Sanity check that the engine blocks an obvious ad request and lets YouTube itself through. */
    private fun selfTest(engine: AdblockEngine): Boolean {
        val ad = engine.check("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "https://m.youtube.com/", "script")
        val page = engine.check("https://m.youtube.com/", "https://m.youtube.com/", "document")
        if (debug) Log.d(TAG, "self-test: doubleclick block=${ad?.block} redirect=${ad?.redirect != null}, m.youtube.com block=${page?.block ?: false}")
        return ad?.block == true && page?.block != true
    }

    companion object {
        private const val TAG = "TT.Adblock"
        private const val ASSET_DIR = "adblock"
    }
}
