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
 * The optional offline vector map: a single MapsForge ``.map`` file (Belgium) that, once
 * downloaded, lets every map in the app render without any tile server.
 *
 * The file lives in the app's external files dir so it survives updates, needs no permission,
 * and can be inspected or replaced over ``adb``.
 */
object OfflineMap {

    /** Where the finished map is downloaded from; overridable at build time. */
    const val DOWNLOAD_URL: String = BuildConfig.OFFLINE_MAP_URL

    private const val FILE_NAME = "belgium.map"

    @Volatile
    private var graphicsInitialised = false

    /**
     * The offline map file (may not exist yet).
     *
     * @param context any context.
     * @return the target [File].
     */
    fun mapFile(context: Context): File =
        File(context.getExternalFilesDir("offline"), FILE_NAME)

    /**
     * The partial-download file used while a download is in progress.
     *
     * @param context any context.
     * @return the ``.part`` [File].
     */
    fun partFile(context: Context): File =
        File(context.getExternalFilesDir("offline"), "$FILE_NAME.part")

    /**
     * Whether a usable offline map is present.
     *
     * @param context any context.
     * @return true when the map file exists and is non-empty.
     */
    fun isReady(context: Context): Boolean = mapFile(context).let { it.isFile && it.length() > 0L }

    /**
     * Size of the downloaded map in bytes, or 0 when absent.
     *
     * @param context any context.
     * @return the file size.
     */
    fun sizeBytes(context: Context): Long = mapFile(context).takeIf { it.isFile }?.length() ?: 0L

    /**
     * Removes the offline map and any partial download.
     *
     * @param context any context.
     */
    fun delete(context: Context) {
        mapFile(context).delete()
        partFile(context).delete()
    }

    /**
     * Builds an osmdroid tile provider that renders from the offline map, or null when no map
     * is present or it cannot be opened.
     *
     * @param context any context.
     * @return a [MapsForgeTileProvider], or null.
     */
    fun tileProvider(context: Context): MapTileProviderBase? {
        val file = mapFile(context)
        if (!file.isFile || file.length() == 0L) return null
        return runCatching {
            if (!graphicsInitialised) {
                MapsForgeTileSource.createInstance(context.applicationContext as Application)
                graphicsInitialised = true
            }
            val source = MapsForgeTileSource.createFromFiles(arrayOf(file))
            MapsForgeTileProvider(SimpleRegisterReceiver(context), source, null)
        }.getOrNull()
    }
}
