package io.github.sennheiser1986.gpstrack.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One recorded outing. The individual GPS fixes live in [TrackPoint] rows that reference this
 * track by [id]. The summary fields ([distanceMeters], [pointCount], [elevationGainMeters]) are
 * filled in when recording stops so the track list does not have to load every point.
 *
 * @property id auto-assigned row id; 0 until the track has been inserted.
 * @property name reader-visible name, defaulted to the start time when the track is created.
 * @property startedAtMillis first-fix wall-clock time, in milliseconds since the Unix epoch.
 * @property endedAtMillis stop time in the same units, or null while the track is still recording.
 * @property distanceMeters ground distance summed over the points, valid once [endedAtMillis] is set.
 * @property elevationGainMeters total climb over the points, valid once [endedAtMillis] is set.
 * @property pointCount number of stored fixes, valid once [endedAtMillis] is set.
 * @property activityType the [ActivityType] name chosen when recording started.
 */
@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val distanceMeters: Double = 0.0,
    val elevationGainMeters: Double = 0.0,
    val pointCount: Int = 0,
    val activityType: String = ActivityType.WALK.name,
) {
    /** True while the recorder is still adding fixes to this track. */
    val isRecording: Boolean get() = endedAtMillis == null

    /** The parsed [activityType], falling back to walking for unknown values. */
    val activity: ActivityType get() = ActivityType.from(activityType)

    /** Wall-clock length of the track in milliseconds, or 0 while it is still recording. */
    val durationMillis: Long get() = (endedAtMillis ?: startedAtMillis) - startedAtMillis
}
