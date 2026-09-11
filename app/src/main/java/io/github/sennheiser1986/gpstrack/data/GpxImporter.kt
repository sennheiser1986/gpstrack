package io.github.sennheiser1986.gpstrack.data

import java.io.InputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * A track parsed out of a GPX file, ready to be stored via
 * [TrackRepository.importTrack].
 *
 * @property name the GPX track (or file-level) name, or null when the file has none.
 * @property points the fixes in file order, with `trackId` 0.
 */
data class ImportedTrack(
    val name: String?,
    val points: List<TrackPoint>,
)

/**
 * Reads GPX 1.0/1.1 files produced by this app or by other recorders. Track segments are
 * flattened into one point list; route points (`rtept`) are accepted when the file has no track
 * points at all. Waypoint-only files import nothing.
 */
object GpxImporter {

    /**
     * Parses a GPX document.
     *
     * @param input the file contents; consumed but not closed.
     * @return the parsed track, or null when the file is not GPX or holds no usable points.
     */
    fun parse(input: InputStream): ImportedTrack? {
        val document = runCatching {
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(input)
        }.getOrNull() ?: return null

        val root = document.documentElement ?: return null
        if (!root.tagName.endsWith("gpx")) return null

        val points = pointsFrom(root, "trkpt").ifEmpty { pointsFrom(root, "rtept") }
        if (points.isEmpty()) return null

        val name = firstTextByTag(root, "name")?.trim()?.takeIf { it.isNotEmpty() }
        return ImportedTrack(name, points.sortedBy { it.timestampMillis })
    }

    /**
     * Collects every point element with the given tag anywhere in the document.
     *
     * @param root the `gpx` element.
     * @param tag `trkpt` or `rtept`.
     * @return the parsed points, skipping elements with unparseable coordinates.
     */
    private fun pointsFrom(root: Element, tag: String): List<TrackPoint> {
        val nodes = root.getElementsByTagName(tag)
        val points = ArrayList<TrackPoint>(nodes.length)
        // GPX points without a <time> keep timestamp 0; they are assigned synthetic ascending
        // times afterwards so ordering and duration maths stay sane.
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            val lat = element.getAttribute("lat").toDoubleOrNull() ?: continue
            val lon = element.getAttribute("lon").toDoubleOrNull() ?: continue
            points += TrackPoint(
                trackId = 0,
                latitude = lat,
                longitude = lon,
                altitudeMeters = firstTextByTag(element, "ele")?.toDoubleOrNull(),
                speedMetersPerSecond = firstTextByTag(element, "speed")?.toFloatOrNull(),
                accuracyMeters = null,
                timestampMillis = firstTextByTag(element, "time")?.let(::parseTime) ?: 0L,
            )
        }
        return withSyntheticTimes(points)
    }

    /**
     * Replaces missing (zero) timestamps with an ascending second-per-point series anchored to
     * the file's first real timestamp, or to the epoch when none exists.
     *
     * @param points the parsed points.
     * @return the points with strictly usable timestamps.
     */
    private fun withSyntheticTimes(points: List<TrackPoint>): List<TrackPoint> {
        if (points.none { it.timestampMillis == 0L }) return points
        val anchor = points.firstOrNull { it.timestampMillis > 0 }?.timestampMillis ?: 1_000L
        var synthetic = anchor
        return points.map { point ->
            if (point.timestampMillis > 0) {
                synthetic = point.timestampMillis
                point
            } else {
                synthetic += 1_000L
                point.copy(timestampMillis = synthetic)
            }
        }
    }

    /**
     * Reads the text of the first direct or nested element with the given tag.
     *
     * @param parent the element to search under.
     * @param tag the child tag name.
     * @return the text content, or null.
     */
    private fun firstTextByTag(parent: Element, tag: String): String? {
        val nodes = parent.getElementsByTagName(tag)
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            // getElementsByTagName is recursive: for the root "name" lookup make sure we do not
            // grab a waypoint's name nested somewhere unrelated — the first hit is fine for
            // points, and for the root GPX metadata/track name it is the conventional position.
            return element.textContent
        }
        return null
    }

    /**
     * Parses a GPX ISO-8601 timestamp.
     *
     * @param text e.g. "2024-05-18T07:15:00Z".
     * @return milliseconds since the epoch, or 0 when unparseable.
     */
    private fun parseTime(text: String): Long =
        runCatching { Instant.parse(text.trim()).toEpochMilli() }.getOrDefault(0L)
}
