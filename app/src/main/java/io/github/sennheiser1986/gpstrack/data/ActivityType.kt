package io.github.sennheiser1986.gpstrack.data

/**
 * What kind of outing a track records. Stored on the track as the enum name; walking and
 * running present their average as a pace (min/km), wheels as a speed (km/h).
 *
 * @property displayName reader-visible name.
 * @property symbol small glyph used in lists and chips.
 * @property usesPace true when the natural unit for the average is min/km rather than km/h.
 */
enum class ActivityType(
    val displayName: String,
    val symbol: String,
    val usesPace: Boolean,
) {
    WALK("Walk", "🚶", true),
    RUN("Run", "🏃", true),
    BIKE("Bike", "🚴", false),
    DRIVE("Drive", "🚗", false),
    ;

    companion object {
        /**
         * Parses a stored enum name, tolerating unknown or missing values.
         *
         * @param name the stored name, or null.
         * @return the matching type, or [WALK].
         */
        fun from(name: String?): ActivityType = entries.firstOrNull { it.name == name } ?: WALK
    }
}
