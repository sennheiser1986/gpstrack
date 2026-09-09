package io.github.sennheiser1986.gpstrack.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Room access for the tracks database. */
@Dao
interface TrackDao {

    /**
     * Inserts a track.
     *
     * @param track the row to insert; its id is ignored.
     * @return the assigned row id.
     */
    @Insert
    suspend fun insertTrack(track: Track): Long

    /**
     * Updates a track in place, matched by id.
     *
     * @param track the row to write back.
     */
    @Update
    suspend fun updateTrack(track: Track)

    /**
     * Deletes a track and, through the foreign key, all of its points.
     *
     * @param trackId id of the track to remove.
     */
    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteTrack(trackId: Long)

    /**
     * Appends a fix.
     *
     * @param point the fix to store; its id is ignored.
     * @return the assigned row id.
     */
    @Insert
    suspend fun insertPoint(point: TrackPoint): Long

    /**
     * Streams every track, newest first, so the list updates as recording progresses.
     *
     * @return a flow that emits the full ordered list on every change.
     */
    @Query("SELECT * FROM tracks ORDER BY startedAtMillis DESC")
    fun observeTracks(): Flow<List<Track>>

    /**
     * Streams one track.
     *
     * @param trackId id of the track to watch.
     * @return a flow that emits the track, or null once it is deleted.
     */
    @Query("SELECT * FROM tracks WHERE id = :trackId")
    fun observeTrack(trackId: Long): Flow<Track?>

    /**
     * Reads one track once.
     *
     * @param trackId id of the track to read.
     * @return the track, or null when it does not exist.
     */
    @Query("SELECT * FROM tracks WHERE id = :trackId")
    suspend fun findTrack(trackId: Long): Track?

    /**
     * Reads every track still marked as recording (no end time).
     *
     * @return the open tracks, if any.
     */
    @Query("SELECT * FROM tracks WHERE endedAtMillis IS NULL")
    suspend fun openTracks(): List<Track>

    /**
     * Reads every fix of a track in recording order.
     *
     * @param trackId id of the owning track.
     * @return the fixes ordered by time.
     */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestampMillis ASC")
    suspend fun pointsForTrack(trackId: Long): List<TrackPoint>

    /**
     * Streams the fixes of a track in recording order.
     *
     * @param trackId id of the owning track.
     * @return a flow that re-emits whenever a fix is added.
     */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestampMillis ASC")
    fun observePointsForTrack(trackId: Long): Flow<List<TrackPoint>>
}
