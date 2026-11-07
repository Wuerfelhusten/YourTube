package com.moddingforge.truetube.adblock

import java.io.Closeable
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Result of a network check that matched something. */
@Serializable
data class CheckResult(
    val block: Boolean,
    val important: Boolean = false,
    /** data: URL with the replacement body (e.g. noop.js), if a redirect rule matched. */
    val redirect: String? = null,
    @SerialName("rewritten_url") val rewrittenUrl: String? = null,
)

/** Cosmetic filtering data for one page URL (adblock-rust `UrlSpecificResources`). */
@Serializable
data class CosmeticResources(
    @SerialName("hide_selectors") val hideSelectors: Set<String> = emptySet(),
    @SerialName("procedural_actions") val proceduralActions: Set<String> = emptySet(),
    val exceptions: Set<String> = emptySet(),
    @SerialName("injected_script") val injectedScript: String = "",
    val generichide: Boolean = false,
)

/**
 * Thread-safe wrapper around one native engine. Checks may run concurrently (adblock-rust's
 * Engine is Send + Sync in our build); [close] waits for running calls to finish.
 */
class AdblockEngine private constructor(
    private var handle: Long,
    private val json: Json,
) : Closeable {

    private val lock = ReentrantReadWriteLock()

    val isOpen: Boolean get() = lock.read { handle != 0L }

    /**
     * @param requestType adblock-rust request type: document, sub_frame, script, stylesheet,
     *   image, media, font, xhr, websocket, ping, other
     * @return null if the request may load unchanged.
     */
    fun check(url: String, sourceUrl: String, requestType: String): CheckResult? = lock.read {
        if (handle == 0L) return null
        val raw = NativeAdblock.nativeCheck(handle, url, sourceUrl, requestType) ?: return null
        json.decodeFromString(CheckResult.serializer(), raw)
    }

    fun cosmetic(url: String): CosmeticResources = lock.read {
        if (handle == 0L) return CosmeticResources()
        val raw = NativeAdblock.nativeCosmetic(handle, url) ?: return CosmeticResources()
        json.decodeFromString(CosmeticResources.serializer(), raw)
    }

    fun hiddenClassIdSelectors(classes: Collection<String>, ids: Collection<String>, exceptions: Set<String>): List<String> = lock.read {
        if (handle == 0L) return emptyList()
        val exc = json.encodeToString(SetSerializer(String.serializer()), exceptions)
        val raw = NativeAdblock.nativeHiddenClassIds(handle, classes.toTypedArray(), ids.toTypedArray(), exc) ?: return emptyList()
        json.decodeFromString(ListSerializer(String.serializer()), raw)
    }

    fun serialize(): ByteArray? = lock.read {
        if (handle == 0L) null else NativeAdblock.nativeSerialize(handle)
    }

    override fun close() = lock.write {
        if (handle != 0L) {
            NativeAdblock.nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        val nativeVersion: String get() = NativeAdblock.nativeVersion()

        fun fromFiles(lists: List<Pair<String, Int>>, resourcesPath: String?, json: Json, debug: Boolean = false): AdblockEngine {
            val handle = NativeAdblock.nativeCreate(
                lists.map { it.first }.toTypedArray(),
                lists.map { it.second }.toIntArray(),
                resourcesPath,
                debug,
            )
            check(handle != 0L) { "nativeCreate returned null handle" }
            return AdblockEngine(handle, json)
        }

        fun fromRules(rules: String, json: Json): AdblockEngine {
            val handle = NativeAdblock.nativeCreateFromRules(rules)
            check(handle != 0L) { "nativeCreateFromRules returned null handle" }
            return AdblockEngine(handle, json)
        }

        fun deserialize(data: ByteArray, resourcesPath: String?, json: Json): AdblockEngine {
            val handle = NativeAdblock.nativeDeserialize(data, resourcesPath)
            check(handle != 0L) { "nativeDeserialize returned null handle" }
            return AdblockEngine(handle, json)
        }
    }
}
