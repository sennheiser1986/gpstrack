package io.github.sennheiser1986.gpstrack.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.sennheiser1986.gpstrack.map.CatalogEntry
import io.github.sennheiser1986.gpstrack.map.CatalogState
import io.github.sennheiser1986.gpstrack.map.OfflineDownload
import io.github.sennheiser1986.gpstrack.map.OfflineRegion

/**
 * The Manual tab. It leads with the offline-maps manager and then all the caveats and
 * background notes that do not belong under the controls on the other screens.
 *
 * @param offlineRegions the installed offline regions.
 * @param offlineDownloads region downloads in progress or failed.
 * @param catalog what the region picker is showing.
 * @param onOpenCatalog open or navigate the region picker to a catalogue directory.
 * @param onCloseCatalog close the region picker.
 * @param onDownloadRegion start downloading a region from the catalogue.
 * @param onCancelDownload cancel one region's download.
 * @param onDeleteRegion delete one installed region.
 * @param batteryExempt whether the app is already excluded from battery optimisation.
 * @param onRequestBatteryExemption open the system dialog asking for the exclusion.
 * @param backgroundLocationGranted whether "Allow all the time" location access is held.
 * @param onRequestBackgroundLocation open the system screen where it can be granted.
 * @param onBackup export every track to a single backup file.
 * @param onRestore pick a backup file and import the tracks it holds.
 */
@Composable
fun ManualScreen(
    offlineRegions: List<OfflineRegion> = emptyList(),
    offlineDownloads: List<OfflineDownload> = emptyList(),
    catalog: CatalogState = CatalogState.Idle,
    onOpenCatalog: (String) -> Unit = {},
    onCloseCatalog: () -> Unit = {},
    onDownloadRegion: (String) -> Unit = {},
    onCancelDownload: (String) -> Unit = {},
    onDeleteRegion: (String) -> Unit = {},
    batteryExempt: Boolean = true,
    onRequestBatteryExemption: () -> Unit = {},
    backgroundLocationGranted: Boolean = true,
    onRequestBackgroundLocation: () -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
) {
    var showPicker by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!backgroundLocationGranted) {
            BackgroundLocationCard(onRequestBackgroundLocation)
        }

        if (!batteryExempt) {
            BatteryCard(onRequestBatteryExemption)
        }

        OfflineMapsCard(
            regions = offlineRegions,
            downloads = offlineDownloads,
            onAddRegion = {
                showPicker = true
                onOpenCatalog("")
            },
            onCancelDownload = onCancelDownload,
            onRetryDownload = onDownloadRegion,
            onDeleteRegion = onDeleteRegion,
        )

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
            "Offline maps",
            "The maps normally stream tiles from OpenStreetMap. \"Add region\" above browses " +
                "the MapsForge map server — continents, countries, and sub-regions for the " +
                "large ones, searchable by name and with file sizes shown (a country is " +
                "typically a few hundred MB). Any number of regions can be installed side by " +
                "side; while at least one is present, every map in the app renders offline. " +
                "Outside your downloaded regions the map is simply empty. Delete regions any " +
                "time to go back to online tiles.",
        )
        Section(
            "Sharing your location",
            "Each device has a permanent sharing ID shown as a QR code on the Share tab. It is " +
                "derived from the phone itself (hashed, never exposing the phone's identifiers), " +
                "so reinstalling the app keeps the same ID and the people following you keep " +
                "working; only a factory reset changes it. The sharing server only talks to " +
                "signed-in devices: enter a server account (created by the server's admin) " +
                "once under \"Server account\", and the device stays signed in. Turn on " +
                "\"Broadcast my location\" to publish your position to the sharing server. " +
                "Turn it off and nothing about your position leaves the device. The broadcast " +
                "also runs from an ongoing notification.",
        )
        Section(
            "Following other people",
            "On the Share tab, add someone three ways: \"Scan\" points the camera at their QR " +
                "code; \"Image\" reads a QR code out of a saved picture; or paste their sharing " +
                "ID (or a gpstrack:// link) into the field and press +. Each person you " +
                "follow appears on the Map tab with a switch; switch someone off to hide them " +
                "and stop fetching their position. Positions are fetched from the server's " +
                "cache only while the app is on screen — watching people keeps nothing " +
                "running in the background and costs no battery. The Map tab always shows " +
                "your own position too.",
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

    if (showPicker) {
        RegionPickerDialog(
            catalog = catalog,
            installedFileNames = offlineRegions.map { it.file.name }.toSet(),
            downloadingPaths = offlineDownloads.filterNot { it.failed }.map { it.regionPath }.toSet(),
            onNavigate = onOpenCatalog,
            onDownload = onDownloadRegion,
            onDismiss = {
                showPicker = false
                onCloseCatalog()
            },
        )
    }
}

/**
 * A warning card shown while "Allow all the time" location access is missing, without which
 * recording and broadcasting stop when the app leaves the screen.
 *
 * @param onRequest open the system screen where the grant can be made.
 */
@Composable
private fun BackgroundLocationCard(onRequest: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Location access",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Location is not set to \"Allow all the time\", so recording and broadcasting " +
                    "stop as soon as the app is in the background or the screen is off. On the " +
                    "next screen choose Permissions \u2192 Location \u2192 \"Allow all the " +
                    "time\".",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRequest) { Text("Open location settings") }
        }
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
                    "app from battery optimisation keeps recording and sharing running. If the " +
                    "app's settings page opens instead of a dialog, choose Battery \u2192 " +
                    "Unrestricted.",
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
 * The offline-maps manager: the installed regions, active downloads, and the entry point into
 * the region picker.
 *
 * @param regions installed regions.
 * @param downloads region downloads in progress or failed.
 * @param onAddRegion open the region picker.
 * @param onCancelDownload cancel one region's download.
 * @param onRetryDownload restart a failed region download.
 * @param onDeleteRegion delete one installed region.
 */
@Composable
private fun OfflineMapsCard(
    regions: List<OfflineRegion>,
    downloads: List<OfflineDownload>,
    onAddRegion: () -> Unit,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onDeleteRegion: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Offline maps",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (regions.isEmpty() && downloads.isEmpty()) {
                Text(
                    "No regions downloaded — the maps use online tiles.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            regions.forEach { region ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(region.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "%.0f MB".format(region.sizeBytes / 1_048_576.0),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton(onClick = { onDeleteRegion(region.file.name) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete ${region.displayName}")
                    }
                }
            }
            downloads.forEach { download ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(download.displayName, style = MaterialTheme.typography.bodyLarge)
                        if (download.failed) {
                            Text(
                                "Download failed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else if (download.percent in 0..100) {
                            LinearProgressIndicator(
                                progress = { download.percent / 100f },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp),
                            )
                        } else {
                            LinearProgressIndicator(
                                Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp),
                            )
                        }
                    }
                    if (download.failed) {
                        TextButton(onClick = { onRetryDownload(download.regionPath) }) { Text("Retry") }
                    } else {
                        IconButton(onClick = { onCancelDownload(download.regionPath) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel ${download.displayName}")
                        }
                    }
                }
            }
            Button(onClick = onAddRegion) { Text("Add region") }
        }
    }
}

/**
 * Full-screen region picker: browses the MapsForge catalogue directory by directory, with a
 * search field filtering the current listing and file sizes on every downloadable region.
 *
 * @param catalog what is currently loaded.
 * @param installedFileNames local file names of already-installed regions.
 * @param downloadingPaths region paths currently downloading.
 * @param onNavigate open a catalogue directory ("" for the continents).
 * @param onDownload start downloading a region.
 * @param onDismiss close the picker.
 */
@Composable
private fun RegionPickerDialog(
    catalog: CatalogState,
    installedFileNames: Set<String>,
    downloadingPaths: Set<String>,
    onNavigate: (String) -> Unit,
    onDownload: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val currentPath = when (catalog) {
        is CatalogState.Loading -> catalog.path
        is CatalogState.Error -> catalog.path
        is CatalogState.Loaded -> catalog.path
        CatalogState.Idle -> ""
    }
    // A new directory starts unfiltered.
    LaunchedEffect(currentPath) { query = "" }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (currentPath.isNotEmpty()) {
                        IconButton(onClick = {
                            val parent = currentPath.trim('/').substringBeforeLast('/', "")
                            onNavigate(if (parent == currentPath.trim('/')) "" else parent)
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                        }
                    }
                    Text(
                        if (currentPath.isEmpty()) "Choose a region" else currentPath.trim('/'),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )

                when (catalog) {
                    is CatalogState.Loading, CatalogState.Idle -> Box(
                        Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    is CatalogState.Error -> Column(
                        Modifier.fillMaxWidth().padding(top = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Could not load the list (${catalog.message}). Check the connection.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { onNavigate(catalog.path) }) { Text("Retry") }
                    }

                    is CatalogState.Loaded -> {
                        val filtered = catalog.entries.filter {
                            query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)
                        }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(filtered, key = { it.path }) { entry ->
                                CatalogRow(
                                    entry = entry,
                                    installed = !entry.isFolder &&
                                        entry.path.trim('/').replace("/", "__") in installedFileNames,
                                    downloading = entry.path in downloadingPaths,
                                    onClick = {
                                        if (entry.isFolder) onNavigate(entry.path) else onDownload(entry.path)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One row of the region picker.
 *
 * @param entry the catalogue entry.
 * @param installed true when this region is already downloaded.
 * @param downloading true when this region is currently downloading.
 * @param onClick open the folder, or start the download.
 */
@Composable
private fun CatalogRow(
    entry: CatalogEntry,
    installed: Boolean,
    downloading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !installed && !downloading, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.isFolder) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = if (entry.isFolder) 0.dp else 36.dp),
        )
        Text(
            when {
                installed -> "installed"
                downloading -> "downloading…"
                else -> entry.sizeLabel ?: ""
            },
            style = MaterialTheme.typography.bodySmall,
        )
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
