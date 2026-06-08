package com.example.alarm.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TravelAlarmDao {
    @Query("SELECT * FROM travel_alarms ORDER BY createdAt DESC")
    fun getAllTravelAlarmsFlow(): Flow<List<TravelAlarm>>

    @Query("SELECT * FROM travel_alarms WHERE active = 1")
    suspend fun getActiveTravelAlarms(): List<TravelAlarm>

    @Query("SELECT * FROM travel_alarms WHERE id = :id")
    suspend fun getTravelAlarmById(id: Int): TravelAlarm?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTravelAlarm(alarm: TravelAlarm): Long

    @Update
    suspend fun updateTravelAlarm(alarm: TravelAlarm)

    @Delete
    suspend fun deleteTravelAlarm(alarm: TravelAlarm)

    @Query("DELETE FROM travel_alarms WHERE id = :id")
    suspend fun deleteTravelAlarmById(id: Int)
}
