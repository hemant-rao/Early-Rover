package com.example.alarm.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alarms")
data class Alarm(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val alarmType: String, // "SUNRISE", "SUNSET", "CUSTOM"
    val hour: Int, // Selected hour for CUSTOM, or calculated hour for SUNRISE/SUNSET
    val minute: Int, // Selected minute for CUSTOM, or calculated minute for SUNRISE/SUNSET
    val offsetMinutes: Int = 0, // negative for "before", positive for "after"
    val repeatDays: String = "", // e.g. "1,2,3,4,5" where 1=Mon, 2=Tue... 7=Sun. Empty means one-time.
    val active: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val snoozeEnabled: Boolean = true,
    val snoozeMinutes: Int = 5,
    val volume: Int = 80,
    val ringtoneUri: String = "", // empty for default alarm sound
    val ringAtExactAlso: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun isRepeating(): Boolean = repeatDays.isNotEmpty()
    
    fun getRepeatDaysList(): List<Int> {
        if (repeatDays.isEmpty()) return emptyList()
        return repeatDays.split(",").mapNotNull { it.trim().toIntOrNull() }
    }
}
