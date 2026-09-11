package io.github.sennheiser1986.gpstrack.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sennheiser1986.gpstrack.data.TrackPoint
import io.github.sennheiser1986.gpstrack.data.haversineMeters

/**
 * One profile series: y values sampled along the track's cumulative distance.
 *
 * @property distancesMeters x positions (cumulative ground distance), same length as [values].
 * @property values the y values.
 */
private class ProfileSeries(
    val distancesMeters: DoubleArray,
    val values: DoubleArray,
)

/**
 * The elevation and speed profiles of a finished track, drawn over distance. Either chart is
 * omitted when the points carry no usable data for it (e.g. no altitudes).
 *
 * @param points the track's fixes in recording order.
 * @param modifier layout modifier for the column of charts.
 */
@Composable
fun TrackProfiles(points: List<TrackPoint>, modifier: Modifier = Modifier) {
    val profiles = remember(points) { buildProfiles(points) }
    Column(modifier) {
        profiles.elevation?.let { series ->
            ProfileChart(
                title = "Elevation",
                series = series,
                unitFormatter = { "%.0f m".format(it) },
                lineColor = MaterialTheme.colorScheme.tertiary,
            )
        }
        profiles.speed?.let { series ->
            ProfileChart(
                title = "Speed",
                series = series,
                unitFormatter = { "%.1f km/h".format(it * 3.6) },
                lineColor = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** The two optional series derivable from a point list. */
private class Profiles(val elevation: ProfileSeries?, val speed: ProfileSeries?)

/**
 * Derives the elevation and smoothed-speed series from the fixes.
 *
 * @param points the fixes in recording order.
 * @return the derivable series; each null when the data is missing or trivial.
 */
private fun buildProfiles(points: List<TrackPoint>): Profiles {
    if (points.size < 2) return Profiles(null, null)

    val distances = DoubleArray(points.size)
    for (index in 1 until points.size) {
        distances[index] = distances[index - 1] + haversineMeters(
            points[index - 1].latitude, points[index - 1].longitude,
            points[index].latitude, points[index].longitude,
        )
    }
    if (distances.last() <= 0.0) return Profiles(null, null)

    val altitudes = points.map { it.altitudeMeters }
    val elevation = if (altitudes.count { it != null } >= 2) {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        altitudes.forEachIndexed { index, altitude ->
            if (altitude != null) {
                xs += distances[index]
                ys += altitude
            }
        }
        ProfileSeries(xs.toDoubleArray(), ys.toDoubleArray())
    } else {
        null
    }

    // Per-leg speed, lightly smoothed over three legs so single-fix jitter does not dominate.
    val legSpeeds = DoubleArray(points.size - 1)
    for (index in 1 until points.size) {
        val millis = points[index].timestampMillis - points[index - 1].timestampMillis
        val meters = distances[index] - distances[index - 1]
        legSpeeds[index - 1] = if (millis > 0) meters / (millis / 1000.0) else 0.0
    }
    val smoothed = DoubleArray(legSpeeds.size) { index ->
        val from = maxOf(0, index - 1)
        val to = minOf(legSpeeds.lastIndex, index + 1)
        var sum = 0.0
        for (i in from..to) sum += legSpeeds[i]
        sum / (to - from + 1)
    }
    val speed = if (smoothed.isNotEmpty()) {
        ProfileSeries(DoubleArray(smoothed.size) { distances[it + 1] }, smoothed)
    } else {
        null
    }

    return Profiles(elevation, speed)
}

/**
 * One titled line chart: the series drawn over distance with a soft fill, min/max labels on the
 * left and the total distance on the right.
 *
 * @param title the chart caption.
 * @param series the data to draw.
 * @param unitFormatter formats a y value for the min/max labels.
 * @param lineColor the stroke colour.
 */
@Composable
private fun ProfileChart(
    title: String,
    series: ProfileSeries,
    unitFormatter: (Double) -> String,
    lineColor: Color,
) {
    val minValue = series.values.min()
    val maxValue = series.values.max()
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Column(Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${unitFormatter(minValue)} – ${unitFormatter(maxValue)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .padding(top = 4.dp),
        ) {
            val totalDistance = series.distancesMeters.last().coerceAtLeast(1.0)
            // A flat series still needs a visible line: give it a token vertical span.
            val span = (maxValue - minValue).takeIf { it > 1e-6 } ?: 1.0
            val floor = if (maxValue - minValue > 1e-6) minValue else minValue - 0.5

            fun toOffset(index: Int): Offset {
                val x = (series.distancesMeters[index] / totalDistance * size.width).toFloat()
                val y = (size.height * (1f - ((series.values[index] - floor) / span).toFloat()))
                return Offset(x, y.coerceIn(0f, size.height))
            }

            val line = Path()
            val fill = Path()
            for (index in series.values.indices) {
                val offset = toOffset(index)
                if (index == 0) {
                    line.moveTo(offset.x, offset.y)
                    fill.moveTo(offset.x, size.height)
                    fill.lineTo(offset.x, offset.y)
                } else {
                    line.lineTo(offset.x, offset.y)
                    fill.lineTo(offset.x, offset.y)
                }
            }
            fill.lineTo(size.width, size.height)
            fill.close()

            drawPath(fill, lineColor.copy(alpha = 0.15f))
            drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            drawPath(line, lineColor, style = Stroke(width = 2.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth()) {
            Text("0", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(
                formatDistance(series.distancesMeters.last()),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
