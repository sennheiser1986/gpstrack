package io.github.sennheiser1986.gpstrack.share

import io.github.sennheiser1986.gpstrack.data.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The last position reported for a peer, as returned by the sharing server.
 *
 * @property position the peer's coordinate.
 * @property timeMillis when the peer recorded it, in milliseconds since the epoch.
 * @property label the peer's display name at the time of the report, if the server had one.
 */
data class PeerLocation(
    val position: LatLon,
    val timeMillis: Long,
    val label: String?,
)

/**
 * Process-wide, in-memory table of where each watched peer is. [LocationShareService] fills it
 * in after every sync; the Map tab observes it.
 */
object PeerDirectory {

    private val _locations = MutableStateFlow<Map<String, PeerLocation>>(emptyMap())

    /** Peer id to that peer's most recent reported position. */
    val locations: StateFlow<Map<String, PeerLocation>> = _locations.asStateFlow()

    /**
     * Replaces the whole table with the latest sync result.
     *
     * @param locations peer id to position, for every peer the server answered for.
     */
    fun replaceAll(locations: Map<String, PeerLocation>) {
        _locations.value = locations
    }

    /** Forgets every peer position, e.g. when sharing is switched off. */
    fun clear() {
        _locations.value = emptyMap()
    }
}
