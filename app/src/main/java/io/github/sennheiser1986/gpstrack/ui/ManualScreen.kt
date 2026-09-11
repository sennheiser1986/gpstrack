package io.github.sennheiser1986.gpstrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sennheiser1986.gpstrack.map.OfflineMapState

/**
 * The Manual tab. It leads with the offline-map control and then all the caveats and background
 * notes that do not belong under the controls on the other screens.
 *
 * @param offlineMapState what the offline Belgium map is currently doing.
 * @param onDownloadOfflineMap start (or resume) the offline-map download.
 * @param onCancelOfflineMap cancel an in-progress download.
 * @param onDeleteOfflineMap remove the offline map and go back to online tiles.
 * @param batteryExempt whether the app is already excluded from battery optimisation.
 * @param onRequestBatteryExemption open the system dialog asking for the exclusion.
 * @param onBackup export every track to a single backup file.
 * @param onRestore pick a backup file and import the tracks it holds.
 */
@Composable
fun ManualScreen(
    offlineMapState: OfflineMapState = OfflineMapState.Absent,
    onDownloadOfflineMap: () -> Unit = {},
    onCancelOfflineMap: () -> Unit = {},
    onDeleteOfflineMap: () -> Unit = {},
    batteryExempt: Boolean = true,
    onRequestBatteryExemption: () -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!batteryExempt) {
            BatteryCard(onRequestBatteryExemption)
        }

        OfflineMapCard(offlineMapState, onDownloadOfflineMap, onCancelOfflineMap, onDeleteOfflineMap)

        BackupCard(onBackup, onRestore)

        Section(
            "Recording a track",
            "The Record tab captures a track using the device's GPS. Pick the activity type " +
                "(walk, run, bike, drive) before starting; walking and running show pace, " +
                "wheels show speed. Recording continues while the app is in the background and " +
                "while the screen is off, shown by an ongoing notification with the live " +
                "distance and time. Grant \"Allow all the time\" location access for " +
                "background recording to keep working; \"While using the app\" stops the track " +
                "when the app is dismissed. Fixes are taken about every three seconds and at " +
                "least four metres apart; fixes with poor accuracy or impossible jumps are " +
                "discarded. The \"Moving\" clock pauses automatically while you stand still. " +
                "Quick Settings tiles for recording and broadcasting can be added from the " +
                "notification shade's tile editor.",
        )
        Section(
            "The database",
            "Every track and every fix is stored in an on-device SQLite database. Nothing is " +
                "uploaded. Deleting a track removes it and its points permanently.",
        )
        Section(
            "Exporting and importing",
            "From a track's detail screen, choose GPX, KML or GeoJSON. GPX is the widest " +
                "interchange format; KML opens in Google Earth; GeoJSON suits web maps and GIS " +
                "tools. The system file picker chooses where the file is saved. The Tracks " +
                "tab's import button reads a GPX file recorded elsewhere into the database; " +
                "it is stored under the currently selected activity type. The Backup card " +
                "above saves every track to one file and restores from it.",
        )
        Section(
            "Offline map",
            "The maps normally stream tiles from OpenStreetMap. Download the offline map above " +
                "(a single vector file for Belgium, a few hundred MB) and every map in the app " +
                "renders from it with no network — useful out of coverage. It keeps working " +
                "outside Belgium, just without map detail there. Delete it any time to go back " +
                "to online tiles.",
        )
        Section(
            "Sharing your location",
            "Each install has a permanent sharing ID shown as a QR code on the Share tab. Turn " +
                "on \"Broadcast my location\" to publish your position to the sharing server. " +
                "Turn it off and nothing about your position leaves the device. The broadcast " +
                "also runs from an ongoing notification.",
        )
        Section(
            "Following other people",
            "On the Share tab, add someone three ways: \"Scan\" points the camera at their QR " +
                "code; \"Image\" reads a QR code out of a saved picture; or paste their sharing " +
                "ID (or a gpstrack:// link) into the field and press +. Each person you " +
                "follow appears on the Map tab with a switch; switch someone off to hide them " +
                "and stop fetching their position. The Map tab always shows your own position too.",
        )
        Section(
            "Web followers",
            "If the sharing server has an admin who has given someone a web login, that person " +
                "can ask to follow you from a browser. Their request appears under \"Web " +
                "followers\" on the Share tab while you are broadcasting; Allow or Deny it. An " +
                "allowed follower sees your position on a web map for as long as you broadcast; " +
                "use Stop to cut them off.",
        )
        Section(
            "What the server sees",
            "While you broadcast, the server holds your latest coordinate, timestamp and " +
                "display name, keyed by your sharing ID, and hands it to anyone who knows that " +
                "ID. It keeps only the most recent value and forgets it a couple of minutes " +
                "after you stop. Treat the QR code as a secret: anyone who scans it can see " +
                "where you are while you broadcast.",
        )
    }
}

/**
 * A warning card shown while the app is still subject to battery optimisation, which on many
 * devices kills long recordings with the screen off.
 *
 * @param onRequest open the system exemption dialog.
 */
@Composable
private fun BatteryCard(onRequest: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Battery optimisation",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "This device may stop long recordings while the screen is off. Excluding the " +
                    "app from battery optimisation keeps recording and sharing running.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRequest) { Text("Exclude from optimisation") }
        }
    }
}

/**
 * The backup/restore controls: everything in the database to one file, and back.
 *
 * @param onBackup start the backup export.
 * @param onRestore pick a backup file to import.
 */
@Composable
private fun BackupCard(onBackup: () -> Unit, onRestore: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Backup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Save every track to a single file, or bring tracks back from a backup. " +
                    "Restoring skips tracks that are already present.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onBackup) { Text("Back up") }
                OutlinedButton(onClick = onRestore) { Text("Restore") }
            }
        }
    }
}

/**
 * The offline-map control: status plus a download / cancel / delete button.
 *
 * @param state the current offline-map state.
 * @param onDownload start or resume the download.
 * @param onCancel cancel an in-progress download.
 * @param onDelete remove the downloaded map.
 */
@Composable
private fun OfflineMapCard(
    state: OfflineMapState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Offline map (Belgium)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            when (state) {
                is OfflineMapState.Absent -> {
                    Text("Not downloaded — the maps use online tiles.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onDownload) { Text("Download") }
                }
                is OfflineMapState.Downloading -> {
                    Text(
                        if (state.percent in 0..100) "Downloading… ${state.percent}%" else "Starting…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.percent in 0..100) {
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
                is OfflineMapState.Ready -> {
                    Text(
                        "Ready — %.0f MB. Maps render offline.".format(state.bytes / 1_048_576.0),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onDelete) { Text("Delete") }
                }
                is OfflineMapState.Failed -> {
                    Text("Download failed. Check the connection and try again.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onDownload) { Text("Retry") }
                }
            }
        }
    }
}

/**
 * A titled block of manual text.
 *
 * @param title the heading.
 * @param body the paragraph.
 */
@Composable
private fun Section(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
