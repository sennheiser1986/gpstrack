package io.github.sennheiser1986.gpstrack.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sennheiser1986.gpstrack.share.FollowRequest
import io.github.sennheiser1986.gpstrack.share.Peer
import io.github.sennheiser1986.gpstrack.share.PeerLocation
import io.github.sennheiser1986.gpstrack.share.ShareCodec

/**
 * The Share tab. It shows this instance's QR code, the switch that starts and stops the
 * position broadcast, an editable display name and server URL, a button to scan a peer's code,
 * and the list of scanned peers with a per-peer show-on-map switch.
 *
 * @param instanceId this device's sharing id, shown in short form under the code.
 * @param qrPayload the exact string encoded in the QR code.
 * @param displayName the current display name.
 * @param onDisplayNameChange invoked with a new display name.
 * @param broadcasting whether the position broadcast is on.
 * @param onBroadcastChange invoked when the broadcast switch is toggled.
 * @param serverUrl the current sharing server URL.
 * @param onServerUrlChange invoked with a new server URL.
 * @param peers the scanned peers.
 * @param peerLocations peer id to latest reported position, for the "last seen" labels.
 * @param nowMillis current time, for the "last seen" labels.
 * @param followRequests web users awaiting an allow/deny decision.
 * @param webFollowers web users whose follow is currently approved.
 * @param onDecideFollow invoked with a follow id and true (allow) or false (deny/stop).
 * @param onScan invoked to scan a peer's code with the camera.
 * @param onScanImage invoked to pick a saved QR image and read a peer from it.
 * @param onAddById invoked with a pasted sharing id or link to add a peer.
 * @param onPeerVisibleChange invoked with a peer id and the new visibility.
 * @param onRemovePeer invoked with a peer id to forget.
 */
@Composable
fun ShareScreen(
    instanceId: String,
    qrPayload: String,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    broadcasting: Boolean,
    onBroadcastChange: (Boolean) -> Unit,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    peers: List<Peer>,
    peerLocations: Map<String, PeerLocation>,
    nowMillis: Long,
    followRequests: List<FollowRequest>,
    webFollowers: List<FollowRequest>,
    onDecideFollow: (Long, Boolean) -> Unit,
    onScan: () -> Unit,
    onScanImage: () -> Unit,
    onAddById: (String) -> Unit,
    onPeerVisibleChange: (String, Boolean) -> Unit,
    onRemovePeer: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val qrBitmap = remember(qrPayload) { ShareCodec.qrBitmap(qrPayload, 600) }
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "This device's sharing code",
                    modifier = Modifier.size(240.dp),
                )
                Text(displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "ID ${instanceId.take(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "Have someone scan this to follow you on their map.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Broadcast my location", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (broadcasting) "On — peers watching you see your position." else "Off — no position leaves this device.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = broadcasting, onCheckedChange = onBroadcastChange)
        }

        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayNameChange,
            label = { Text("Display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        var showServerField by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { showServerField = !showServerField }) {
            Text(if (showServerField) "Hide server setting" else "Server setting")
        }
        if (showServerField) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text("Sharing server URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (followRequests.isNotEmpty() || webFollowers.isNotEmpty()) {
            HorizontalDivider()
            Text("Web followers", style = MaterialTheme.typography.titleMedium)

            followRequests.forEach { request ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "${request.user} is asking to follow your location.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { onDecideFollow(request.id, true) }) { Text("Allow") }
                            OutlinedButton(onClick = { onDecideFollow(request.id, false) }) { Text("Deny") }
                        }
                    }
                }
            }

            webFollowers.forEach { follower ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${follower.user} is following you",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    OutlinedButton(onClick = { onDecideFollow(follower.id, false) }) { Text("Stop") }
                }
            }

            if (!broadcasting) {
                Text(
                    "Turn on \"Broadcast my location\" so approved followers can see you.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        HorizontalDivider()

        Text("People I follow", style = MaterialTheme.typography.titleMedium)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                Text("  Scan")
            }
            OutlinedButton(onClick = onScanImage, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Image, contentDescription = null)
                Text("  Image")
            }
        }

        var pastedId by remember { mutableStateOf("") }
        OutlinedTextField(
            value = pastedId,
            onValueChange = { pastedId = it },
            label = { Text("Paste a sharing ID or link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(
                    onClick = {
                        onAddById(pastedId.trim())
                        pastedId = ""
                    },
                    enabled = pastedId.isNotBlank(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add this ID")
                }
            },
        )

        if (peers.isEmpty()) {
            Text("No peers yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            peers.forEach { peer ->
                PeerRow(
                    peer = peer,
                    location = peerLocations[peer.id],
                    nowMillis = nowMillis,
                    onVisibleChange = { onPeerVisibleChange(peer.id, it) },
                    onRemove = { onRemovePeer(peer.id) },
                )
            }
        }
    }
}

/**
 * One row of the followed-people list: name, last-seen time, a show-on-map switch and a delete
 * button.
 *
 * @param peer the peer.
 * @param location the peer's latest reported position, or null.
 * @param nowMillis current time, for the "last seen" label.
 * @param onVisibleChange invoked with the new visibility.
 * @param onRemove invoked to forget the peer.
 */
@Composable
private fun PeerRow(
    peer: Peer,
    location: PeerLocation?,
    nowMillis: Long,
    onVisibleChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(peer.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                if (location != null) "seen ${formatAge(location.timeMillis, nowMillis)}" else "no fix yet",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = peer.visibleOnMap, onCheckedChange = onVisibleChange)
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove ${peer.label}")
        }
    }
}
