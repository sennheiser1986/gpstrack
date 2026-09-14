package io.github.sennheiser1986.gpstrack.share

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.sennheiser1986.gpstrack.MainActivity
import io.github.sennheiser1986.gpstrack.R
import io.github.sennheiser1986.gpstrack.TrackRecorderApp
import io.github.sennheiser1986.gpstrack.data.LatLon
import io.github.sennheiser1986.gpstrack.record.RecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that runs the sharing loop while the reader is either broadcasting their
 * own position or watching at least one peer. Every [SYNC_INTERVAL_MILLIS] it calls
 * [LocationShareClient.sync] with this device's current position (when broadcasting) and the
 * ids of the visible peers, then publishes the answer into [PeerDirectory].
 *
 * Call [sync] whenever the broadcast switch or a peer's visibility changes: the service starts,
 * stops or simply re-reads its settings as needed.
 */
class LocationShareService : LifecycleService(), LocationListener {

    private lateinit var preferences: SharePreferences
    private lateinit var peersStore: PeersStore
    private lateinit var locationManager: LocationManager
    private var loopJob: Job? = null
    private var lastFix: Location? = null

    /** Follow-request ids a heads-up notification has already been posted for. */
    private val notifiedFollowIds = mutableSetOf<Long>()

    /** Peers currently inside the proximity-alert radius, so each approach alerts once. */
    private val nearbyPeerIds = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        preferences = SharePreferences(this)
        peersStore = PeersStore(this)
        locationManager = getSystemService()!!
    }

    /**
     * Starts or refreshes the sharing loop, or stops the service when there is nothing to do.
     *
     * @param intent the command intent; the action is [ACTION_SYNC] or [ACTION_STOP].
     * @param flags framework restart flags.
     * @param startId framework start id.
     * @return [START_STICKY] so the loop resumes if Android kills the service.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // The stop intent arrives via startService(), with no obligation to go foreground.
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        // A follow decision from a notification action button: record it, clear the alert, and
        // fall through so the foreground start and an immediate sync deliver it to the server.
        val decisionAction = intent?.action == ACTION_FOLLOW_ALLOW || intent?.action == ACTION_FOLLOW_DENY
        if (decisionAction) {
            val followId = intent!!.getLongExtra(EXTRA_FOLLOW_ID, -1L)
            if (followId >= 0L) {
                FollowRequestDirectory.decide(followId, intent.action == ACTION_FOLLOW_ALLOW)
                NotificationManagerCompat.from(this).cancel(followNotificationId(followId))
            }
        }

        // The sync intent arrives via startForegroundService(): startForeground() must be
        // called. A location-typed service also needs location permission to do so on Android
        // 14+, so if that is missing there is nothing we can safely run.
        val started = runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }.isSuccess
        if (!started || !hasWork() || !hasLocationPermission()) {
            stopEverything()
            return START_NOT_STICKY
        }
        // GPS is only needed to publish our own position: watching peers is pure network
        // polling, so with the broadcast off the location subscription is dropped and the GPS
        // radio can sleep.
        if (preferences.isBroadcasting()) {
            ensureLocationUpdates()
        } else {
            runCatching { locationManager.removeUpdates(this) }
            lastFix = null
        }
        ensureLoop()
        if (decisionAction) lifecycleScope.launch { runCatching { syncOnce() } }
        return START_STICKY
    }

    /**
     * Reports whether the service currently has a reason to run. Watching peers is not one:
     * the server caches every broadcaster's last position, and the app fetches it in the
     * foreground while the Map is actually being looked at.
     *
     * @return true when broadcasting is on or a web follow decision still has to be delivered.
     */
    private fun hasWork(): Boolean =
        preferences.isBroadcasting() || FollowRequestDirectory.hasUndeliveredDecisions()

    /**
     * Subscribes to coarse location updates once, so the sharing loop always has a recent fix
     * to send. The recorder, when running, also feeds [RecordingState]; here we keep our own
     * lightweight subscription so sharing works without recording.
     */
    private fun ensureLocationUpdates() {
        if (!hasLocationPermission()) return
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_INTERVAL_MILLIS,
                LOCATION_DISTANCE_METERS,
                this,
                Looper.getMainLooper(),
            )
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                LOCATION_INTERVAL_MILLIS,
                LOCATION_DISTANCE_METERS,
                this,
                Looper.getMainLooper(),
            )
        }
    }

    /** Starts the periodic sync coroutine if it is not already running. */
    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        loopJob = lifecycleScope.launch {
            while (isActive) {
                val result = runCatching { syncOnce() }
                if (result.exceptionOrNull() is ShareAuthRequiredException) {
                    // The server wants a (fresh) sign-in; looping would only hammer 401s.
                    // The Share tab shows the sign-in card and restarts us on success.
                    ShareAuthState.reject()
                    stopEverything()
                    break
                }
                delay(SYNC_INTERVAL_MILLIS)
                if (!hasWork()) {
                    stopEverything()
                    break
                }
            }
        }
    }

    /**
     * Runs one sync exchange and publishes the result.
     */
    private suspend fun syncOnce() {
        val broadcasting = preferences.isBroadcasting()
        val watched = peersStore.watchedIds()
        val fix = lastFix
        val position: LatLon? =
            if (fix != null) LatLon(fix.latitude, fix.longitude) else RecordingState.currentLocation.value

        val decisions = FollowRequestDirectory.snapshotDecisions()
        val request = SyncRequest(
            instanceId = preferences.instanceId(),
            label = preferences.displayName(),
            broadcasting = broadcasting,
            position = position,
            timeMillis = fix?.time ?: System.currentTimeMillis(),
            watching = watched,
            followDecisions = decisions,
        )
        val result = withContext(Dispatchers.IO) {
            LocationShareClient.sync(preferences.serverUrl(), request, preferences.deviceToken())
        }
        ShareAuthState.accept()
        // Follows made on the web page under the same account become peers here automatically.
        peersStore.mergeAccountFollows(result.followedDevices, preferences.instanceId())
        PeerDirectory.replaceAll(result.peers)
        PeerDirectory.mergeOwners(result.watchedOwners)
        // Only reachable while broadcasting (this service does not run otherwise), which is
        // exactly when being told "X is nearby" is wanted.
        checkProximity(position, result.peers)
        FollowRequestDirectory.replaceAll(result.followRequests, result.followers)
        alertNewFollowRequests(result.followRequests)
        if (decisions.isNotEmpty()) {
            FollowRequestDirectory.acknowledge(decisions.keys.mapNotNull { it.toLongOrNull() })
        }
    }

    /**
     * Remembers the latest fix for the next sync and feeds the shared location state.
     *
     * @param location the new fix.
     */
    override fun onLocationChanged(location: Location) {
        lastFix = location
        RecordingState.updateCurrentLocation(LatLon(location.latitude, location.longitude))
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    /** Cancels the loop, drops the location subscription and leaves the foreground. */
    private fun stopEverything() {
        loopJob?.cancel()
        loopJob = null
        runCatching { locationManager.removeUpdates(this) }
        PeerDirectory.clear()
        // FollowRequestDirectory is left as-is: any queued decisions are already delivered by
        // the time hasWork() lets us stop, and keeping the last pending/approved lists avoids a
        // flicker on the Share tab.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        loopJob?.cancel()
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
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = "Sharing your location"
        return NotificationCompat.Builder(this, TrackRecorderApp.SHARING_CHANNEL_ID)
            .setContentTitle(getString(R.string.sharing_channel_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    /**
     * Alerts once when a watched peer comes within [PROXIMITY_ALERT_METERS] of this device,
     * re-arming only after they leave [PROXIMITY_REARM_METERS] (hysteresis) or go offline.
     *
     * @param own this device's position, or null when unknown.
     * @param peers the watched peers' latest positions from this sync.
     */
    private fun checkProximity(own: LatLon?, peers: Map<String, PeerLocation>) {
        if (own == null) return
        val unseen = nearbyPeerIds.toMutableSet()
        peers.forEach { (id, location) ->
            val meters = io.github.sennheiser1986.gpstrack.data.haversineMeters(
                own.latitude, own.longitude,
                location.position.latitude, location.position.longitude,
            )
            when {
                meters <= PROXIMITY_ALERT_METERS -> {
                    unseen.remove(id)
                    if (nearbyPeerIds.add(id) && canPostNotifications()) {
                        val label = location.label?.takeIf { it.isNotBlank() } ?: id.take(8)
                        runCatching {
                            NotificationManagerCompat.from(this).notify(
                                NEARBY_NOTIFICATION_BASE + (id.hashCode() and 0xFF),
                                buildNearbyNotification(label, meters),
                            )
                        }
                    }
                }
                meters >= PROXIMITY_REARM_METERS -> {
                    unseen.remove(id)
                    nearbyPeerIds.remove(id)
                }
                else -> unseen.remove(id) // inside the hysteresis band: keep current state
            }
        }
        // Peers that stopped reporting re-arm too.
        unseen.forEach(nearbyPeerIds::remove)
    }

    /**
     * Builds the "someone is nearby" alert; tapping it opens the Map tab.
     *
     * @param label the peer's display name.
     * @param meters current distance.
     * @return the notification.
     */
    private fun buildNearbyNotification(label: String, meters: Double): Notification {
        val openMap = PendingIntent.getActivity(
            this,
            9,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, "MAP")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val distance = if (meters < 1000) "%.0f m".format(meters) else "%.1f km".format(meters / 1000)
        return NotificationCompat.Builder(this, TrackRecorderApp.NEARBY_CHANNEL_ID)
            .setContentTitle("$label is nearby")
            .setContentText("About $distance away")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openMap)
            .build()
    }

    /**
     * Posts a heads-up notification for every pending follow request not alerted before, so the
     * reader hears about it without opening the Share tab. Ids no longer pending are forgotten so
     * a later request with a fresh id alerts again.
     *
     * @param requests the pending follow requests from the latest sync.
     */
    private fun alertNewFollowRequests(requests: List<FollowRequest>) {
        notifiedFollowIds.retainAll(requests.map { it.id }.toSet())
        val fresh = requests.filter { notifiedFollowIds.add(it.id) }
        if (fresh.isEmpty() || !canPostNotifications()) return
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        fresh.forEach { request ->
            runCatching {
                manager.notify(followNotificationId(request.id), buildFollowRequestNotification(request))
            }
        }
    }

    /**
     * Builds the alert for one follow request: who is asking, plus Allow and Deny actions that
     * come straight back to this service, and a body tap that opens the Share tab.
     *
     * @param request the pending follow request.
     * @return the notification.
     */
    private fun buildFollowRequestNotification(request: FollowRequest): Notification {
        val openShareTab = PendingIntent.getActivity(
            this,
            request.id.toInt(),
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_SHARE)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, TrackRecorderApp.FOLLOW_REQUEST_CHANNEL_ID)
            .setContentTitle("Follow request")
            .setContentText("${request.user} wants to follow your location")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openShareTab)
            .addAction(0, "Allow", followDecisionIntent(request.id, allow = true))
            .addAction(0, "Deny", followDecisionIntent(request.id, allow = false))
            .build()
    }

    /**
     * A pending intent that redelivers an Allow/Deny choice for a follow request to this service.
     *
     * @param followId the server-side follow id.
     * @param allow true for Allow, false for Deny.
     * @return the pending intent for a notification action.
     */
    private fun followDecisionIntent(followId: Long, allow: Boolean): PendingIntent {
        val intent = Intent(this, LocationShareService::class.java).apply {
            action = if (allow) ACTION_FOLLOW_ALLOW else ACTION_FOLLOW_DENY
            putExtra(EXTRA_FOLLOW_ID, followId)
        }
        val requestCode = (followId.toInt() shl 1) or (if (allow) 1 else 0)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * The notification id for a follow request, kept distinct from the ongoing service id and
     * stable so the alert can be cancelled once decided.
     *
     * @param followId the server-side follow id.
     * @return a per-request notification id.
     */
    private fun followNotificationId(followId: Long): Int =
        FOLLOW_NOTIFICATION_BASE + (followId % 10_000L).toInt()

    /**
     * Whether the app may post a normal notification: always below Android 13, otherwise only
     * with the runtime POST_NOTIFICATIONS grant.
     *
     * @return true when [alertNewFollowRequests] may call `notify`.
     */
    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Reports whether at least coarse location permission is held.
     *
     * @return true when the service may read a fix.
     */
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val ACTION_SYNC = "io.github.sennheiser1986.gpstrack.share.SYNC"
        private const val ACTION_STOP = "io.github.sennheiser1986.gpstrack.share.STOP"
        private const val ACTION_FOLLOW_ALLOW = "io.github.sennheiser1986.gpstrack.share.FOLLOW_ALLOW"
        private const val ACTION_FOLLOW_DENY = "io.github.sennheiser1986.gpstrack.share.FOLLOW_DENY"
        private const val EXTRA_FOLLOW_ID = "follow_id"
        private const val NOTIFICATION_ID = 4202

        /** Base id for per-follow-request heads-up notifications. */
        private const val FOLLOW_NOTIFICATION_BASE = 4300

        /** A watched peer closer than this triggers the one-shot "nearby" alert. */
        private const val PROXIMITY_ALERT_METERS = 500.0

        /** The alert re-arms only once the peer is farther away than this again. */
        private const val PROXIMITY_REARM_METERS = 750.0

        /** Base id for per-peer nearby notifications. */
        private const val NEARBY_NOTIFICATION_BASE = 4500

        /** How often the sync exchange runs. */
        private const val SYNC_INTERVAL_MILLIS = 7_000L

        /** How often the local location subscription may deliver a fix. */
        private const val LOCATION_INTERVAL_MILLIS = 5_000L

        /** Minimum move between delivered fixes. */
        private const val LOCATION_DISTANCE_METERS = 5f

        /**
         * Starts the service, or lets it re-evaluate whether it still has work. Safe to call on
         * every settings change.
         *
         * @param context any context.
         */
        fun sync(context: Context) {
            val intent = Intent(context, LocationShareService::class.java).apply {
                action = ACTION_SYNC
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Stops the service regardless of its current settings.
         *
         * @param context any context.
         */
        fun stop(context: Context) {
            val intent = Intent(context, LocationShareService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
