package io.github.sennheiser1986.gpstrack.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The on-device SQLite database that holds every recorded track and its fixes. This is the
 * "internal database" the app is built around; nothing about a track leaves the device unless
 * the reader exports it.
 */
@Database(
    entities = [Track::class, TrackPoint::class],
    version = 2,
    exportSchema = false,
)
abstract class TrackDatabase : RoomDatabase() {

    /** Data access object for tracks and their points. */
    abstract fun trackDao(): TrackDao

    companion object {
        @Volatile
        private var instance: TrackDatabase? = null

        /** v1 → v2: tracks gained an activity type; existing recordings become walks. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tracks ADD COLUMN activityType TEXT NOT NULL DEFAULT 'WALK'",
                )
            }
        }

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
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
