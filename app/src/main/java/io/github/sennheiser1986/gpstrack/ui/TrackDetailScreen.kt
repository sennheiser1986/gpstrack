package io.github.sennheiser1986.gpstrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sennheiser1986.gpstrack.data.ExportFormat
import io.github.sennheiser1986.gpstrack.data.Track
import io.github.sennheiser1986.gpstrack.data.TrackPoint
import io.github.sennheiser1986.gpstrack.data.TrackStatistics

/**
 * The detail screen for one track: its path on the map, the derived figures, rename and delete
 * controls, and one export button per file format.
 *
 * @param track the track being shown.
 * @param points the track's fixes in recording order.
 * @param statistics figures derived from [points].
 * @param onRename invoked with a new name.
 * @param onDelete invoked when the reader confirms deletion.
 * @param onExport invoked with the chosen format.
 * @param offlineMapReady whether the downloaded offline map should be used.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackDetailScreen(
    track: Track,
    points: List<TrackPoint>,
    statistics: TrackStatistics,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    offlineMapReady: Boolean = false,
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var focusRequest by remember { mutableStateOf<MapFocusRequest?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        val path = points.map { it.toLatLon() }
        val markers = buildList {
            path.firstOrNull()?.let { add(MapMarker.start(it)) }
            if (path.size > 1) path.lastOrNull()?.let { add(MapMarker.end(it)) }
        }
        TrackMapView(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            path = path,
            markers = markers,
            fallbackCenter = path.firstOrNull(),
            focusRequest = focusRequest,
            offlineReady = offlineMapReady,
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            if (markers.isNotEmpty()) {
                MapLegend(
                    entries = markers.map { LegendEntry(it.label, it.color, position = it.position) },
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    onEntryClick = { entry ->
                        entry.position?.let { focusRequest = MapFocusRequest(it) }
                    },
                )
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    track.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showRenameDialog = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename")
                }
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
            Text(formatDateTime(track.startedAtMillis), style = MaterialTheme.typography.bodyMedium)

            Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCell("Distance", formatDistance(statistics.distanceMeters))
                    StatCell("Moving time", formatDuration(statistics.movingDurationMillis))
                    StatCell("Total time", formatDuration(statistics.totalDurationMillis))
                    StatCell("Climb", "%.0f m".format(statistics.elevationGainMeters))
                    StatCell("Avg speed", formatSpeed(statistics.averageMovingSpeedMetersPerSecond))
                    StatCell("Max speed", formatSpeed(statistics.maxSpeedMetersPerSecond))
                    StatCell("Points", track.pointCount.takeIf { it > 0 }?.toString() ?: points.size.toString())
                }
            }

            Text(
                "Export",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ExportFormat.entries.forEach { format ->
                    OutlinedButton(onClick = { onExport(format) }) { Text(format.displayName) }
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            initialName = track.name,
            onConfirm = {
                onRename(it)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this track?") },
            text = { Text("“${track.name}” and its ${points.size} points will be removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * A labelled figure in the statistics grid.
 *
 * @param label the caption.
 * @param value the figure.
 */
@Composable
private fun StatCell(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A single-field dialog for renaming a track.
 *
 * @param initialName the current name.
 * @param onConfirm invoked with the trimmed new name.
 * @param onDismiss invoked when the dialog is dismissed without confirming.
 */
@Composable
private fun RenameDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename track") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
