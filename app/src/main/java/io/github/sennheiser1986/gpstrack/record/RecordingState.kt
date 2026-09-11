package io.github.sennheiser1986.gpstrack.record

import io.github.sennheiser1986.gpstrack.data.LatLon
import io.github.sennheiser1986.gpstrack.data.TrackStatistics
import io.github.sennheiser1986.gpstrack.data.haversineMeters
import io.github.sennheiser1986.gpstrack.data.totalDistanceMeters
import io.github.sennheiser1986.gpstrack.data.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide, in-memory view of what the location services are currently doing. The
 * foreground services write to it; the Compose screens read it.
 *
 * Two independent things live here:
 *  - [currentLocation]: the latest fix from whichever service is running. Both the recorder and
 *    the sharing service update it, so the Map tab can show "me" even when nothing is being
 *    recorded.
 *  - the active-track fields ([activeTrackId], [liveTrail], [liveDistanceMeters],
 *    [startedAtMillis]): only meaningful while [LocationRecordingService] is running.
 */
object RecordingState {

    private val _currentLocation = MutableStateFlow<LatLon?>(null)

    /** The most recent fix, or null before the first one this session. */
    val currentLocation: StateFlow<LatLon?> = _currentLocation.asStateFlow()

    private val _activeTrackId = MutableStateFlow<Long?>(null)

    /** Id of the track being recorded, or null when the recorder is stopped. */
    val activeTrackId: StateFlow<Long?> = _activeTrackId.asStateFlow()

    private val _liveTrail = MutableStateFlow<List<LatLon>>(emptyList())

    /** The fixes recorded so far for the active track, for drawing the growing trail. */
    val liveTrail: StateFlow<List<LatLon>> = _liveTrail.asStateFlow()

    private val _liveDistanceMeters = MutableStateFlow(0.0)

    /** Ground distance of [liveTrail] in metres. */
    val liveDistanceMeters: StateFlow<Double> = _liveDistanceMeters.asStateFlow()

    private val _startedAtMillis = MutableStateFlow<Long?>(null)

    /** Start time of the active track, or null when the recorder is stopped. */
    val startedAtMillis: StateFlow<Long?> = _startedAtMillis.asStateFlow()

    private val _liveMovingMillis = MutableStateFlow(0L)

    /**
     * Auto-paused clock: time spent actually moving, summed over legs whose speed clears the
     * moving threshold. Standing at a light adds nothing here while the total clock runs on.
     */
    val liveMovingMillis: StateFlow<Long> = _liveMovingMillis.asStateFlow()

    /** The previous accepted fix, for leg speed; not exposed. */
    private var lastPoint: TrackPoint? = null

    /** True while a track is being recorded. */
    val isRecording: Boolean get() = _activeTrackId.value != null

    /**
     * Records the latest known position.
     *
     * @param location the fix.
     */
    fun updateCurrentLocation(location: LatLon) {
        _currentLocation.value = location
    }

    /**
     * Marks the start of a recording session and clears the previous trail.
     *
     * @param trackId id of the track now being recorded.
     * @param startedAtMillis its start time in milliseconds since the epoch.
     */
    fun beginRecording(trackId: Long, startedAtMillis: Long) {
        _activeTrackId.value = trackId
        _startedAtMillis.value = startedAtMillis
        _liveTrail.value = emptyList()
        _liveDistanceMeters.value = 0.0
        _liveMovingMillis.value = 0L
        lastPoint = null
    }

    /**
     * Appends a fix to the live trail and updates the running distance, the moving clock and
     * the current location.
     *
     * @param point the fix just stored for the active track.
     */
    fun appendRecordedPoint(point: TrackPoint) {
        val previous = lastPoint
        if (previous != null) {
            val legMillis = point.timestampMillis - previous.timestampMillis
            val legMeters = haversineMeters(
                previous.latitude, previous.longitude, point.latitude, point.longitude,
            )
            val legSpeed = if (legMillis > 0) legMeters / (legMillis / 1000.0) else 0.0
            if (legSpeed >= TrackStatistics.MOVING_SPEED_THRESHOLD_MPS && legMillis > 0) {
                _liveMovingMillis.update { it + legMillis }
            }
        }
        lastPoint = point
        _liveTrail.update { it + point.toLatLon() }
        _liveDistanceMeters.value = totalDistanceMetersOfLatLon(_liveTrail.value)
        updateCurrentLocation(point.toLatLon())
    }

    /** Clears the active-track fields; [currentLocation] is left untouched. */
    fun endRecording() {
        _activeTrackId.value = null
        _startedAtMillis.value = null
        _liveTrail.value = emptyList()
        _liveDistanceMeters.value = 0.0
        _liveMovingMillis.value = 0L
        lastPoint = null
    }

    /**
     * Sums the legs of a coordinate list, reusing the [TrackPoint] distance helper.
     *
     * @param trail the ordered coordinates.
     * @return the distance in metres.
     */
    private fun totalDistanceMetersOfLatLon(trail: List<LatLon>): Double =
        totalDistanceMeters(
            trail.map { TrackPoint(trackId = 0, latitude = it.latitude, longitude = it.longitude, timestampMillis = 0) },
        )
}
