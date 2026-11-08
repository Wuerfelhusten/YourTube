package com.moddingforge.yourtube.adblock

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
import java.util.concurrent.TimeUnit

/** Daily filter list update in the background (Brave updates its components similarly). */
class AdblockUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = (applicationContext as YourTubeApp).container.filterRepository
        return try {
            val r = repo.updateLists()
            // Everything failed (e.g. captive portal): try again later with backoff.
            if (r.checked > 0 && r.failed == r.checked) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "List update failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "TT.Adblock"
        private const val PERIODIC = "adblock-list-update"
        private const val ONE_SHOT = "adblock-list-update-now"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Idempotent; call on every app start. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AdblockUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Manual "update now" (settings button, debug intent). */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AdblockUpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
