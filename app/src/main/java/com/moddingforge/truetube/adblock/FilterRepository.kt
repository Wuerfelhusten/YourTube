package com.moddingforge.truetube.adblock

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    data class Ready(
        val engine: AdblockEngine,
        val lists: Int,
        val loadMs: Long,
        val fromCache: Boolean,
        val selfTestOk: Boolean,
        /** When a downloaded list last changed (0 = only the bundled snapshot). */
        val listsUpdatedAt: Long,
    ) : EngineState

    data class Unavailable(val reason: String) : EngineState
}

/**
 * Owns Brave's adblock engine:
 *  - unpacks the bundled snapshot (assets/adblock) into app storage,
 *  - builds the engine from snapshot lists, overridden by newer downloaded lists ([ListUpdater]),
 *  - caches the built engine (Engine::serialize) for fast cold starts,
 *  - swaps in a rebuilt engine after list updates and closes the old one.
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

    private val root get() = File(context.filesDir, "adblock")
    private val snapshotDir get() = File(root, "snapshot")
    private val cacheFile get() = File(root, "engine.cache")

    val updater = ListUpdater(json, File(root, "updates"), File(root, "updates.json"))

    private val buildMutex = Mutex()

    fun load() {
        scope.launch(Dispatchers.IO) {
            if (debug) jniSmokeTest()
            buildMutex.withLock { publish(safeBuild(preferCache = true)) }
        }
    }

    /**
     * Downloads newer filter lists and, if anything changed (or [forceRebuild]), rebuilds the
     * engine and swaps it in. Safe to call from a worker.
     */
    suspend fun updateLists(forceRebuild: Boolean = false): UpdateResult {
        state.first { it !is EngineState.Loading }
        val manifest = unpackSnapshot()
        val current = effectiveLists(manifest).associate { it.url to File(it.path) }
        val result = updater.update(current)
        Log.i(TAG, "List update: $result")
        if (result.changed > 0 || forceRebuild) {
            buildMutex.withLock { publish(safeBuild(preferCache = false)) }
        }
        return result
    }

    private fun publish(new: EngineState) {
        val old = (_state.value as? EngineState.Ready)?.engine
        _state.value = new
        // Calls already running on the old engine finish first (AdblockEngine's write lock).
        if (old != null && old !== (new as? EngineState.Ready)?.engine) old.close()
    }

    private suspend fun safeBuild(preferCache: Boolean): EngineState = try {
        build(preferCache)
    } catch (e: Throwable) {
        Log.e(TAG, "Adblock engine unavailable", e)
        // Keep a working engine if we already have one.
        _state.value.takeIf { it is EngineState.Ready } ?: EngineState.Unavailable(e.message ?: e.javaClass.simpleName)
    }

    private data class EffectiveList(val url: String, val path: String, val mask: Int)

    /** Enabled lists, each pointing at the downloaded copy if there is one, else the snapshot copy. */
    private fun effectiveLists(manifest: SnapshotManifest): List<EffectiveList> =
        manifest.components.filter { it.defaultEnabled }
            .flatMap { comp ->
                comp.lists.map { ref ->
                    val file = updater.fileFor(ref.url) ?: File(snapshotDir, ref.file)
                    EffectiveList(ref.url, file.path, comp.permissionMask)
                }
            }
            .distinctBy { it.url }

    private suspend fun build(preferCache: Boolean): EngineState = withContext(Dispatchers.IO) {
        val manifest = unpackSnapshot()
        val lists = effectiveLists(manifest)
        val resources = File(snapshotDir, manifest.resources)
        val key = cacheKey(lists, resources)
        val start = SystemClock.elapsedRealtime()

        val cached = if (preferCache) readCache(key)?.let { bytes ->
            runCatching { AdblockEngine.deserialize(bytes, resources.path, json) }
                .onFailure { Log.w(TAG, "Engine cache unusable, rebuilding", it) }
                .getOrNull()
        } else null

        val engine = cached ?: AdblockEngine.fromFiles(lists.map { it.path to it.mask }, resources.path, json, debug = false)
        val loadMs = SystemClock.elapsedRealtime() - start
        if (cached == null) scope.launch(Dispatchers.IO) { writeCache(key, engine) }

        val selfTest = selfTest(engine)
        val updatedAt = updater.meta.lastChange
        Log.i(TAG, "Engine ready (adblock-rust ${AdblockEngine.nativeVersion}): ${lists.size} lists " +
            "(${lists.count { updater.fileFor(it.url) != null }} updated), ${if (cached != null) "from cache" else "built"} " +
            "in ${loadMs}ms, self-test ${if (selfTest) "OK" else "FAILED"}")
        EngineState.Ready(engine, lists.size, loadMs, cached != null, selfTest, updatedAt)
    }

    // --- engine cache -------------------------------------------------------------------------

    /** Changes whenever the engine version, any list file or the resources change. */
    private fun cacheKey(lists: List<EffectiveList>, resources: File): String {
        val parts = buildList {
            add("adblock-rust=${AdblockEngine.nativeVersion}")
            lists.forEach { val f = File(it.path); add("${it.path}|${f.length()}|${f.lastModified()}|${it.mask}") }
            add("${resources.path}|${resources.length()}|${resources.lastModified()}")
        }
        return ListUpdater.sha1(parts.joinToString("\n"))
    }

    private fun readCache(key: String): ByteArray? {
        val f = cacheFile.takeIf { it.isFile } ?: return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { input ->
                if (input.readUTF() != key) return null
                ByteArray(input.readInt()).also { input.readFully(it) }
            }
        }.getOrNull()
    }

    private fun writeCache(key: String, engine: AdblockEngine) {
        val start = SystemClock.elapsedRealtime()
        val bytes = engine.serialize() ?: return
        val tmp = File(root, "engine.cache.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeUTF(key)
            out.writeInt(bytes.size)
            out.write(bytes)
        }
        if (!tmp.renameTo(cacheFile)) { cacheFile.delete(); tmp.renameTo(cacheFile) }
        Log.i(TAG, "Engine cache written: ${bytes.size / 1024} KB in ${SystemClock.elapsedRealtime() - start}ms")
    }

    // --- snapshot -----------------------------------------------------------------------------

    /**
     * Copies assets/adblock into filesDir (Rust reads plain files). Re-copies only when the
     * bundled snapshot changed; a newer snapshot also discards older list downloads and the cache.
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
        if (current != null) {
            updater.clear()
            cacheFile.delete()
        }
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

    // --- checks -------------------------------------------------------------------------------

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
