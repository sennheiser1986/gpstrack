package io.github.sennheiser1986.gpstrack.data

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A file format a track can be written out as. Each entry knows the details the Storage Access
 * Framework needs to create the document.
 *
 * @property displayName label shown on the export button.
 * @property mimeType MIME type handed to the document picker.
 * @property fileExtension extension appended to the suggested file name (without a dot).
 */
enum class ExportFormat(
    val displayName: String,
    val mimeType: String,
    val fileExtension: String,
) {
    /** GPS Exchange Format: the interchange format understood by almost every GPS tool. */
    GPX("GPX", "application/gpx+xml", "gpx"),

    /** Keyhole Markup Language: opens directly in Google Earth and most mapping software. */
    KML("KML", "application/vnd.google-earth.kml+xml", "kml"),

    /** GeoJSON: the format web maps and GIS tools ingest most easily. */
    GEOJSON("GeoJSON", "application/geo+json", "geojson"),
}

/** Serialises tracks to the formats in [ExportFormat]. */
object TrackExporter {

    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    private val fileNameTimestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)

    /**
     * Serialises a track in the requested format.
     *
     * @param format the target format.
     * @param track the track being exported.
     * @param points the track's fixes in recording order.
     * @return the file contents as text.
     */
    fun export(format: ExportFormat, track: Track, points: List<TrackPoint>): String =
        when (format) {
            ExportFormat.GPX -> toGpx(track, points)
            ExportFormat.KML -> toKml(track, points)
            ExportFormat.GEOJSON -> toGeoJson(track, points)
        }

    /**
     * Builds a safe suggested file name for a track and format, e.g. `Morning ride-20240518-071500.gpx`.
     *
     * @param format the target format.
     * @param track the track being exported.
     * @return a file name including the extension.
     */
    fun suggestedFileName(format: ExportFormat, track: Track): String {
        val safeName = track.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "track" }
        val stamp = fileNameTimestampFormatter.format(Instant.ofEpochMilli(track.startedAtMillis))
        return "$safeName-$stamp.${format.fileExtension}"
    }

    /**
     * Renders a track as a GPX 1.1 document with a single `<trk>` and one `<trkseg>`.
     *
     * @param track the track being exported.
     * @param points the track's fixes in recording order.
     * @return the GPX text.
     */
    fun toGpx(track: Track, points: List<TrackPoint>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append(
            """<gpx version="1.1" creator="GPS Track" """ +
                """xmlns="http://www.topografix.com/GPX/1/1">""",
        ).append('\n')
        append("  <metadata>\n")
        append("    <name>").append(xmlEscape(track.name)).append("</name>\n")
        append("    <time>").append(timestampFormatter.format(Instant.ofEpochMilli(track.startedAtMillis)))
            .append("</time>\n")
        append("  </metadata>\n")
        append("  <trk>\n")
        append("    <name>").append(xmlEscape(track.name)).append("</name>\n")
        append("    <trkseg>\n")
        for (point in points) {
            append("      <trkpt lat=\"").append(point.latitude).append("\" lon=\"")
                .append(point.longitude).append("\">\n")
            point.altitudeMeters?.let { append("        <ele>").append(it).append("</ele>\n") }
            append("        <time>")
                .append(timestampFormatter.format(Instant.ofEpochMilli(point.timestampMillis)))
                .append("</time>\n")
            append("      </trkpt>\n")
        }
        append("    </trkseg>\n")
        append("  </trk>\n")
        append("</gpx>\n")
    }

    /**
     * Renders a track as a KML 2.2 document with one `<Placemark>` holding a `<LineString>`.
     * KML coordinates are written longitude-first, per the specification.
     *
     * @param track the track being exported.
     * @param points the track's fixes in recording order.
     * @return the KML text.
     */
    fun toKml(track: Track, points: List<TrackPoint>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<kml xmlns="http://www.opengis.net/kml/2.2">""").append('\n')
        append("  <Document>\n")
        append("    <name>").append(xmlEscape(track.name)).append("</name>\n")
        append("    <Placemark>\n")
        append("      <name>").append(xmlEscape(track.name)).append("</name>\n")
        append("      <LineString>\n")
        append("        <tessellate>1</tessellate>\n")
        append("        <coordinates>\n")
        for (point in points) {
            append("          ").append(point.longitude).append(',').append(point.latitude)
            append(',').append(point.altitudeMeters ?: 0.0).append('\n')
        }
        append("        </coordinates>\n")
        append("      </LineString>\n")
        append("    </Placemark>\n")
        append("  </Document>\n")
        append("</kml>\n")
    }

    /**
     * Renders a track as a GeoJSON `FeatureCollection` with a `LineString` for the path and a
     * `MultiPoint` carrying the per-fix timestamps in its `coordinates`.
     *
     * @param track the track being exported.
     * @param points the track's fixes in recording order.
     * @return the GeoJSON text.
     */
    fun toGeoJson(track: Track, points: List<TrackPoint>): String {
        val path = points.joinToString(",") { point ->
            "[${point.longitude},${point.latitude}${point.altitudeMeters?.let { ",$it" } ?: ""}]"
        }
        val times = points.joinToString(",") { point ->
            "\"${timestampFormatter.format(Instant.ofEpochMilli(point.timestampMillis))}\""
        }
        return buildString {
            append("{\n")
            append("  \"type\": \"FeatureCollection\",\n")
            append("  \"features\": [\n")
            append("    {\n")
            append("      \"type\": \"Feature\",\n")
            append("      \"properties\": {\n")
            append("        \"name\": ").append(jsonString(track.name)).append(",\n")
            append("        \"startedAt\": ")
                .append(jsonString(timestampFormatter.format(Instant.ofEpochMilli(track.startedAtMillis))))
                .append(",\n")
            append("        \"distanceMeters\": ").append(track.distanceMeters).append(",\n")
            append("        \"coordinateTimes\": [").append(times).append("]\n")
            append("      },\n")
            append("      \"geometry\": {\n")
            append("        \"type\": \"LineString\",\n")
            append("        \"coordinates\": [").append(path).append("]\n")
            append("      }\n")
            append("    }\n")
            append("  ]\n")
            append("}\n")
        }
    }

    /**
     * Escapes the five XML markup characters in a text value.
     *
     * @param value the raw text.
     * @return the text safe to place in element content or a double-quoted attribute.
     */
    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /**
     * Renders a Kotlin string as a JSON string literal, including the surrounding quotes.
     *
     * @param value the raw text.
     * @return the quoted, escaped literal.
     */
    private fun jsonString(value: String): String = buildString {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
