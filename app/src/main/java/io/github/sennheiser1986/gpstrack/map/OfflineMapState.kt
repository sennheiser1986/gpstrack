package io.github.sennheiser1986.gpstrack.map

/**
 * One in-flight (or failed) region download, for the Manual tab.
 *
 * @property regionPath server-relative path being downloaded.
 * @property displayName reader-visible region name.
 * @property percent progress 0..100, or -1 while unknown.
 * @property failed true when the download gave up and can be retried.
 */
data class OfflineDownload(
    val regionPath: String,
    val displayName: String,
    val percent: Int,
    val failed: Boolean,
)

/** What the offline-map region picker is currently showing. */
sealed interface CatalogState {
    /** The picker is closed or has nothing loaded. */
    data object Idle : CatalogState

    /** A directory listing is being fetched. */
    data class Loading(val path: String) : CatalogState

    /** The listing could not be fetched. */
    data class Error(val path: String, val message: String) : CatalogState

    /** A directory listing is on screen. */
    data class Loaded(val path: String, val entries: List<CatalogEntry>) : CatalogState
}
