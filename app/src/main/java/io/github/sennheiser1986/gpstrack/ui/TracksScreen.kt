package io.github.sennheiser1986.gpstrack.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sennheiser1986.gpstrack.data.ActivityType
import io.github.sennheiser1986.gpstrack.data.Track
import java.time.LocalDate
import java.time.ZoneId

/** How the track list can be ordered. */
private enum class TrackSort(val label: String) {
    NEWEST("Newest first"),
    LONGEST("Longest distance"),
    SLOWEST("Longest duration"),
}

/**
 * The Tracks tab: weekly/monthly totals, an activity filter, a sort menu, a GPX import button,
 * and the list itself. Selecting a row opens its detail screen.
 *
 * @param tracks the recorded tracks, newest first as delivered.
 * @param onOpen invoked with a track id when a row is tapped.
 * @param onImportGpx invoked when the reader wants to pick a GPX file to import.
 */
@Composable
fun TracksScreen(
    tracks: List<Track>,
    onOpen: (Long) -> Unit,
    onImportGpx: () -> Unit,
) {
    // Saved as names: enums (and null) do not round-trip through rememberSaveable's Bundle.
    var sortName by rememberSaveable { mutableStateOf(TrackSort.NEWEST.name) }
    var filterName by rememberSaveable { mutableStateOf("") }
    val sort = TrackSort.entries.firstOrNull { it.name == sortName } ?: TrackSort.NEWEST
    val filter = ActivityType.entries.firstOrNull { it.name == filterName }

    val filtered = tracks
        .filter { filter == null || it.activity == filter }
        .let { list ->
            when (sort) {
                TrackSort.NEWEST -> list.sortedByDescending { it.startedAtMillis }
                TrackSort.LONGEST -> list.sortedByDescending { it.distanceMeters }
                TrackSort.SLOWEST -> list.sortedByDescending { it.durationMillis }
            }
        }

    Column(Modifier.fillMaxSize()) {
        TotalsHeader(tracks)

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filter == null,
                    onClick = { filterName = "" },
                    label = { Text("All") },
                )
                ActivityType.entries.forEach { type ->
                    FilterChip(
                        selected = filter == type,
                        onClick = { filterName = if (filter == type) "" else type.name },
                        label = { Text(type.symbol) },
                    )
                }
            }
            SortMenu(sort) { sortName = it.name }
            IconButton(onClick = onImportGpx) {
                Icon(Icons.Filled.FileDownload, contentDescription = "Import GPX")
            }
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (tracks.isEmpty()) {
                        "No tracks yet. Start one from the Record tab."
                    } else {
                        "No ${filter?.displayName?.lowercase()} tracks."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(filtered, key = { it.id }) { track ->
                TrackRow(track = track, onClick = { onOpen(track.id) })
            }
        }
    }
}

/**
 * Sums of the finished tracks started this calendar week and this calendar month.
 *
 * @param tracks all tracks.
 */
@Composable
private fun TotalsHeader(tracks: List<Track>) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val monthStart = today.withDayOfMonth(1)
    val weekStartMillis = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
    val monthStartMillis = monthStart.atStartOfDay(zone).toInstant().toEpochMilli()

    val finished = tracks.filter { !it.isRecording }
    val week = finished.filter { it.startedAtMillis >= weekStartMillis }
    val month = finished.filter { it.startedAtMillis >= monthStartMillis }

    Card(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TotalCell("This week", week)
            TotalCell("This month", month)
        }
    }
}

/**
 * One totals figure: distance and time over a set of tracks.
 *
 * @param label the caption.
 * @param tracks the tracks summed.
 */
@Composable
private fun TotalCell(label: String, tracks: List<Track>) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            formatDistance(tracks.sumOf { it.distanceMeters }),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "${tracks.size} tracks · ${formatDuration(tracks.sumOf { it.durationMillis })}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The sort button and its dropdown.
 *
 * @param current the active sort.
 * @param onSelect invoked with the chosen sort.
 */
@Composable
private fun SortMenu(current: TrackSort, onSelect: (TrackSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.SwapVert, contentDescription = "Sort")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TrackSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(if (sort == current) "✓ ${sort.label}" else sort.label) },
                    onClick = {
                        onSelect(sort)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * One row of the track list.
 *
 * @param track the track to show.
 * @param onClick invoked when the row is tapped.
 */
@Composable
private fun TrackRow(track: Track, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "${track.activity.symbol} ${track.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                formatDateTime(track.startedAtMillis),
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (track.isRecording) {
                    Text("Recording…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(formatDistance(track.distanceMeters), style = MaterialTheme.typography.bodyMedium)
                    Text(formatDuration(track.durationMillis), style = MaterialTheme.typography.bodyMedium)
                    Text("${track.pointCount} pts", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
