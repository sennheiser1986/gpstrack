package io.github.sennheiser1986.gpstrack.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sennheiser1986.gpstrack.data.LatLon
import io.github.sennheiser1986.gpstrack.share.Peer
import io.github.sennheiser1986.gpstrack.share.PeerLocation

/**
 * The Map tab — "where is everyone". The map shows this device and every visible peer as
 * coloured dots; the legend under it is a table matching the web map: dot, owning user, device
 * name, last seen, and a show/hide switch. Tapping a row centres the map on that dot.
 *
 * @param me this device's latest position, or null when unknown.
 * @param peers every followed peer, visible or not; colours follow this list's order.
 * @param peerLocations peer id to that peer's latest reported position.
 * @param peerOwners peer id to the server account owning that device.
 * @param nowMillis current time, for the "last seen" labels.
 * @param onPeerVisibleChange invoked with a peer id and the new visibility.
 * @param offlineMapVersion offline-region set version; 0 renders online tiles.
 */
@Composable
fun MapScreen(
    me: LatLon?,
    peers: List<Peer>,
    peerLocations: Map<String, PeerLocation>,
    peerOwners: Map<String, String>,
    nowMillis: Long,
    onPeerVisibleChange: (String, Boolean) -> Unit,
    offlineMapVersion: Int = 0,
) {
    // Colour by position among ALL peers, so a peer keeps its colour when others are hidden.
    val markers = buildList {
        me?.let { add(MapMarker.me(it)) }
        peers.forEachIndexed { index, peer ->
            if (!peer.visibleOnMap) return@forEachIndexed
            peerLocations[peer.id]?.let { location ->
                add(MapMarker.peer(location.position, peer.label, index))
            }
        }
    }

    var focusRequest by remember { mutableStateOf<MapFocusRequest?>(null) }
    val context = LocalContext.current

    /**
     * Hands a position to whatever navigation app the reader has, via a standard geo: intent.
     */
    fun openInNavigationApp(label: String, position: LatLon) {
        val uri = android.net.Uri.parse(
            "geo:${position.latitude},${position.longitude}" +
                "?q=${position.latitude},${position.longitude}(${android.net.Uri.encode(label)})",
        )
        runCatching {
            context.startActivity(
                android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, uri),
                    "Open $label in",
                ),
            )
        }
    }

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
            offlineVersion = offlineMapVersion,
        )

        HorizontalDivider()

        // An opaque strip below the map, never over it.
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                if (peers.isEmpty() && me == null) {
                    Text(
                        "No one is shown. Scan a code on the Share tab, then switch a peer on.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    return@Column
                }

                LegendHeaderRow()

                if (me != null) {
                    LegendTableRow(
                        color = MarkerPalette.ME,
                        owner = "—",
                        device = "You",
                        age = "now",
                        visible = null,
                        onCenter = { focusRequest = MapFocusRequest(me) },
                        onNavigate = { openInNavigationApp("My position", me) },
                        onVisibleChange = null,
                    )
                }
                peers.forEachIndexed { index, peer ->
                    val location = peerLocations[peer.id]
                    LegendTableRow(
                        color = MarkerPalette.peerColor(index),
                        owner = peerOwners[peer.id] ?: "—",
                        device = peer.label,
                        age = when {
                            !peer.visibleOnMap -> "hidden"
                            location != null -> formatAge(location.timeMillis, nowMillis)
                            else -> "no fix yet"
                        },
                        visible = peer.visibleOnMap,
                        onCenter = location?.let { loc ->
                            { focusRequest = MapFocusRequest(loc.position) }
                        },
                        onNavigate = location?.let { loc ->
                            { openInNavigationApp(peer.label, loc.position) }
                        },
                        onVisibleChange = { onPeerVisibleChange(peer.id, it) },
                    )
                }
                if (peers.isEmpty()) {
                    Text(
                        "Scan a code on the Share tab to add people.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** The legend table's column captions. */
@Composable
private fun LegendHeaderRow() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Spacer(Modifier.size(18.dp))
        LegendHeaderCell("User", Modifier.weight(0.8f).padding(start = 10.dp))
        LegendHeaderCell("Device", Modifier.weight(1f))
        LegendHeaderCell("Seen", Modifier.weight(0.7f))
        LegendHeaderCell("Show", Modifier.padding(end = 4.dp))
    }
}

/**
 * One caption of the legend header.
 *
 * @param text the caption.
 * @param modifier layout modifier.
 */
@Composable
private fun LegendHeaderCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * One row of the legend table.
 *
 * @param color the marker's base colour, drawn as the shared concentric dot.
 * @param owner the owning server account, or "—".
 * @param device the device's display name.
 * @param age the last-seen text.
 * @param visible the show/hide state, or null for rows without a switch (You).
 * @param onCenter centres the map on this row's dot, or null when it has no position.
 * @param onNavigate hands the position to a navigation app, or null when it has no position.
 * @param onVisibleChange flips the show/hide switch, or null for rows without one.
 */
@Composable
private fun LegendTableRow(
    color: Int,
    owner: String,
    device: String,
    age: String,
    visible: Boolean?,
    onCenter: (() -> Unit)?,
    onNavigate: (() -> Unit)?,
    onVisibleChange: ((Boolean) -> Unit)?,
) {
    val dimmed = visible == false
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onCenter != null) it.clickable(onClick = onCenter) else it }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(18.dp).alpha(if (dimmed) 0.4f else 1f)) {
            val radius = size.minDimension / 2f
            drawCircle(Color(blendWithWhite(color, 0.62f)), radius = radius)
            drawCircle(Color.White, radius = radius * 0.5f)
            drawCircle(Color(color), radius = radius * 0.36f)
        }
        Text(
            owner,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.8f).padding(start = 10.dp).alpha(if (dimmed) 0.5f else 1f),
        )
        Text(
            device,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f).alpha(if (dimmed) 0.5f else 1f),
        )
        Text(
            age,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.7f).alpha(if (dimmed) 0.5f else 1f),
        )
        IconButton(
            onClick = { onCenter?.invoke() },
            enabled = onCenter != null,
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                Icons.Filled.CenterFocusStrong,
                contentDescription = "Centre on $device",
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(
            onClick = { onNavigate?.invoke() },
            enabled = onNavigate != null,
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                Icons.Filled.NearMe,
                contentDescription = "Open $device in a navigation app",
                modifier = Modifier.size(20.dp),
            )
        }
        if (visible != null && onVisibleChange != null) {
            Switch(
                checked = visible,
                onCheckedChange = onVisibleChange,
                modifier = Modifier.padding(start = 4.dp),
            )
        } else {
            androidx.compose.foundation.layout.Spacer(Modifier.size(52.dp, 32.dp))
        }
    }
}
