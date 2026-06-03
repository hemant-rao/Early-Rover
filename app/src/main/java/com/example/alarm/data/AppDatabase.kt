package com.example.alarm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Alarm::class, TravelAlarm::class], version = 5, exportSchema = false)
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

        /**
         * v2 -> v3 adds start location details (startLabel, startLatitude, startLongitude) to
         * support the new "FROM ➔ TO" travel routing and safeguarding.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `travel_alarms` ADD COLUMN `startLabel` TEXT NOT NULL DEFAULT 'My Location'")
                db.execSQL("ALTER TABLE `travel_alarms` ADD COLUMN `startLatitude` REAL")
                db.execSQL("ALTER TABLE `travel_alarms` ADD COLUMN `startLongitude` REAL")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `alarms` ADD COLUMN `ringAtExactAlso` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `alarms` ADD COLUMN `locationName` TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sun_alarm_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                // Safety net for any other (unforeseen) version jump only.
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
