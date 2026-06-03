package com.example.alarm.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY createdAt DESC")
    fun getAllAlarmsFlow(): Flow<List<Alarm>>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getAlarmById(id: Int): Alarm?

    @Query("SELECT * FROM alarms WHERE active = 1")
    suspend fun getActiveAlarms(): List<Alarm>

    @Query("SELECT * FROM alarms WHERE active = 1 AND locationName = :locationName")
    suspend fun getActiveAlarmsForLocation(locationName: String): List<Alarm>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarm(alarm: Alarm): Long

    @Update
    suspend fun updateAlarm(alarm: Alarm)

    @Delete
    suspend fun deleteAlarm(alarm: Alarm)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteAlarmById(id: Int)
}
