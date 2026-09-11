package io.github.sennheiser1986.gpstrack.map

import android.app.Application
import android.content.Context
import io.github.sennheiser1986.gpstrack.BuildConfig
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import java.io.File

/**
 * A downloaded offline region: one MapsForge ``.map`` file.
 *
 * @property file the map file on disk.
 * @property displayName reader-visible region name, e.g. "belgium" or "germany / berlin".
 * @property sizeBytes the file size.
 */
data class OfflineRegion(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
)

/**
 * The optional offline vector maps: MapsForge ``.map`` files downloaded per region from the
 * MapsForge server. Any number of regions can be installed side by side; every map in the app
 * renders from all of them at once.
 *
 * Files live in the app's external files dir so they survive updates, need no permission, and
 * can be inspected or replaced over ``adb``. A region's server path (e.g. ``europe/belgium.map``)
 * maps to a flat local file name with ``__`` for slashes; the pre-multi-region ``belgium.map``
 * file is still recognised.
 */
object OfflineMap {

    /** Base URL of the MapsForge map tree; overridable at build time. */
    const val BASE_URL: String = BuildConfig.OFFLINE_MAP_BASE_URL

    /** Separator that replaces ``/`` in server paths to form a flat local file name. */
    private const val PATH_SEPARATOR = "__"

    @Volatile
    private var graphicsInitialised = false

    /**
     * The directory holding the downloaded regions.
     *
     * @param context any context.
     * @return the offline dir (created by the framework on first use).
     */
    fun dir(context: Context): File = context.getExternalFilesDir("offline")!!

    /**
     * The installed regions, alphabetically.
     *
     * @param context any context.
     * @return one [OfflineRegion] per complete ``.map`` file.
     */
    fun regions(context: Context): List<OfflineRegion> =
        dir(context).listFiles { file -> file.isFile && file.name.endsWith(".map") && file.length() > 0 }
            .orEmpty()
            .sortedBy { it.name }
            .map { OfflineRegion(it, displayName(it.name), it.length()) }

    /**
     * Turns a local file name back into a readable region name.
     *
     * @param fileName e.g. "europe__germany__berlin.map" or the legacy "belgium.map".
     * @return e.g. "germany / berlin" (the leading continent is dropped when present).
     */
    fun displayName(fileName: String): String {
        val parts = fileName.removeSuffix(".map").split(PATH_SEPARATOR)
        val withoutContinent = if (parts.size > 1) parts.drop(1) else parts
        return withoutContinent.joinToString(" / ")
    }

    /**
     * The local file a server region path downloads to.
     *
     * @param context any context.
     * @param regionPath server-relative path, e.g. "europe/belgium.map".
     * @return the target [File].
     */
    fun fileForRegion(context: Context, regionPath: String): File =
        File(dir(context), regionPath.trim('/').replace("/", PATH_SEPARATOR))

    /**
     * The partial-download file used while a region download is in progress.
     *
     * @param context any context.
     * @param regionPath server-relative path.
     * @return the ``.part`` [File].
     */
    fun partFileForRegion(context: Context, regionPath: String): File =
        File(dir(context), fileForRegion(context, regionPath).name + ".part")

    /**
     * Whether at least one usable offline region is present.
     *
     * @param context any context.
     * @return true when the maps can render offline.
     */
    fun isReady(context: Context): Boolean = regions(context).isNotEmpty()

    /**
     * A value that changes whenever the installed set of regions changes, for keying the map's
     * tile-provider setup.
     *
     * @param context any context.
     * @return 0 when no region is installed, otherwise a hash of the file names and sizes.
     */
    fun version(context: Context): Int {
        val regions = regions(context)
        if (regions.isEmpty()) return 0
        return regions.joinToString { "${it.file.name}:${it.sizeBytes}" }.hashCode().let {
            if (it == 0) 1 else it
        }
    }

    /**
     * Removes one region and any partial download for it.
     *
     * @param context any context.
     * @param fileName the region's local file name.
     */
    fun deleteRegion(context: Context, fileName: String) {
        File(dir(context), fileName).delete()
        File(dir(context), "$fileName.part").delete()
    }

    /**
     * Builds an osmdroid tile provider that renders from every installed region, or null when
     * none is present or they cannot be opened.
     *
     * @param context any context.
     * @return a [MapsForgeTileProvider], or null.
     */
    fun tileProvider(context: Context): MapTileProviderBase? {
        val files = regions(context).map { it.file }
        if (files.isEmpty()) return null
        return runCatching {
            if (!graphicsInitialised) {
                MapsForgeTileSource.createInstance(context.applicationContext as Application)
                graphicsInitialised = true
            }
            val source = MapsForgeTileSource.createFromFiles(files.toTypedArray())
            MapsForgeTileProvider(SimpleRegisterReceiver(context), source, null)
        }.getOrNull()
    }
}
