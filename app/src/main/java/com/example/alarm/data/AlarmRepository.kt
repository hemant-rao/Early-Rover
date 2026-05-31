package com.example.alarm.data

import kotlinx.coroutines.flow.Flow

class AlarmRepository(private val alarmDao: AlarmDao) {
    val allAlarms: Flow<List<Alarm>> = alarmDao.getAllAlarmsFlow()

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
}
