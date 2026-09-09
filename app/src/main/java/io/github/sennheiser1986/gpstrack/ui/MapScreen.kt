package io.github.sennheiser1986.gpstrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sennheiser1986.gpstrack.data.LatLon
import io.github.sennheiser1986.gpstrack.share.Peer
import io.github.sennheiser1986.gpstrack.share.PeerLocation

/**
 * The Map tab — "where is everyone". It shows this device and every visible peer the server has
 * a recent position for as coloured dots, with a legend below the map matching each dot to a
 * name and its last-seen time.
 *
 * @param me this device's latest position, or null when unknown.
 * @param visiblePeers the peers the reader has chosen to show.
 * @param peerLocations peer id to that peer's latest reported position.
 * @param nowMillis current time, for the "last seen" labels.
 * @param offlineMapReady whether the downloaded offline map should be used.
 */
@Composable
fun MapScreen(
    me: LatLon?,
    visiblePeers: List<Peer>,
    peerLocations: Map<String, PeerLocation>,
    nowMillis: Long,
    offlineMapReady: Boolean = false,
) {
    // Each peer's colour is its position in the list, so the colours on screen stay as far
    // apart as the palette allows for the current number of peers.
    val markers = buildList {
        me?.let { add(MapMarker.me(it)) }
        visiblePeers.forEachIndexed { index, peer ->
            peerLocations[peer.id]?.let { location ->
                add(MapMarker.peer(location.position, peer.label, index))
            }
        }
    }

    val legend = buildList {
        if (me != null) add(LegendEntry("You", MarkerPalette.ME, position = me))
        visiblePeers.forEachIndexed { index, peer ->
            val location = peerLocations[peer.id]
            add(
                LegendEntry(
                    label = peer.label,
                    color = MarkerPalette.peerColor(index),
                    trailing = if (location != null) formatAge(location.timeMillis, nowMillis) else "no fix yet",
                    position = location?.position,
                ),
            )
        }
    }

    var focusRequest by remember { mutableStateOf<MapFocusRequest?>(null) }

    Column(Modifier.fillMaxSize()) {
        TrackMapView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds(),
            markers = markers,
            fallbackCenter = me,
            recenterTarget = me,
            focusRequest = focusRequest,
            offlineReady = offlineMapReady,
        )

        HorizontalDivider()

        // An opaque strip below the map, never over it.
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (visiblePeers.isEmpty() && me == null) {
                    Text(
                        "No one is shown. Scan a code on the Share tab, then switch a peer on.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        "Legend",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    MapLegend(
                        entries = legend,
                        onEntryClick = { entry ->
                            entry.position?.let { focusRequest = MapFocusRequest(it) }
                        },
                    )
                    if (visiblePeers.isEmpty()) {
                        Text(
                            "Scan a code on the Share tab to add people.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
