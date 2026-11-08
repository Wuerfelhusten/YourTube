package com.moddingforge.yourtube.adblock

/**
 * Raw JNI surface of libyourtube_adblock.so (adblock-native/src/lib.rs).
 * Use [AdblockEngine] instead of calling this directly. Errors arrive as RuntimeException.
 */
internal object NativeAdblock {
    init {
        System.loadLibrary("yourtube_adblock")
    }

    @JvmStatic external fun nativeCreate(listPaths: Array<String>, permissionMasks: IntArray, resourcesPath: String?, debug: Boolean): Long
    @JvmStatic external fun nativeCreateFromRules(rules: String): Long
    @JvmStatic external fun nativeDestroy(handle: Long)

    /** null = no match (allow). Otherwise JSON, see [CheckResult]. */
    @JvmStatic external fun nativeCheck(handle: Long, url: String, sourceUrl: String, requestType: String): String?

    /** JSON, see [CosmeticResources]. */
    @JvmStatic external fun nativeCosmetic(handle: Long, url: String): String?

    /** JSON array of selectors. */
    @JvmStatic external fun nativeHiddenClassIds(handle: Long, classes: Array<String>, ids: Array<String>, exceptionsJson: String): String?

    @JvmStatic external fun nativeSerialize(handle: Long): ByteArray?
    @JvmStatic external fun nativeDeserialize(data: ByteArray, resourcesPath: String?): Long
    @JvmStatic external fun nativeVersion(): String
}
