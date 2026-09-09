package io.github.sennheiser1986.gpstrack.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single GPS fix belonging to a [Track]. Rows are deleted automatically when their track is
 * removed.
 *
 * @property id auto-assigned row id.
 * @property trackId id of the owning [Track].
 * @property latitude degrees north.
 * @property longitude degrees east.
 * @property altitudeMeters metres above the WGS84 ellipsoid, or null when the fix carried none.
 * @property speedMetersPerSecond ground speed reported with the fix, or null.
 * @property accuracyMeters horizontal accuracy radius reported with the fix, or null.
 * @property timestampMillis fix time in milliseconds since the Unix epoch.
 */
@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId")],
)
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Float? = null,
    val accuracyMeters: Float? = null,
    val timestampMillis: Long,
) {
    /** This fix as a plain coordinate for map drawing. */
    fun toLatLon(): LatLon = LatLon(latitude, longitude)
}
