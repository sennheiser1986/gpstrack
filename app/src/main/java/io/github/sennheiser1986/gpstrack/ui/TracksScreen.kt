package io.github.sennheiser1986.gpstrack.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sennheiser1986.gpstrack.data.Track

/**
 * The Tracks tab: the list of everything in the database, newest first. Selecting a row opens
 * its detail screen.
 *
 * @param tracks the recorded tracks.
 * @param onOpen invoked with a track id when a row is tapped.
 */
@Composable
fun TracksScreen(
    tracks: List<Track>,
    onOpen: (Long) -> Unit,
) {
    if (tracks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No tracks yet. Start one from the Record tab.",
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
        items(tracks, key = { it.id }) { track ->
            TrackRow(track = track, onClick = { onOpen(track.id) })
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
                track.name,
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
