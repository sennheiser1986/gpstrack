package io.github.sennheiser1986.gpstrack.record

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleService
import io.github.sennheiser1986.gpstrack.MainActivity
import io.github.sennheiser1986.gpstrack.R
import io.github.sennheiser1986.gpstrack.TrackRecorderApp
import io.github.sennheiser1986.gpstrack.data.TrackRepository
import kotlinx.coroutines.launch

/**
 * Foreground service that owns a recording session: it listens for GPS fixes, writes each one
 * into the [TrackRepository] as a [io.github.sennheiser1986.gpstrack.data.TrackPoint], and mirrors progress
 * into [RecordingState] for the interface. It keeps running while the app is backgrounded so a
 * walk or ride is captured end to end.
 */
class LocationRecordingService : LifecycleService(), LocationListener {

    private lateinit var repository: TrackRepository
    private lateinit var locationManager: LocationManager
    private var trackId: Long = -1L

    /**
     * Caches the repository and location manager handles.
     */
    override fun onCreate() {
        super.onCreate()
        repository = TrackRepository.get(this)
        locationManager = getSystemService()!!
    }

    /**
     * Handles the start and stop intents.
     *
     * @param intent the command intent; [ACTION_START] carries [EXTRA_TRACK_NAME].
     * @param flags framework restart flags.
     * @param startId framework start id.
     * @return [START_STICKY] so Android restarts the service if it is killed mid-recording.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startRecording(
                intent.getStringExtra(EXTRA_TRACK_NAME) ?: defaultTrackName(),
            )
            ACTION_STOP -> stopRecording()
            // Null intent means the system restarted a sticky service; keep an in-progress
            // recording alive, otherwise there is nothing to do.
            else -> if (trackId == -1L) stopSelf()
        }
        return START_STICKY
    }

    /**
     * Creates the track, goes to the foreground, and subscribes to location updates.
     *
     * @param name reader-visible name for the new track.
     */
    private fun startRecording(name: String) {
        if (trackId != -1L) return
        // A location-typed foreground service may only enter the foreground while location
        // permission is held (enforced from Android 14). Callers are expected to have requested
        // it already; if it is somehow missing, stop before going foreground.
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }
        startForeground(NOTIFICATION_ID, buildNotification())

        val startedAt = System.currentTimeMillis()
        lifecycleScope.launch {
            trackId = repository.startTrack(name, startedAt)
            RecordingState.beginRecording(trackId, startedAt)
        }

        requestUpdates(LocationManager.GPS_PROVIDER)
        requestUpdates(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * Subscribes to one provider, ignoring providers the device does not have.
     *
     * @param provider a [LocationManager] provider constant.
     */
    private fun requestUpdates(provider: String) {
        if (!hasLocationPermission()) return
        runCatching {
            locationManager.requestLocationUpdates(
                provider,
                MIN_UPDATE_INTERVAL_MILLIS,
                MIN_UPDATE_DISTANCE_METERS,
                this,
                Looper.getMainLooper(),
            )
        }
    }

    /**
     * Stores a fix and forwards it to [RecordingState].
     *
     * @param location the new fix.
     */
    override fun onLocationChanged(location: Location) {
        val currentTrackId = trackId
        if (currentTrackId == -1L) return
        lifecycleScope.launch {
            val stored = repository.appendFix(currentTrackId, location)
            RecordingState.appendRecordedPoint(stored)
        }
    }

    /** Ignored; required by the [LocationListener] interface on older API levels. */
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    /**
     * Closes the track, drops the location subscription and leaves the foreground.
     */
    private fun stopRecording() {
        val currentTrackId = trackId
        trackId = -1L
        runCatching { locationManager.removeUpdates(this) }
        lifecycleScope.launch {
            if (currentTrackId != -1L) {
                repository.finishTrack(currentTrackId, System.currentTimeMillis())
            }
            RecordingState.endRecording()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Removes any remaining location subscription if the service is torn down without a stop
     * intent.
     */
    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(this) }
        super.onDestroy()
    }

    /**
     * Builds the ongoing notification that keeps the service in the foreground.
     *
     * @return the notification.
     */
    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, TrackRecorderApp.RECORDING_CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_channel_name))
            .setContentText("Recording your track…")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    /**
     * Reports whether at least coarse location permission is held.
     *
     * @return true when the service may listen for fixes.
     */
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val ACTION_START = "io.github.sennheiser1986.gpstrack.record.START"
        private const val ACTION_STOP = "io.github.sennheiser1986.gpstrack.record.STOP"
        private const val EXTRA_TRACK_NAME = "track_name"
        private const val NOTIFICATION_ID = 4201

        /** Fastest cadence at which fixes are delivered. */
        private const val MIN_UPDATE_INTERVAL_MILLIS = 3_000L

        /** Minimum move between delivered fixes, so a stationary device stops adding points. */
        private const val MIN_UPDATE_DISTANCE_METERS = 4f

        /**
         * Starts recording a new track.
         *
         * @param context any context.
         * @param trackName reader-visible name, or null to name it after the current time.
         */
        fun start(context: Context, trackName: String?) {
            val intent = Intent(context, LocationRecordingService::class.java).apply {
                action = ACTION_START
                if (trackName != null) putExtra(EXTRA_TRACK_NAME, trackName)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Stops the current recording, if any.
         *
         * @param context any context.
         */
        fun stop(context: Context) {
            val intent = Intent(context, LocationRecordingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * A default track name based on the current local date and time.
         *
         * @return a name such as "Track 2024-05-18 07:15".
         */
        fun defaultTrackName(): String {
            val now = java.time.LocalDateTime.now()
            return "Track %04d-%02d-%02d %02d:%02d".format(
                now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute,
            )
        }
    }
}
