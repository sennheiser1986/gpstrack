package io.github.sennheiser1986.gpstrack.data

/**
 * A plain geographic coordinate, used for live map drawing and for passing positions between
 * the services and the interface without dragging in osmdroid or Android location types.
 *
 * @property latitude degrees north, in the range -90..90.
 * @property longitude degrees east, in the range -180..180.
 */
data class LatLon(
    val latitude: Double,
    val longitude: Double,
)
