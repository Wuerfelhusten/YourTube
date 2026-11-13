package com.moddingforge.yourtube.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import android.webkit.WebView
import com.moddingforge.yourtube.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local crash log. Nothing is sent anywhere: the user shares it by hand (Settings -> About).
 *
 * Sources:
 *  - uncaught Java/Kotlin exceptions (written right before the process dies)
 *  - on the next start, Android's exit reasons (API 30+): native crashes (adblock engine, WebView),
 *    ANRs (app froze), kills for low memory / excessive resource use - none of these reach a handler
 *  - WebView renderer crashes / kills (the app survives those, but they explain "slow, then reloaded")
 */
object CrashLog {
    private const val TAG = "TT.CrashLog"
    private const val FILE = "crash-log.txt"
    private const val MAX_BYTES = 96 * 1024
    private const val PREFS = "crash_log"
    private const val KEY_LAST_EXIT = "last_exit_ts"

    private lateinit var app: Context
    private val file get() = File(app.filesDir, FILE)

    fun install(context: Context) {
        app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { append("CRASH in thread \"${thread.name}\"", stackTrace(e)) }
            previous?.uncaughtException(thread, e) ?: run {
                Process.killProcess(Process.myPid())
                System.exit(10)
            }
        }
        runCatching { importExitReasons() }.onFailure { Log.w(TAG, "exit reasons: ${it.message}") }
    }

    /** A WebView renderer died; the app recreates it. */
    fun rendererGone(role: String, crashed: Boolean, priorityAtExit: Int) {
        val why = if (crashed) "crashed" else "killed by the system (low memory), priority $priorityAtExit"
        append("WebView renderer ($role) $why", null)
    }

    fun read(): String? = file.takeIf { it.exists() && it.length() > 0 }?.readText()

    fun entryCount(): Int = read()?.lineSequence()?.count { it.startsWith("=== ") } ?: 0

    fun clear() {
        file.delete()
    }

    /** What the user shares: device info + the log. */
    fun report(): String = buildString {
        append("YourTube ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        val wv = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        append("WebView: ").append(wv?.let { "${it.packageName} ${it.versionName}" } ?: "?").append('\n')
        val am = app.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        append("RAM: ").append(mem.totalMem / (1024 * 1024)).append(" MB, low-RAM device: ").append(am?.isLowRamDevice).append("\n\n")
        append(read() ?: "(no entries)\n")
    }

    /**
     * The report as a file for sharing (cache/reports, served by the FileProvider in the manifest),
     * e.g. yourtube-crash-0.2.7-20261113-1552.txt.
     */
    fun reportFile(): File {
        val dir = File(app.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the newest one
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return File(dir, "yourtube-crash-${BuildConfig.VERSION_NAME}-$stamp.txt").apply { writeText(report()) }
    }

    @Synchronized
    private fun append(title: String, body: String?) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = buildString {
            append("=== ").append(stamp).append(" · ").append(BuildConfig.VERSION_NAME).append(" · ").append(title).append('\n')
            if (!body.isNullOrBlank()) append(body.trimEnd()).append('\n')
            append('\n')
        }
        Log.w(TAG, entry)
        val old = if (file.exists()) file.readText() else ""
        var text = old + entry
        // Keep the newest entries: cut whole entries from the front.
        while (text.length > MAX_BYTES) {
            val next = text.indexOf("\n=== ", 1)
            text = if (next > 0) text.substring(next + 1) else text.takeLast(MAX_BYTES)
        }
        file.writeText(text)
    }

    private fun importExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_EXIT, 0L)
        val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, 10)
            .filter { it.timestamp > last && it.processName == app.packageName }
            .sortedBy { it.timestamp }
        for (e in exits) {
            val label = when (e.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH"
                ApplicationExitInfo.REASON_ANR -> "APP NOT RESPONDING (froze)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "KILLED: low memory"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "KILLED: excessive resource use"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION FAILURE"
                // REASON_CRASH is already logged by the handler; user swipes / updates are normal.
                else -> null
            } ?: continue
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(e.timestamp))
            val body = buildString {
                append("at ").append(time).append(", ").append(e.description ?: "").append('\n')
                append("memory: pss ").append(e.pss / 1024).append(" MB, rss ").append(e.rss / 1024).append(" MB, importance ").append(e.importance).append('\n')
                // ANR: the thread dump - the main thread is the interesting part, keep it short.
                if (e.reason == ApplicationExitInfo.REASON_ANR) {
                    runCatching {
                        e.traceInputStream?.bufferedReader()?.useLines { lines -> lines.take(120).forEach { append(it).append('\n') } }
                    }
                }
            }
            append(label, body)
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong(KEY_LAST_EXIT, it).apply() }
    }

    private fun stackTrace(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
}
