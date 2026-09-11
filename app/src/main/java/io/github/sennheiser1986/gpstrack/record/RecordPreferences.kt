package io.github.sennheiser1986.gpstrack.record

import android.content.Context
import io.github.sennheiser1986.gpstrack.data.ActivityType

/**
 * Persisted recording settings: currently only the activity type last chosen on the Record tab,
 * so the picker defaults to what the reader usually does.
 */
class RecordPreferences(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * Reads the last-used activity type.
     *
     * @return the stored type, or [ActivityType.WALK].
     */
    fun lastActivityType(): ActivityType =
        ActivityType.from(preferences.getString(KEY_ACTIVITY_TYPE, null))

    /**
     * Stores the activity type just used to start a recording.
     *
     * @param type the chosen type.
     */
    fun setLastActivityType(type: ActivityType) {
        preferences.edit().putString(KEY_ACTIVITY_TYPE, type.name).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "gpstrack_record"
        const val KEY_ACTIVITY_TYPE = "activity_type"
    }
}
