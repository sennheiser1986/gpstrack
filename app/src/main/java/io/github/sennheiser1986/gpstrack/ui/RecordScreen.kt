package io.github.sennheiser1986.gpstrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sennheiser1986.gpstrack.data.ActivityType
import io.github.sennheiser1986.gpstrack.data.LatLon
import kotlinx.coroutines.delay

/**
 * The Record tab: a live map of the current trail with an activity-type picker, a start/stop
 * control and running distance, total time and moving time.
 *
 * @param isRecording whether a track is currently being recorded.
 * @param liveTrail the fixes recorded so far.
 * @param currentLocation the latest known position, for centring before recording starts.
 * @param liveDistanceMeters distance of [liveTrail] in metres.
 * @param liveMovingMillis time spent actually moving, for the auto-paused clock.
 * @param startedAtMillis start time of the recording, or null.
 * @param activityType the type the next recording will be stored as.
 * @param onActivityTypeChange invoked when the reader picks a different type.
 * @param onStart invoked with an optional name when the reader starts recording.
 * @param onStop invoked when the reader stops recording.
 * @param offlineMapReady whether the downloaded offline map should be used.
 */
@Composable
fun RecordScreen(
    isRecording: Boolean,
    liveTrail: List<LatLon>,
    currentLocation: LatLon?,
    liveDistanceMeters: Double,
    liveMovingMillis: Long,
    startedAtMillis: Long?,
    activityType: ActivityType,
    onActivityTypeChange: (ActivityType) -> Unit,
    onStart: (String?) -> Unit,
    onStop: () -> Unit,
    offlineMapReady: Boolean = false,
) {
    Column(Modifier.fillMaxSize()) {
        val markers = buildList {
            currentLocation?.let { add(MapMarker.me(it)) }
        }
        TrackMapView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            path = liveTrail,
            markers = markers,
            fallbackCenter = currentLocation,
            recenterTarget = currentLocation,
            offlineReady = offlineMapReady,
        )

        Card(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                var elapsedMillis by remember { mutableLongStateOf(0L) }
                LaunchedEffect(isRecording, startedAtMillis) {
                    while (isRecording && startedAtMillis != null) {
                        elapsedMillis = System.currentTimeMillis() - startedAtMillis
                        delay(1_000)
                    }
                    if (!isRecording) elapsedMillis = 0L
                }

                if (!isRecording) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActivityType.entries.forEach { type ->
                            FilterChip(
                                selected = type == activityType,
                                onClick = { onActivityTypeChange(type) },
                                label = { Text("${type.symbol} ${type.displayName}") },
                            )
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    LiveStat("Distance", formatDistance(liveDistanceMeters))
                    LiveStat("Time", formatDuration(elapsedMillis))
                    LiveStat("Moving", formatDuration(liveMovingMillis))
                }

                if (isRecording) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Text("  Stop recording")
                    }
                } else {
                    Button(onClick = { onStart(null) }) {
                        Icon(
                            Icons.Filled.FiberManualRecord,
                            contentDescription = null,
                            tint = Color(0xFFD9345A),
                        )
                        Text("  Start recording")
                    }
                }
            }
        }
    }
}

/**
 * A label above a value, used for the live figures.
 *
 * @param label the caption.
 * @param value the figure.
 */
@Composable
private fun LiveStat(label: String, value: String) {
    Box(contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
