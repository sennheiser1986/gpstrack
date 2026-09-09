package io.github.sennheiser1986.gpstrack

import io.github.sennheiser1986.gpstrack.data.ExportFormat
import io.github.sennheiser1986.gpstrack.data.Track
import io.github.sennheiser1986.gpstrack.data.TrackExporter
import io.github.sennheiser1986.gpstrack.data.TrackPoint
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the serialisers in `TrackExporter.kt`. */
class TrackExporterTest {

    private val track = Track(
        id = 7,
        name = "Morning & evening <loop>",
        startedAtMillis = 1_700_000_000_000,
        endedAtMillis = 1_700_000_600_000,
        distanceMeters = 1234.5,
        pointCount = 3,
    )

    private val points = listOf(
        TrackPoint(trackId = 7, latitude = 50.8503, longitude = 4.3517, altitudeMeters = 13.0, timestampMillis = 1_700_000_000_000),
        TrackPoint(trackId = 7, latitude = 50.8510, longitude = 4.3520, altitudeMeters = 15.0, timestampMillis = 1_700_000_300_000),
        TrackPoint(trackId = 7, latitude = 50.8520, longitude = 4.3530, altitudeMeters = 14.0, timestampMillis = 1_700_000_600_000),
    )

    /** GPX output is well-formed enough to carry one trkpt per fix and escapes the name. */
    @Test
    fun gpxHasOneTrackPointPerFix() {
        val gpx = TrackExporter.toGpx(track, points)
        assertTrue(gpx.startsWith("<?xml"))
        assertTrue(gpx.contains("<gpx"))
        assertTrue(gpx.contains("&amp;") && gpx.contains("&lt;loop&gt;"))
        assertTrue("raw markup must be escaped", !gpx.contains("<loop>"))
        assertEquals(3, gpx.split("<trkpt").size - 1)
    }

    /** KML writes coordinates longitude-first. */
    @Test
    fun kmlIsLongitudeFirst() {
        val kml = TrackExporter.toKml(track, points)
        assertTrue(kml.contains("<kml"))
        assertTrue(kml.contains("4.3517,50.8503,13.0"))
    }

    /** GeoJSON is a FeatureCollection with a LineString of the right length. */
    @Test
    fun geoJsonIsAFeatureCollection() {
        val json = TrackExporter.toGeoJson(track, points)
        assertTrue(json.contains("\"FeatureCollection\""))
        assertTrue(json.contains("\"LineString\""))
        assertEquals(3, json.split("],[").size) // three coordinate tuples
    }

    /** The suggested name is filesystem-safe and carries the format extension. */
    @Test
    fun suggestedFileNameIsSafe() {
        val name = TrackExporter.suggestedFileName(ExportFormat.GPX, track)
        assertTrue(name.endsWith(".gpx"))
        assertTrue(name.none { it in "<>&/" })
    }

    private fun assertEquals(expected: Int, actual: Int) =
        org.junit.Assert.assertEquals(expected.toLong(), actual.toLong())
}
