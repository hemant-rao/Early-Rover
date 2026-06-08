package com.example.alarm.data

import kotlinx.coroutines.flow.Flow

class AlarmRepository(
    private val alarmDao: AlarmDao,
    private val travelAlarmDao: TravelAlarmDao
) {
    val allAlarms: Flow<List<Alarm>> = alarmDao.getAllAlarmsFlow()
    val allTravelAlarms: Flow<List<TravelAlarm>> = travelAlarmDao.getAllTravelAlarmsFlow()

    suspend fun getTravelAlarmById(id: Int): TravelAlarm? {
        return travelAlarmDao.getTravelAlarmById(id)
    }

    suspend fun getActiveTravelAlarms(): List<TravelAlarm> {
        return travelAlarmDao.getActiveTravelAlarms()
    }

    suspend fun insertTravelAlarm(alarm: TravelAlarm): Long {
        return travelAlarmDao.insertTravelAlarm(alarm)
    }

    suspend fun updateTravelAlarm(alarm: TravelAlarm) {
        travelAlarmDao.updateTravelAlarm(alarm)
    }

    suspend fun deleteTravelAlarm(alarm: TravelAlarm) {
        travelAlarmDao.deleteTravelAlarm(alarm)
    }

    suspend fun deleteTravelAlarmById(id: Int) {
        travelAlarmDao.deleteTravelAlarmById(id)
    }

    suspend fun deleteAllTravelAlarms() {
        travelAlarmDao.deleteAllTravelAlarms()
    }

    suspend fun getAlarmById(id: Int): Alarm? {
        return alarmDao.getAlarmById(id)
    }

    suspend fun getActiveAlarms(): List<Alarm> {
        return alarmDao.getActiveAlarms()
    }

    suspend fun insertAlarm(alarm: Alarm): Long {
        return alarmDao.insertAlarm(alarm)
    }

    suspend fun updateAlarm(alarm: Alarm) {
        alarmDao.updateAlarm(alarm)
    }

    suspend fun deleteAlarm(alarm: Alarm) {
        alarmDao.deleteAlarm(alarm)
    }

    suspend fun deleteAlarmById(id: Int) {
        alarmDao.deleteAlarmById(id)
    }

    suspend fun deleteAllAlarms() {
        alarmDao.deleteAllAlarms()
    }
}
