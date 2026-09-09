package io.github.sennheiser1986.gpstrack.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Mean Earth radius in metres, used for great-circle distances. */
private const val EARTH_RADIUS_METERS = 6_371_008.8

/**
 * Great-circle distance between two coordinates using the haversine formula.
 *
 * @param startLatitude first point latitude in degrees.
 * @param startLongitude first point longitude in degrees.
 * @param endLatitude second point latitude in degrees.
 * @param endLongitude second point longitude in degrees.
 * @return the distance in metres.
 */
fun haversineMeters(
    startLatitude: Double,
    startLongitude: Double,
    endLatitude: Double,
    endLongitude: Double,
): Double {
    val startLatRad = Math.toRadians(startLatitude)
    val endLatRad = Math.toRadians(endLatitude)
    val deltaLat = Math.toRadians(endLatitude - startLatitude)
    val deltaLon = Math.toRadians(endLongitude - startLongitude)
    val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
        cos(startLatRad) * cos(endLatRad) * sin(deltaLon / 2) * sin(deltaLon / 2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
}

/**
 * Distance walked along an ordered list of fixes, summing the leg between each pair.
 *
 * @param points the fixes in recording order.
 * @return the total ground distance in metres; 0 for fewer than two points.
 */
fun totalDistanceMeters(points: List<TrackPoint>): Double {
    var total = 0.0
    for (index in 1 until points.size) {
        val previous = points[index - 1]
        val current = points[index]
        total += haversineMeters(
            previous.latitude, previous.longitude,
            current.latitude, current.longitude,
        )
    }
    return total
}
