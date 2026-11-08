package com.moddingforge.yourtube.update

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.moddingforge.yourtube.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "TT.Update"

/** A newer YourTube release on GitHub. */
data class Release(val version: String, val tag: String, val notes: String, val apkUrl: String, val apkName: String, val size: Long)

sealed interface UpdateState {
    /** Nothing known (not checked yet, no repo configured, or up to date). */
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    /** [progress] 0..1, or -1 when the size is unknown. */
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** APK handed to the system installer; the confirmation dialog is showing. */
    data class Installing(val release: Release) : UpdateState
    data class Failed(val release: Release?, val message: String) : UpdateState
}

/**
 * In-app updater for the privately distributed APK:
 *  1. checks the latest GitHub release on start (`releases/latest`, newest non-prerelease),
 *  2. exposes [state] so the shell can show an "Update" button,
 *  3. downloads the APK itself (no browser) into the app cache with progress,
 *  4. hands it to the system installer through a [PackageInstaller] session.
 * Android always asks the user to confirm the install (and once to allow YourTube to install apps);
 * the release APK must be signed with the same key, otherwise Android refuses the update.
 */
class Updater(
    private val app: Application,
    private val json: Json,
    private val scope: CoroutineScope,
    private val settings: com.moddingforge.yourtube.data.SettingsRepository,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** owner/name of the GitHub repository (gradle property `yourtube.updateRepo`); debug builds can override it. */
    var repo: String = BuildConfig.UPDATE_REPO
    val enabled get() = repo.isNotBlank()

    private var checkedThisRun = false
    private var job: Job? = null

    /** Once per process start. */
    fun checkOnStart() {
        if (checkedThisRun) return
        checkedThisRun = true
        check()
    }

    fun check() {
        if (!enabled || job?.isActive == true) return
        _state.value = UpdateState.Checking
        job = scope.launch(Dispatchers.IO) {
            _state.value = runCatching { fetchLatest() }
                .onFailure { Log.w(TAG, "check failed: ${it.message}") }
                .fold(
                    onSuccess = { r -> if (r != null && isNewer(r.version, currentVersion())) UpdateState.Available(r) else UpdateState.UpToDate },
                    onFailure = { UpdateState.Idle }, // offline etc.: stay quiet, try again on next start
                )
            Log.i(TAG, "check $repo: ${_state.value}")
        }
    }

    /** Downloads the APK of [release] and starts the installation when done. */
    fun download(release: Release) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val file = downloadApk(release) { !isActive }
                if (!isActive) return@launch
                install(release, file)
            } catch (e: Exception) {
                Log.w(TAG, "update failed", e)
                _state.value = UpdateState.Failed(release, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        val s = _state.value
        if (s is UpdateState.Downloading) _state.value = UpdateState.Available(s.release)
    }

    /** Back to "available" after the user cancelled the system dialog or an error was shown. */
    fun reset() {
        when (val s = _state.value) {
            is UpdateState.Installing -> _state.value = UpdateState.Available(s.release)
            is UpdateState.Failed -> _state.value = s.release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            else -> Unit
        }
    }

    /** Debug builds only: install an APK already placed in cache/updates (tests the installer path without GitHub). */
    fun debugInstall(fileName: String) {
        if (!BuildConfig.DEBUG) return
        val file = File(File(app.cacheDir, "updates"), fileName)
        val release = Release("debug", "debug", "", file.toURI().toString(), file.name, file.length())
        job = scope.launch(Dispatchers.IO) {
            runCatching { install(release, file) }.onFailure { _state.value = UpdateState.Failed(release, it.message ?: "install failed") }
        }
    }


    // --- "What's new" after an update ---------------------------------------------------------

    private val _whatsNew = MutableStateFlow<List<ChangelogEntry>?>(null)
    /** Changelog entries to show once after an update; null = nothing to show. */
    val whatsNew: StateFlow<List<ChangelogEntry>?> = _whatsNew.asStateFlow()

    /**
     * On start: after an update (not a fresh install) show every changelog entry newer than the
     * version the user last saw. A fresh install just remembers the current version.
     */
    fun checkWhatsNew() {
        val current = BuildConfig.VERSION_CODE
        val seen = settings.current.lastSeenVersion
        if (seen >= current) return
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val freshInstall = info != null && info.firstInstallTime == info.lastUpdateTime
        if (freshInstall) return markWhatsNewSeen()
        val all = Changelog.load(app)
        // seen == 0: updated from a build without this feature - show the current version only.
        val entries = if (seen == 0) all.filter { it.code == current } else Changelog.between(all, seen, current)
        if (entries.isEmpty()) markWhatsNewSeen() else _whatsNew.value = entries
    }

    fun markWhatsNewSeen() {
        _whatsNew.value = null
        settings.update { it.copy(lastSeenVersion = BuildConfig.VERSION_CODE) }
    }

    /** Debug builds: pretend [version] is available (tests the banner without GitHub). */
    fun debugFakeUpdate(version: String) {
        if (!BuildConfig.DEBUG) return
        val notes = Changelog.load(app).firstOrNull()?.text.orEmpty()
        _state.value = UpdateState.Available(Release(version, "v$version", notes, "", "YourTube-$version.apk", 0))
    }

    /** Debug builds: show the popup for the current version right away. */
    fun debugShowWhatsNew() {
        if (BuildConfig.DEBUG) _whatsNew.value = Changelog.load(app).filter { it.code == BuildConfig.VERSION_CODE }
    }

    // --- GitHub -------------------------------------------------------------------------------

    @Serializable
    private data class GhAsset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tag: String,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GhAsset> = emptyList(),
    )

    private fun fetchLatest(): Release? {
        val conn = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "YourTube-Updater")
        }
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null // no release yet
            check(conn.responseCode == HttpURLConnection.HTTP_OK) { "GitHub HTTP ${conn.responseCode}" }
            val gh = json.decodeFromString<GhRelease>(conn.inputStream.bufferedReader().use { it.readText() })
            if (gh.draft || gh.prerelease) return null
            val apk = pickApk(gh.assets) ?: return null
            return Release(gh.tag.removePrefix("v").removePrefix("V"), gh.tag, gh.body.orEmpty().trim(), apk.url, apk.name, apk.size)
        } finally {
            conn.disconnect()
        }
    }

    /** The release APK; with several APKs prefer one for this device's ABI or a universal one, never a debug build. */
    private fun pickApk(assets: List<GhAsset>): GhAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && !it.name.contains("debug", ignoreCase = true) }
        if (apks.size <= 1) return apks.firstOrNull()
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return apks.firstOrNull { abi.isNotEmpty() && it.name.contains(abi, ignoreCase = true) }
            ?: apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: apks.first()
    }

    // --- download ---------------------------------------------------------------------------------

    private fun downloadApk(release: Release, cancelled: () -> Boolean): File {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // older downloads
        val target = File(dir, "YourTube-${release.version}.apk")
        val part = File(dir, target.name + ".part")
        _state.value = UpdateState.Downloading(release, 0f)

        // GitHub answers with a redirect to its asset CDN (https -> https, followed automatically).
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "YourTube-Updater")
        }
        try {
            check(conn.responseCode == HttpURLConnection.HTTP_OK) { "Download HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            var done = 0L
            var lastEmit = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) error("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastEmit > 256 * 1024 || done == total) {
                            lastEmit = done
                            _state.value = UpdateState.Downloading(release, if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                        }
                    }
                }
            }
            check(total <= 0 || done == total) { "Download incomplete ($done of $total bytes)" }
        } finally {
            conn.disconnect()
        }
        check(part.renameTo(target)) { "Could not store the download" }
        Log.i(TAG, "downloaded ${target.length()} bytes -> $target")
        return target
    }

    // --- install ------------------------------------------------------------------------------

    private fun install(release: Release, apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("YourTube.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(app, InstallResultReceiver::class.java).setPackage(app.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(app, sessionId, intent, flags)
            _state.value = UpdateState.Installing(release)
            session.commit(pending.intentSender)
        }
        Log.i(TAG, "install session $sessionId committed for ${release.version}")
    }

    internal fun onInstallResult(status: Int, message: String?) {
        val release = (state.value as? UpdateState.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit // the app is replaced and restarted by the system
            PackageInstaller.STATUS_FAILURE_ABORTED -> reset() // user cancelled
            else -> _state.value = UpdateState.Failed(release, message ?: "Install failed ($status)")
        }
    }

    companion object {
        fun currentVersion(): String = BuildConfig.VERSION_NAME.substringBefore('-')

        /** Numeric comparison of dotted versions ("0.10.0" > "0.9.3"); suffixes like "-beta" are ignored. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.trim().toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

/** Result of the PackageInstaller session; shows the system confirmation when user action is needed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "install status $status $message")
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        (context.applicationContext as? com.moddingforge.yourtube.YourTubeApp)?.container?.updater?.onInstallResult(status, message)
    }
}
