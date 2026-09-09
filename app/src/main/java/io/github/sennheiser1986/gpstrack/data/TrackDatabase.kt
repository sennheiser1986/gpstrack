package io.github.sennheiser1986.gpstrack.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The on-device SQLite database that holds every recorded track and its fixes. This is the
 * "internal database" the app is built around; nothing about a track leaves the device unless
 * the reader exports it.
 */
@Database(
    entities = [Track::class, TrackPoint::class],
    version = 1,
    exportSchema = false,
)
abstract class TrackDatabase : RoomDatabase() {

    /** Data access object for tracks and their points. */
    abstract fun trackDao(): TrackDao

    companion object {
        @Volatile
        private var instance: TrackDatabase? = null

        /**
         * Returns the process-wide database, creating it on first use.
         *
         * @param context any context; the application context is used internally.
         * @return the shared [TrackDatabase].
         */
        fun get(context: Context): TrackDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TrackDatabase::class.java,
                    "track-recorder.db",
                ).build().also { instance = it }
            }
    }
}
