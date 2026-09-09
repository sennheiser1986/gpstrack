package io.github.sennheiser1986.gpstrack

import io.github.sennheiser1986.gpstrack.data.TrackPoint
import io.github.sennheiser1986.gpstrack.data.computeStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks `computeStatistics` in `TrackStatistics.kt`. */
class TrackStatisticsTest {

    /** An empty or single-point track yields the zero result. */
    @Test
    fun emptyResultForShortTracks() {
        assertEquals(0.0, computeStatistics(emptyList()).distanceMeters, 0.0)
        assertEquals(
            0.0,
            computeStatistics(listOf(point(0, 0.0, 0.0, null))).distanceMeters,
            0.0,
        )
    }

    /** Distance, total time and climb are summed across the points. */
    @Test
    fun sumsDistanceTimeAndClimb() {
        val points = listOf(
            point(0, 0.0, 0.0, altitude = 10.0),
            point(60_000, 0.0, 0.01, altitude = 25.0),
            point(120_000, 0.0, 0.02, altitude = 20.0),
        )
        val statistics = computeStatistics(points)

        assertEquals(120_000L, statistics.totalDurationMillis)
        assertTrue("distance should be positive", statistics.distanceMeters > 0.0)
        // Only the +15 m leg counts; the -5 m leg is a descent.
        assertEquals(15.0, statistics.elevationGainMeters, 0.001)
    }

    /** Legs slower than the walking threshold do not add to the moving time. */
    @Test
    fun pausesDoNotCountAsMovingTime() {
        val points = listOf(
            point(0, 0.0, 0.0, null),
            point(600_000, 0.00001, 0.0, null), // ~1 m in 10 minutes: a pause
        )
        assertEquals(0L, computeStatistics(points).movingDurationMillis)
    }

    private fun point(timeMillis: Long, lat: Double, lon: Double, altitude: Double?) =
        TrackPoint(
            trackId = 1,
            latitude = lat,
            longitude = lon,
            altitudeMeters = altitude,
            timestampMillis = timeMillis,
        )
}
