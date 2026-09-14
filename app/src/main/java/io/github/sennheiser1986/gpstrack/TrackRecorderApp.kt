package io.github.sennheiser1986.gpstrack

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.getSystemService
import io.github.sennheiser1986.gpstrack.data.TrackRepository
import org.osmdroid.config.Configuration

/**
 * Application entry point. It configures osmdroid before any map is shown, registers the two
 * foreground-service notification channels, and exposes the shared [TrackRepository].
 */
class TrackRecorderApp : Application() {

    /** The process-wide tracks repository, backed by the Room database. */
    val repository: TrackRepository by lazy { TrackRepository.get(this) }

    /**
     * Sets up osmdroid's cache and user agent and creates the notification channels.
     */
    override fun onCreate() {
        super.onCreate()

        // osmdroid needs a descriptive user agent and a writable cache directory before the
        // first tile request, otherwise the OpenStreetMap tile servers reject the traffic.
        Configuration.getInstance().apply {
            userAgentValue = "GPSTrack/1.0 (+https://github.com/sennheiser1986/gpstrack)"
            osmdroidBasePath = cacheDir
            osmdroidTileCache = cacheDir.resolve("osmdroid-tiles")
        }

        createChannels()
    }

    /**
     * Creates the notification channels used by the recording and sharing services.
     */
    private fun createChannels() {
        val manager = getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                RECORDING_CHANNEL_ID,
                getString(R.string.recording_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.recording_channel_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                SHARING_CHANNEL_ID,
                getString(R.string.sharing_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.sharing_channel_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                OFFLINE_MAP_CHANNEL_ID,
                getString(R.string.offline_map_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.offline_map_channel_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                NEARBY_CHANNEL_ID,
                getString(R.string.nearby_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = getString(R.string.nearby_channel_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                FOLLOW_REQUEST_CHANNEL_ID,
                getString(R.string.follow_request_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = getString(R.string.follow_request_channel_description) },
        )
    }

    companion object {
        /** Channel for the "recording your track" ongoing notification. */
        const val RECORDING_CHANNEL_ID = "track_recording"

        /** Channel for the "sharing your location" ongoing notification. */
        const val SHARING_CHANNEL_ID = "location_sharing"

        /** Channel for the "downloading offline map" progress notification. */
        const val OFFLINE_MAP_CHANNEL_ID = "offline_map_download"

        /** Channel for the heads-up "a web user wants to follow you" alert. */
        const val FOLLOW_REQUEST_CHANNEL_ID = "follow_requests"

        /** Channel for the "someone you follow is nearby" alert. */
        const val NEARBY_CHANNEL_ID = "nearby_peers"
    }
}
