package io.github.sennheiser1986.gpstrack

import io.github.sennheiser1986.gpstrack.data.TrackPoint
import io.github.sennheiser1986.gpstrack.data.haversineMeters
import io.github.sennheiser1986.gpstrack.data.totalDistanceMeters
import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks the great-circle helpers in `GeoMath.kt`. */
class GeoMathTest {

    /** A degree of latitude is about 111 km anywhere on Earth. */
    @Test
    fun oneDegreeOfLatitudeIsAboutOneHundredAndElevenKilometres() {
        val meters = haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, meters, 500.0)
    }

    /** The distance between a point and itself is zero. */
    @Test
    fun zeroForIdenticalPoints() {
        assertEquals(0.0, haversineMeters(50.85, 4.35, 50.85, 4.35), 1e-6)
    }

    /** The path length is the sum of its legs. */
    @Test
    fun totalDistanceSumsTheLegs() {
        val points = listOf(
            point(0.0, 0.0),
            point(0.0, 1.0),
            point(0.0, 2.0),
        )
        val oneLeg = haversineMeters(0.0, 0.0, 0.0, 1.0)
        assertEquals(oneLeg * 2, totalDistanceMeters(points), 1.0)
    }

    /** Fewer than two points has no distance. */
    @Test
    fun singlePointHasNoDistance() {
        assertEquals(0.0, totalDistanceMeters(listOf(point(1.0, 1.0))), 0.0)
    }

    private fun point(latitude: Double, longitude: Double) =
        TrackPoint(trackId = 1, latitude = latitude, longitude = longitude, timestampMillis = 0)
}
