package com.moddingforge.yourtube.notify

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.moddingforge.yourtube.YourTubeApp
import com.moddingforge.yourtube.data.AppSettings
import java.util.concurrent.TimeUnit

/** Periodic check for new videos / inbox entries (interval and network from the settings). */
class NotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val notifier = (applicationContext as YourTubeApp).container.notifier
        return try {
            notifier.check(force = inputData.getBoolean(KEY_FORCE, false))
            Result.success()
        } catch (e: YouTubeApi.NotSignedIn) {
            Result.success() // nothing to do until the user signs in
        } catch (e: Exception) {
            Log.w(TAG, "check failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "TT.Notify"
        private const val PERIODIC = "notify-check"
        private const val ONE_SHOT = "notify-check-now"
        private const val KEY_FORCE = "force"

        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Schedules (or cancels) the periodic check to match the settings. */
        fun schedule(context: Context, s: AppSettings) {
            val wm = WorkManager.getInstance(context)
            if (!s.notifyVideos && !s.notifyInbox) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val minutes = s.notifyInterval.toLong().coerceAtLeast(15)
            val request = PeriodicWorkRequestBuilder<NotifyWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(constraints(s.notifyWifiOnly))
                .setInitialDelay(minutes, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** "Check now" in the settings and the debug intent. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<NotifyWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(androidx.work.workDataOf(KEY_FORCE to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
