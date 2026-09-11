package io.github.sennheiser1986.gpstrack.map

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/** Enqueues and observes the background jobs that download offline regions. */
object OfflineMapRepository {

    /** Tag shared by every region download, for observing them all at once. */
    private const val TAG = "offline-map"

    /** Tag prefix carrying the region path, so a WorkInfo can be matched back to its region. */
    private const val REGION_TAG_PREFIX = "region:"

    /**
     * Starts downloading one region if it is not already running.
     *
     * @param context any context.
     * @param regionPath server-relative path, e.g. "europe/belgium.map".
     */
    fun start(context: Context, regionPath: String) {
        val request = OneTimeWorkRequestBuilder<OfflineMapDownloadWorker>()
            .setInputData(
                Data.Builder().putString(OfflineMapDownloadWorker.KEY_REGION_PATH, regionPath).build(),
            )
            .addTag(TAG)
            .addTag(REGION_TAG_PREFIX + regionPath)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueName(regionPath), ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Cancels one region's download and removes its partial file.
     *
     * @param context any context.
     * @param regionPath the region being downloaded.
     */
    fun cancel(context: Context, regionPath: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(regionPath))
        OfflineMap.partFileForRegion(context, regionPath).delete()
    }

    /**
     * Streams the state of every region download.
     *
     * @param context any context.
     * @return a flow of all offline-map [WorkInfo]s.
     */
    fun workInfos(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG)

    /**
     * Reads the region path back out of a download's tags.
     *
     * @param info one offline-map [WorkInfo].
     * @return the region path, or null when the tag is missing.
     */
    fun regionPathOf(info: WorkInfo): String? =
        info.tags.firstOrNull { it.startsWith(REGION_TAG_PREFIX) }?.removePrefix(REGION_TAG_PREFIX)

    /**
     * The unique work name for a region.
     *
     * @param regionPath the region.
     * @return a stable per-region name.
     */
    private fun uniqueName(regionPath: String) = "offline-map-$regionPath"
}
