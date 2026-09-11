package io.github.sennheiser1986.gpstrack

import io.github.sennheiser1986.gpstrack.data.ActivityType
import io.github.sennheiser1986.gpstrack.data.BackupCodec
import io.github.sennheiser1986.gpstrack.data.BackupTrack
import io.github.sennheiser1986.gpstrack.data.Track
import io.github.sennheiser1986.gpstrack.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Round-trips the backup format and rejects foreign files. */
class BackupCodecTest {

    private fun point(seconds: Long, altitude: Double? = null) = TrackPoint(
        trackId = 0,
        latitude = 50.0 + seconds / 1_000.0,
        longitude = 4.0,
        altitudeMeters = altitude,
        speedMetersPerSecond = if (seconds % 2 == 0L) 1.5f else null,
        accuracyMeters = 5f,
        timestampMillis = 1_700_000_000_000 + seconds * 1_000,
    )

    @Test
    fun roundTripsTracksAndPoints() {
        val original = listOf(
            BackupTrack(
                track = Track(
                    name = "Ride",
                    startedAtMillis = 1_700_000_000_000,
                    endedAtMillis = 1_700_000_060_000,
                    activityType = ActivityType.BIKE.name,
                ),
                points = (0L..60L step 10).map { point(it, altitude = 50.0 + it) },
            ),
            BackupTrack(
                track = Track(
                    name = "Walk",
                    startedAtMillis = 1_700_100_000_000,
                    endedAtMillis = 1_700_100_030_000,
                    activityType = ActivityType.WALK.name,
                ),
                points = (100L..130L step 10).map { point(it) },
            ),
        )

        val decoded = BackupCodec.decode(BackupCodec.encode(original))!!

        assertEquals(2, decoded.size)
        decoded.zip(original).forEach { (got, want) ->
            assertEquals(want.track.name, got.track.name)
            assertEquals(want.track.activityType, got.track.activityType)
            assertEquals(want.track.startedAtMillis, got.track.startedAtMillis)
            assertEquals(want.track.endedAtMillis, got.track.endedAtMillis)
            assertEquals(want.points.size, got.points.size)
            got.points.zip(want.points).forEach { (gotPoint, wantPoint) ->
                assertEquals(wantPoint.latitude, gotPoint.latitude, 1e-9)
                assertEquals(wantPoint.longitude, gotPoint.longitude, 1e-9)
                assertEquals(wantPoint.altitudeMeters, gotPoint.altitudeMeters)
                assertEquals(wantPoint.speedMetersPerSecond, gotPoint.speedMetersPerSecond)
                assertEquals(wantPoint.timestampMillis, gotPoint.timestampMillis)
            }
        }
    }

    @Test
    fun rejectsForeignJson() {
        assertNull(BackupCodec.decode("{}"))
        assertNull(BackupCodec.decode("""{"format":"something-else","tracks":[]}"""))
        assertNull(BackupCodec.decode("plain text"))
    }

    @Test
    fun unknownActivityTypeFallsBackToWalk() {
        val text = BackupCodec.encode(
            listOf(
                BackupTrack(
                    Track(name = "X", startedAtMillis = 1, activityType = "TELEPORT"),
                    listOf(point(0)),
                ),
            ),
        )
        assertEquals(ActivityType.WALK.name, BackupCodec.decode(text)!!.single().track.activityType)
    }
}
