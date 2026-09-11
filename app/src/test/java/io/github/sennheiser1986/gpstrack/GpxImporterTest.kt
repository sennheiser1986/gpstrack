package io.github.sennheiser1986.gpstrack

import io.github.sennheiser1986.gpstrack.data.GpxImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parses representative GPX documents: a normal track, a file without timestamps, and files
 * that are not usable at all.
 */
class GpxImporterTest {

    private val gpx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
          <trk>
            <name>Morning walk</name>
            <trkseg>
              <trkpt lat="50.8503" lon="4.3517"><ele>56.0</ele><time>2024-05-18T07:15:00Z</time></trkpt>
              <trkpt lat="50.8513" lon="4.3527"><ele>58.5</ele><time>2024-05-18T07:15:30Z</time></trkpt>
              <trkpt lat="50.8523" lon="4.3537"><ele>57.0</ele><time>2024-05-18T07:16:00Z</time></trkpt>
            </trkseg>
          </trk>
        </gpx>
    """.trimIndent()

    @Test
    fun parsesTrackWithNameElevationAndTime() {
        val imported = GpxImporter.parse(gpx.byteInputStream())
        assertNotNull(imported)
        imported!!
        assertEquals("Morning walk", imported.name)
        assertEquals(3, imported.points.size)
        assertEquals(50.8503, imported.points.first().latitude, 1e-9)
        assertEquals(56.0, imported.points.first().altitudeMeters!!, 1e-9)
        assertEquals(1716016500000L, imported.points.first().timestampMillis)
        assertEquals(1716016560000L, imported.points.last().timestampMillis)
    }

    @Test
    fun assignsSyntheticAscendingTimesWhenMissing() {
        val timeless = """
            <gpx version="1.0"><trk><trkseg>
              <trkpt lat="1.0" lon="1.0"/>
              <trkpt lat="1.001" lon="1.0"/>
              <trkpt lat="1.002" lon="1.0"/>
            </trkseg></trk></gpx>
        """.trimIndent()
        val imported = GpxImporter.parse(timeless.byteInputStream())
        assertNotNull(imported)
        val times = imported!!.points.map { it.timestampMillis }
        assertEquals(times, times.sorted())
        assertEquals(times.size, times.distinct().size)
    }

    @Test
    fun fallsBackToRoutePoints() {
        val route = """
            <gpx version="1.1"><rte>
              <rtept lat="2.0" lon="3.0"/>
              <rtept lat="2.1" lon="3.1"/>
            </rte></gpx>
        """.trimIndent()
        val imported = GpxImporter.parse(route.byteInputStream())
        assertNotNull(imported)
        assertEquals(2, imported!!.points.size)
    }

    @Test
    fun rejectsNonGpxAndPointlessFiles() {
        assertNull(GpxImporter.parse("not xml at all".byteInputStream()))
        assertNull(GpxImporter.parse("<kml><Placemark/></kml>".byteInputStream()))
        assertNull(GpxImporter.parse("<gpx version=\"1.1\"><wpt lat=\"1\" lon=\"1\"/></gpx>".byteInputStream()))
    }
}
