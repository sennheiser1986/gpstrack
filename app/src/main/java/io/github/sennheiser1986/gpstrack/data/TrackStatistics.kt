package io.github.sennheiser1986.gpstrack.data

import kotlin.math.max

/**
 * Figures derived from a track's fixes, shown on the track detail screen and cached onto the
 * [Track] row when recording stops.
 *
 * @property distanceMeters ground distance over all legs.
 * @property totalDurationMillis time between the first and last fix.
 * @property movingDurationMillis time spent with a leg speed above [MOVING_SPEED_THRESHOLD_MPS].
 * @property elevationGainMeters sum of the positive altitude changes between fixes.
 * @property maxSpeedMetersPerSecond fastest single leg (or reported fix speed) seen.
 * @property averageMovingSpeedMetersPerSecond distance divided by moving time, or 0.
 */
data class TrackStatistics(
    val distanceMeters: Double,
    val totalDurationMillis: Long,
    val movingDurationMillis: Long,
    val elevationGainMeters: Double,
    val maxSpeedMetersPerSecond: Double,
    val averageMovingSpeedMetersPerSecond: Double,
) {
    companion object {
        /** Legs slower than this (about 1.8 km/h) are treated as a pause, not movement. */
        const val MOVING_SPEED_THRESHOLD_MPS = 0.5

        /** Altitude wobble smaller than this is ignored so GPS noise does not inflate the climb. */
        const val ELEVATION_NOISE_FLOOR_METERS = 1.0

        /** The zero result for a track with fewer than two fixes. */
        val EMPTY = TrackStatistics(0.0, 0L, 0L, 0.0, 0.0, 0.0)
    }
}

/**
 * Computes [TrackStatistics] from an ordered list of fixes.
 *
 * @param points the fixes in recording order.
 * @return the derived figures, or [TrackStatistics.EMPTY] for fewer than two fixes.
 */
fun computeStatistics(points: List<TrackPoint>): TrackStatistics {
    if (points.size < 2) return TrackStatistics.EMPTY

    var distance = 0.0
    var movingMillis = 0L
    var elevationGain = 0.0
    var maxSpeed = 0.0

    for (index in 1 until points.size) {
        val previous = points[index - 1]
        val current = points[index]

        val legMeters = haversineMeters(
            previous.latitude, previous.longitude,
            current.latitude, current.longitude,
        )
        distance += legMeters

        val legMillis = current.timestampMillis - previous.timestampMillis
        val legSpeed = if (legMillis > 0) legMeters / (legMillis / 1000.0) else 0.0
        if (legSpeed >= TrackStatistics.MOVING_SPEED_THRESHOLD_MPS) {
            movingMillis += legMillis
        }

        val reportedSpeed = current.speedMetersPerSecond?.toDouble() ?: 0.0
        maxSpeed = max(maxSpeed, max(legSpeed, reportedSpeed))

        val previousAltitude = previous.altitudeMeters
        val currentAltitude = current.altitudeMeters
        if (previousAltitude != null && currentAltitude != null) {
            val climb = currentAltitude - previousAltitude
            if (climb > TrackStatistics.ELEVATION_NOISE_FLOOR_METERS) {
                elevationGain += climb
            }
        }
    }

    val totalMillis = points.last().timestampMillis - points.first().timestampMillis
    val averageMovingSpeed =
        if (movingMillis > 0) distance / (movingMillis / 1000.0) else 0.0

    return TrackStatistics(
        distanceMeters = distance,
        totalDurationMillis = totalMillis,
        movingDurationMillis = movingMillis,
        elevationGainMeters = elevationGain,
        maxSpeedMetersPerSecond = maxSpeed,
        averageMovingSpeedMetersPerSecond = averageMovingSpeed,
    )
}
