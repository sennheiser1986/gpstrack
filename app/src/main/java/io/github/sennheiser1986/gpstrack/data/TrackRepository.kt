package io.github.sennheiser1986.gpstrack.data

import android.content.Context
import android.location.Location
import kotlinx.coroutines.flow.Flow

/**
 * The single place the rest of the app goes through to read and change tracks. It owns the Room
 * [TrackDao] and keeps the derived summary fields on [Track] in step with the stored points.
 */
class TrackRepository(private val dao: TrackDao) {

    /** Every track, newest first; re-emits as points are added and tracks are renamed. */
    val tracks: Flow<List<Track>> = dao.observeTracks()

    /**
     * Streams one track.
     *
     * @param trackId id of the track to watch.
     * @return a flow of the track, emitting null once it is deleted.
     */
    fun track(trackId: Long): Flow<Track?> = dao.observeTrack(trackId)

    /**
     * Streams one track's fixes in recording order.
     *
     * @param trackId id of the owning track.
     * @return a flow of the ordered fixes.
     */
    fun points(trackId: Long): Flow<List<TrackPoint>> = dao.observePointsForTrack(trackId)

    /**
     * Reads one track's fixes once, for export.
     *
     * @param trackId id of the owning track.
     * @return the ordered fixes.
     */
    suspend fun pointsOnce(trackId: Long): List<TrackPoint> = dao.pointsForTrack(trackId)

    /**
     * Creates a new, empty track that is "recording" until [finishTrack] is called.
     *
     * @param name reader-visible name.
     * @param startedAtMillis start time in milliseconds since the epoch.
     * @param activityType what kind of outing this is.
     * @return the new track's id.
     */
    suspend fun startTrack(
        name: String,
        startedAtMillis: Long,
        activityType: ActivityType = ActivityType.WALK,
    ): Long =
        dao.insertTrack(
            Track(name = name, startedAtMillis = startedAtMillis, activityType = activityType.name),
        )

    /**
     * Changes a track's activity type after the fact.
     *
     * @param trackId the track to change.
     * @param activityType the new type.
     */
    suspend fun setActivityType(trackId: Long, activityType: ActivityType) {
        val track = dao.findTrack(trackId) ?: return
        dao.updateTrack(track.copy(activityType = activityType.name))
    }

    /**
     * Stores a complete, already-finished track — a GPX import or a backup restore. The summary
     * fields are computed here from the given points.
     *
     * @param name reader-visible name.
     * @param activityType what kind of outing this is.
     * @param points the ordered fixes; must not be empty.
     * @param startedAtMillis original start time to preserve, or null to use the first fix.
     * @param endedAtMillis original end time to preserve, or null to use the last fix.
     * @return the new track's id, or null when [points] is empty.
     */
    suspend fun importTrack(
        name: String,
        activityType: ActivityType,
        points: List<TrackPoint>,
        startedAtMillis: Long? = null,
        endedAtMillis: Long? = null,
    ): Long? {
        if (points.isEmpty()) return null
        val statistics = computeStatistics(points)
        val id = dao.insertTrack(
            Track(
                name = name,
                startedAtMillis = startedAtMillis ?: points.first().timestampMillis,
                endedAtMillis = endedAtMillis ?: points.last().timestampMillis,
                distanceMeters = statistics.distanceMeters,
                elevationGainMeters = statistics.elevationGainMeters,
                pointCount = points.size,
                activityType = activityType.name,
            ),
        )
        dao.insertPoints(points.map { it.copy(id = 0, trackId = id) })
        return id
    }

    /**
     * Reads every track once, oldest first, for building a backup.
     *
     * @return all tracks.
     */
    suspend fun allTracksOnce(): List<Track> = dao.allTracks()

    /**
     * Stores one fix for a track. Summary fields are recomputed when the track is finished, so
     * this is a plain insert.
     *
     * @param trackId id of the owning track.
     * @param location the Android fix to store.
     * @return the stored [TrackPoint], with its assigned row id.
     */
    suspend fun appendFix(trackId: Long, location: Location): TrackPoint {
        val point = TrackPoint(
            trackId = trackId,
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = if (location.hasAltitude()) location.altitude else null,
            speedMetersPerSecond = if (location.hasSpeed()) location.speed else null,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            timestampMillis = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
        val id = dao.insertPoint(point)
        return point.copy(id = id)
    }

    /**
     * Closes a track: stamps its end time and writes the cached distance, climb and point count.
     * A track that never received a fix is deleted instead.
     *
     * @param trackId id of the track to close.
     * @param endedAtMillis stop time in milliseconds since the epoch.
     */
    suspend fun finishTrack(trackId: Long, endedAtMillis: Long) {
        val track = dao.findTrack(trackId) ?: return
        val points = dao.pointsForTrack(trackId)
        if (points.isEmpty()) {
            dao.deleteTrack(trackId)
            return
        }
        val statistics = computeStatistics(points)
        dao.updateTrack(
            track.copy(
                endedAtMillis = endedAtMillis,
                distanceMeters = statistics.distanceMeters,
                elevationGainMeters = statistics.elevationGainMeters,
                pointCount = points.size,
            ),
        )
    }

    /**
     * Renames a track.
     *
     * @param trackId id of the track to rename.
     * @param newName the new reader-visible name.
     */
    suspend fun renameTrack(trackId: Long, newName: String) {
        val track = dao.findTrack(trackId) ?: return
        dao.updateTrack(track.copy(name = newName))
    }

    /**
     * Deletes a track and all of its fixes.
     *
     * @param trackId id of the track to remove.
     */
    suspend fun deleteTrack(trackId: Long) = dao.deleteTrack(trackId)

    /**
     * Closes any track left open by a recording that was killed before it could stop (for
     * example the process was force-stopped). Each such track is finished at the time of its
     * last fix, or deleted when it never received one. Safe to call on every app start.
     */
    suspend fun reconcileOpenTracks() {
        for (track in dao.openTracks()) {
            val points = dao.pointsForTrack(track.id)
            if (points.isEmpty()) {
                dao.deleteTrack(track.id)
                continue
            }
            val statistics = computeStatistics(points)
            dao.updateTrack(
                track.copy(
                    endedAtMillis = points.last().timestampMillis,
                    distanceMeters = statistics.distanceMeters,
                    elevationGainMeters = statistics.elevationGainMeters,
                    pointCount = points.size,
                ),
            )
        }
    }

    companion object {
        @Volatile
        private var instance: TrackRepository? = null

        /**
         * Returns the process-wide repository, creating it (and the database) on first use.
         *
         * @param context any context.
         * @return the shared [TrackRepository].
         */
        fun get(context: Context): TrackRepository =
            instance ?: synchronized(this) {
                instance ?: TrackRepository(TrackDatabase.get(context).trackDao())
                    .also { instance = it }
            }
    }
}
