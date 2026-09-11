package io.github.sennheiser1986.gpstrack.map

import java.net.HttpURLConnection
import java.net.URL

/**
 * One row of the MapsForge download catalogue.
 *
 * @property name the entry's name without trailing slash or extension, e.g. "belgium" or "germany".
 * @property path server-relative path, e.g. "europe/belgium.map" or "europe/germany/".
 * @property isFolder true for a sub-folder, false for a downloadable ``.map`` file.
 * @property sizeLabel human size from the listing (e.g. "494M"), or null for folders.
 */
data class CatalogEntry(
    val name: String,
    val path: String,
    val isFolder: Boolean,
    val sizeLabel: String?,
)

/**
 * Reads the MapsForge download server's directory listings, turning its Apache index pages into
 * browsable entries: continents at the root, then countries, then sub-regions for the large
 * ones. Only ``.map`` files and folders are surfaced.
 */
object MapsForgeCatalog {

    /** Matches one listing row: the link href/text plus the trailing size cell. */
    private val ROW_PATTERN = Regex(
        """<a href="([^"?][^"]*)">[^<]*</a>.*?align="right">[^<]*<.*?align="right">\s*([^<]*?)\s*<""",
    )

    /**
     * Lists one directory of the catalogue.
     *
     * @param path server-relative directory, "" for the root or e.g. "europe/".
     * @return the folders and map files in listing order.
     * @throws Exception when the network request fails or the page is not a listing.
     */
    fun list(path: String): List<CatalogEntry> {
        val cleanPath = path.trim('/')
        val url = if (cleanPath.isEmpty()) "${OfflineMap.BASE_URL}/" else "${OfflineMap.BASE_URL}/$cleanPath/"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "GPSTrack/1.0 (Android)")
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "HTTP ${connection.responseCode}"
            }
            val html = connection.inputStream.bufferedReader().readText()
            return parse(html, cleanPath)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parses an Apache index page.
     *
     * @param html the page body.
     * @param directory the listed directory, without slashes at either end.
     * @return the entries; absolute links (Parent Directory, sort headers) are skipped.
     */
    internal fun parse(html: String, directory: String): List<CatalogEntry> {
        val prefix = if (directory.isEmpty()) "" else "$directory/"
        return ROW_PATTERN.findAll(html).mapNotNull { match ->
            val href = match.groupValues[1]
            val size = match.groupValues[2].takeIf { it.isNotEmpty() && it != "-" }
            when {
                href.startsWith("/") || href.startsWith("http") -> null
                href.endsWith("/") -> CatalogEntry(
                    name = href.trimEnd('/'),
                    path = "$prefix$href",
                    isFolder = true,
                    sizeLabel = null,
                )
                href.endsWith(".map") -> CatalogEntry(
                    name = href.removeSuffix(".map"),
                    path = "$prefix$href",
                    isFolder = false,
                    sizeLabel = size,
                )
                else -> null
            }
        }.toList()
    }
}
