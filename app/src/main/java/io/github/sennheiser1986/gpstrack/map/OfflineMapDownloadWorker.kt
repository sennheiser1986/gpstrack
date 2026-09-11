package io.github.sennheiser1986.gpstrack.map

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.sennheiser1986.gpstrack.MainActivity
import io.github.sennheiser1986.gpstrack.TrackRecorderApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads one offline region in the background, with a progress notification and resume
 * support: a partial file is kept and continued with an HTTP ``Range`` request if the download
 * is interrupted. The region's server path arrives in the input data.
 */
class OfflineMapDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val regionPath: String get() = inputData.getString(KEY_REGION_PATH).orEmpty()

    private val regionName: String
        get() = OfflineMap.displayName(regionPath.substringAfterLast('/'))

    /**
     * Streams the region file into its partial file, then swaps it into place.
     *
     * @return [Result.success] when the map is in place, [Result.retry] on a network error,
     *   [Result.failure] when cancelled or misconfigured.
     */
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (regionPath.isEmpty()) return@withContext Result.failure()
        val target = OfflineMap.fileForRegion(applicationContext, regionPath)
        val part = OfflineMap.partFileForRegion(applicationContext, regionPath)
        target.parentFile?.mkdirs()

        runCatching { setForeground(foregroundInfo(0)) }

        val alreadyHave = if (part.isFile) part.length() else 0L
        val url = "${OfflineMap.BASE_URL}/${regionPath.trim('/')}"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "GPSTrack/1.0 (Android)")
            if (alreadyHave > 0) setRequestProperty("Range", "bytes=$alreadyHave-")
        }

        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                return@withContext Result.retry()
            }
            val resuming = status == HttpURLConnection.HTTP_PARTIAL && alreadyHave > 0
            val bodyLength = connection.getHeaderFieldLong("Content-Length", -1L)
            val total = if (resuming && bodyLength > 0) alreadyHave + bodyLength else bodyLength

            connection.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { output ->
                    if (resuming) output.seek(alreadyHave) else output.setLength(0)
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = if (resuming) alreadyHave else 0L
                    var lastPercent = -1
                    while (true) {
                        if (isStopped) return@withContext Result.failure()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                setProgress(workDataOf(KEY_PERCENT to percent))
                                runCatching { setForeground(foregroundInfo(percent)) }
                            }
                        }
                    }
                }
            }

            if (part.length() <= 0L) return@withContext Result.retry()
            target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Builds the ongoing "downloading offline map" notification.
     *
     * @param percent progress 0..100, or a value outside that range for an indeterminate bar.
     * @return the [ForegroundInfo].
     */
    private fun foregroundInfo(percent: Int): ForegroundInfo {
        val open = PendingIntent.getActivity(
            applicationContext, 3,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(
            applicationContext, TrackRecorderApp.OFFLINE_MAP_CHANNEL_ID,
        )
            .setContentTitle("Downloading map: $regionName")
            .setContentText(if (percent in 0..100) "$percent%" else "Starting…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), percent !in 0..100)
            .setContentIntent(open)
            .build()
        // One notification id per region, so parallel downloads each show their own progress.
        val id = NOTIFICATION_ID_BASE + (regionPath.hashCode() and 0xFF)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    companion object {
        /** Work progress key: percent complete (Int). */
        const val KEY_PERCENT = "percent"

        /** Input data key: the region's server-relative path, e.g. "europe/belgium.map". */
        const val KEY_REGION_PATH = "region_path"

        // 4400+ keeps clear of the recording (4201), sharing (4202) and follow-request
        // (4300..) notification ids.
        private const val NOTIFICATION_ID_BASE = 4400
    }
}
