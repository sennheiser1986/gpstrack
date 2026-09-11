package io.github.sennheiser1986.gpstrack.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One track inside a backup file: the row plus all of its points, decoupled from database ids.
 *
 * @property track the track row; its [Track.id] is meaningless in a backup.
 * @property points the fixes in recording order, with `trackId` 0.
 */
data class BackupTrack(
    val track: Track,
    val points: List<TrackPoint>,
)

/**
 * Serialises the whole track database to a single JSON document and back, for the Manual tab's
 * backup/restore. The format is versioned and self-contained: nothing in it references row ids.
 *
 * Point rows are stored as compact arrays `[lat, lon, altitude?, speed?, accuracy?, timeMillis]`
 * (nulls kept as JSON null) so a big database stays a reasonably small file.
 */
object BackupCodec {

    /** Format version written into every backup. */
    const val VERSION = 1

    /**
     * Builds the backup document.
     *
     * @param tracks every track with its points.
     * @return the JSON text.
     */
    fun encode(tracks: List<BackupTrack>): String {
        val root = JSONObject()
        root.put("format", "gpstrack-backup")
        root.put("version", VERSION)
        val trackArray = JSONArray()
        tracks.forEach { entry ->
            val track = entry.track
            val json = JSONObject()
            json.put("name", track.name)
            json.put("activityType", track.activityType)
            json.put("startedAtMillis", track.startedAtMillis)
            json.put("endedAtMillis", track.endedAtMillis ?: JSONObject.NULL)
            val points = JSONArray()
            entry.points.forEach { point ->
                val row = JSONArray()
                row.put(point.latitude)
                row.put(point.longitude)
                row.put(point.altitudeMeters ?: JSONObject.NULL)
                row.put(point.speedMetersPerSecond?.toDouble() ?: JSONObject.NULL)
                row.put(point.accuracyMeters?.toDouble() ?: JSONObject.NULL)
                row.put(point.timestampMillis)
                points.put(row)
            }
            json.put("points", points)
            trackArray.put(json)
        }
        root.put("tracks", trackArray)
        return root.toString()
    }

    /**
     * Parses a backup document.
     *
     * @param text the JSON text.
     * @return the tracks it holds, or null when the text is not a gpstrack backup.
     */
    fun decode(text: String): List<BackupTrack>? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optString("format") != "gpstrack-backup") return null
        val trackArray = root.optJSONArray("tracks") ?: return null

        val tracks = ArrayList<BackupTrack>(trackArray.length())
        for (index in 0 until trackArray.length()) {
            val json = trackArray.optJSONObject(index) ?: continue
            val pointArray = json.optJSONArray("points") ?: JSONArray()
            val points = ArrayList<TrackPoint>(pointArray.length())
            for (pointIndex in 0 until pointArray.length()) {
                val row = pointArray.optJSONArray(pointIndex) ?: continue
                points += TrackPoint(
                    trackId = 0,
                    latitude = row.getDouble(0),
                    longitude = row.getDouble(1),
                    altitudeMeters = if (row.isNull(2)) null else row.getDouble(2),
                    speedMetersPerSecond = if (row.isNull(3)) null else row.getDouble(3).toFloat(),
                    accuracyMeters = if (row.isNull(4)) null else row.getDouble(4).toFloat(),
                    timestampMillis = row.getLong(5),
                )
            }
            if (points.isEmpty()) continue
            tracks += BackupTrack(
                track = Track(
                    name = json.optString("name").ifEmpty { "Imported track" },
                    startedAtMillis = json.optLong("startedAtMillis", points.first().timestampMillis),
                    endedAtMillis = if (json.isNull("endedAtMillis")) {
                        points.last().timestampMillis
                    } else {
                        json.getLong("endedAtMillis")
                    },
                    activityType = ActivityType.from(json.optString("activityType")).name,
                ),
                points = points,
            )
        }
        return tracks
    }
}
