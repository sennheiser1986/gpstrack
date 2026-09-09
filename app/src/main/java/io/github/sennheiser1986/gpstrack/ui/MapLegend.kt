package io.github.sennheiser1986.gpstrack.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import io.github.sennheiser1986.gpstrack.data.LatLon

/**
 * One line of a map legend: a coloured dot matching a map marker, a name, and an optional
 * trailing note such as a "last seen" time.
 *
 * @property label the marker's name.
 * @property color the marker's base ARGB colour (from [MarkerPalette]).
 * @property trailing right-aligned note, or null.
 * @property position the matching marker's location; when set, the row can be tapped to centre
 *   the map on it.
 */
data class LegendEntry(
    val label: String,
    val color: Int,
    val trailing: String? = null,
    val position: LatLon? = null,
)

/**
 * A key placed under a map: one [LegendEntry] per row, each showing the same two-tone
 * concentric dot used for that marker on the map, so a reader can match a dot to a name without
 * any text drawn over the map itself.
 *
 * @param entries the rows to show, in order; nothing is drawn when empty.
 * @param modifier layout modifier for the legend column.
 * @param onEntryClick invoked when a row with a [LegendEntry.position] is tapped; rows without a
 *   position, or when this is null, are not tappable.
 */
@Composable
fun MapLegend(
    entries: List<LegendEntry>,
    modifier: Modifier = Modifier,
    onEntryClick: ((LegendEntry) -> Unit)? = null,
) {
    if (entries.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { entry ->
            val tappable = onEntryClick != null && entry.position != null
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (tappable) {
                            Modifier
                                .clickable { onEntryClick!!(entry) }
                                .heightIn(min = 32.dp)
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConcentricDot(entry.color)
                Text(
                    entry.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                )
                entry.trailing?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * The legend swatch: a small copy of the map's concentric-circle symbol.
 *
 * @param baseColor the marker's base ARGB colour.
 */
@Composable
private fun ConcentricDot(baseColor: Int) {
    val base = Color(baseColor)
    val outer = Color(blendWithWhite(baseColor, 0.62f))
    Canvas(Modifier.size(18.dp)) {
        val radius = size.minDimension / 2f
        drawCircle(outer, radius = radius)
        drawCircle(Color.White, radius = radius * 0.5f)
        drawCircle(base, radius = radius * 0.36f)
    }
}
