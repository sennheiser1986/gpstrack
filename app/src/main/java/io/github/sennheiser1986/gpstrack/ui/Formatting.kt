package io.github.sennheiser1986.gpstrack.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())

/**
 * Formats a distance for display, switching from metres to kilometres at 1 km.
 *
 * @param meters the distance in metres.
 * @return e.g. "840 m" or "12.4 km".
 */
fun formatDistance(meters: Double): String =
    if (meters < 1_000) "%.0f m".format(meters) else "%.2f km".format(meters / 1_000)

/**
 * Formats a duration as hours and minutes, or minutes and seconds under an hour.
 *
 * @param millis the duration in milliseconds.
 * @return e.g. "1:04:12" or "7:32".
 */
fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Formats a speed in metres per second as km/h.
 *
 * @param metersPerSecond the speed.
 * @return e.g. "14.2 km/h".
 */
fun formatSpeed(metersPerSecond: Double): String = "%.1f km/h".format(metersPerSecond * 3.6)

/**
 * Formats a speed as a pace in minutes per kilometre, the natural unit for walking and running.
 *
 * @param metersPerSecond the speed.
 * @return e.g. "5:42 /km", or "–" for a speed too slow to express.
 */
fun formatPace(metersPerSecond: Double): String {
    if (metersPerSecond < 0.1) return "–"
    val secondsPerKm = (1_000.0 / metersPerSecond).toLong()
    return "%d:%02d /km".format(secondsPerKm / 60, secondsPerKm % 60)
}

/**
 * Formats a wall-clock instant using the device locale.
 *
 * @param epochMillis milliseconds since the Unix epoch.
 * @return e.g. "18 May 2024, 07:15".
 */
fun formatDateTime(epochMillis: Long): String =
    dateTimeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/**
 * Formats how long ago an instant was, coarsely, for "last seen" labels.
 *
 * @param epochMillis milliseconds since the Unix epoch; 0 or negative reads as "never".
 * @param nowMillis the current time in the same units.
 * @return e.g. "just now", "3 min ago", "2 h ago", "never".
 */
fun formatAge(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0) return "never"
    val seconds = (nowMillis - epochMillis) / 1_000
    return when {
        seconds < 15 -> "just now"
        seconds < 90 -> "1 min ago"
        seconds < 3_600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3_600} h ago"
        else -> "${seconds / 86_400} d ago"
    }
}
