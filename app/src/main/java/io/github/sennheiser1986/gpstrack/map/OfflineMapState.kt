package io.github.sennheiser1986.gpstrack.map

/** What the interface should show for the offline map. */
sealed interface OfflineMapState {

    /** No offline map, and none downloading. */
    data object Absent : OfflineMapState

    /**
     * A download is running.
     *
     * @property percent 0..100, or -1 when the total size is not yet known.
     */
    data class Downloading(val percent: Int) : OfflineMapState

    /**
     * The offline map is ready to use.
     *
     * @property bytes its size on disk.
     */
    data class Ready(val bytes: Long) : OfflineMapState

    /** The last download attempt failed. */
    data object Failed : OfflineMapState
}
