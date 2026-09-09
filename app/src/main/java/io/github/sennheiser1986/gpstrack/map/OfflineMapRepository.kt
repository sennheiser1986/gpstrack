package io.github.sennheiser1986.gpstrack.map

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/** Enqueues and observes the one background job that downloads the offline map. */
object OfflineMapRepository {

    private const val WORK_NAME = "offline-map-download"

    /**
     * Starts the download if it is not already running or complete.
     *
     * @param context any context.
     */
    fun start(context: Context) {
        val request = OneTimeWorkRequestBuilder<OfflineMapDownloadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Cancels an in-progress download and removes the partial file.
     *
     * @param context any context.
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        OfflineMap.partFile(context).delete()
    }

    /**
     * Streams the state of the download job.
     *
     * @param context any context.
     * @return a flow of the job's [WorkInfo]s (usually zero or one).
     */
    fun workInfo(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME)
}
