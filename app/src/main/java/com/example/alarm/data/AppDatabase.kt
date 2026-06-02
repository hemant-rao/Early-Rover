package com.example.alarm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Alarm::class, TravelAlarm::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun travelAlarmDao(): TravelAlarmDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 -> v2 added the travel_alarms table (the alarms table was unchanged). Preserve the
         * user's existing alarms across this upgrade instead of wiping them. Columns/types mirror
         * the [TravelAlarm] entity exactly so Room's post-migration schema check passes.
         *
         * NOTE: verify on a real upgrade in the external build — Room can't be exercised here.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `travel_alarms` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, " +
                        "`radiusKm` REAL NOT NULL, " +
                        "`active` INTEGER NOT NULL, " +
                        "`ttsEnabled` INTEGER NOT NULL, " +
                        "`flashEnabled` INTEGER NOT NULL, " +
                        "`vibrationEnabled` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sun_alarm_database"
                )
                .addMigrations(MIGRATION_1_2)
                // Safety net for any other (unforeseen) version jump only.
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
