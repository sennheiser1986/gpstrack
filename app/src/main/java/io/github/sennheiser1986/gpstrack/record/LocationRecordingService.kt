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
import io.github.sennheiser1986.gpstrack.data.ActivityType
import io.github.sennheiser1986.gpstrack.data.TrackRepository
import io.github.sennheiser1986.gpstrack.data.haversineMeters
import io.github.sennheiser1986.gpstrack.ui.formatDistance
import io.github.sennheiser1986.gpstrack.ui.formatDuration
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

    /** The last fix that passed the quality gate, for teleport rejection. */
    private var lastAcceptedFix: Location? = null

    /** Fixes rejected in a row; after a few the next fix re-anchors instead of being dropped. */
    private var consecutiveRejections = 0

    /** When the ongoing notification's stats line was last refreshed. */
    private var lastNotificationUpdateMillis = 0L

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
                ActivityType.from(intent.getStringExtra(EXTRA_ACTIVITY_TYPE)),
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
     * @param activityType what kind of outing is being recorded.
     */
    private fun startRecording(name: String, activityType: ActivityType) {
        if (trackId != -1L) return
        // A location-typed foreground service may only enter the foreground while location
        // permission is held (enforced from Android 14). Callers are expected to have requested
        // it already; if it is somehow missing, stop before going foreground.
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }
        // Starting from a Quick Settings tile with the app in the background can be refused on
        // some devices; failing quietly beats crashing the tile tap.
        val started = runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }.isSuccess
        if (!started) {
            stopSelf()
            return
        }

        lastAcceptedFix = null
        lastNotificationUpdateMillis = 0L
        val startedAt = System.currentTimeMillis()
        lifecycleScope.launch {
            trackId = repository.startTrack(name, startedAt, activityType)
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
     * Stores a fix — if it passes the quality gate — and forwards it to [RecordingState],
     * refreshing the notification's stats line at most every few seconds.
     *
     * @param location the new fix.
     */
    override fun onLocationChanged(location: Location) {
        val currentTrackId = trackId
        if (currentTrackId == -1L) return
        if (!passesQualityGate(location)) return
        lastAcceptedFix = location
        lifecycleScope.launch {
            val stored = repository.appendFix(currentTrackId, location)
            RecordingState.appendRecordedPoint(stored)
            maybeUpdateNotification()
        }
    }

    /**
     * Filters out fixes that would corrupt the track: a wide accuracy circle (indoor drift, cold
     * starts) or a jump implying an impossible speed from the previous accepted fix. A run of
     * rejections re-anchors on the next fix rather than dropping fixes forever — otherwise one
     * bad fix (or a stray fix from the other provider) could stall the whole recording.
     *
     * @param location the candidate fix.
     * @return true when the fix should be stored.
     */
    private fun passesQualityGate(location: Location): Boolean {
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_METERS) {
            // A genuinely vague fix never re-anchors: it is noise at any point in the track.
            return false
        }
        val previous = lastAcceptedFix
        val accepted = when {
            previous == null -> true
            consecutiveRejections >= MAX_CONSECUTIVE_REJECTIONS -> true
            else -> {
                val legMillis = location.time - previous.time
                val legMeters = haversineMeters(
                    previous.latitude, previous.longitude, location.latitude, location.longitude,
                )
                if (legMillis > 0) {
                    legMeters / (legMillis / 1000.0) <= MAX_LEG_SPEED_MPS
                } else {
                    // No usable time delta (repeated or out-of-order timestamps): fall back to
                    // a plain distance sanity check against the fix cadence.
                    legMeters <= MAX_TIMELESS_JUMP_METERS
                }
            }
        }
        consecutiveRejections = if (accepted) 0 else consecutiveRejections + 1
        return accepted
    }

    /**
     * Rewrites the ongoing notification with the live distance and elapsed time, throttled to
     * one update per [NOTIFICATION_UPDATE_INTERVAL_MILLIS].
     */
    private fun maybeUpdateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateMillis < NOTIFICATION_UPDATE_INTERVAL_MILLIS) return
        lastNotificationUpdateMillis = now
        val manager = getSystemService<android.app.NotificationManager>() ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
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
        // Before the first fix there is nothing to report; afterwards show live figures so the
        // reader never has to open the app mid-activity.
        val startedAt = RecordingState.startedAtMillis.value
        val text = if (startedAt == null || RecordingState.liveTrail.value.isEmpty()) {
            "Recording your track…"
        } else {
            val distance = formatDistance(RecordingState.liveDistanceMeters.value)
            val elapsed = formatDuration(System.currentTimeMillis() - startedAt)
            val moving = formatDuration(RecordingState.liveMovingMillis.value)
            "$distance · $elapsed · moving $moving"
        }
        return NotificationCompat.Builder(this, TrackRecorderApp.RECORDING_CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_channel_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
        private const val EXTRA_ACTIVITY_TYPE = "activity_type"
        private const val NOTIFICATION_ID = 4201

        /** Fastest cadence at which fixes are delivered. */
        private const val MIN_UPDATE_INTERVAL_MILLIS = 3_000L

        /** Minimum move between delivered fixes, so a stationary device stops adding points. */
        private const val MIN_UPDATE_DISTANCE_METERS = 4f

        /** Fixes with a wider accuracy circle than this are dropped as GPS noise. */
        private const val MAX_ACCURACY_METERS = 30f

        /** A leg implying a higher speed than this (252 km/h) is dropped as a teleport. */
        private const val MAX_LEG_SPEED_MPS = 70.0

        /** Distance cap for legs whose timestamps give no usable time delta. */
        private const val MAX_TIMELESS_JUMP_METERS = 250.0

        /** After this many rejections in a row, the next fix is accepted as the new anchor. */
        private const val MAX_CONSECUTIVE_REJECTIONS = 4

        /** How often the ongoing notification's stats line is refreshed at most. */
        private const val NOTIFICATION_UPDATE_INTERVAL_MILLIS = 5_000L

        /**
         * Starts recording a new track.
         *
         * @param context any context.
         * @param trackName reader-visible name, or null to name it after the current time.
         * @param activityType what kind of outing is being recorded.
         */
        fun start(context: Context, trackName: String?, activityType: ActivityType = ActivityType.WALK) {
            val intent = Intent(context, LocationRecordingService::class.java).apply {
                action = ACTION_START
                if (trackName != null) putExtra(EXTRA_TRACK_NAME, trackName)
                putExtra(EXTRA_ACTIVITY_TYPE, activityType.name)
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
