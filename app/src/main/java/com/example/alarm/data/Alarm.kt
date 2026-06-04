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
    // ISO yyyy-MM-dd of a single upcoming occurrence to SKIP ("skip today only" for a repeating
    // alarm). Empty = nothing skipped. The resolver advances past this date once, then it is ignored
    // again on subsequent occurrences. A date strictly before today is treated as stale (ignored).
    val skipDate: String = "",
    val active: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val snoozeEnabled: Boolean = true,
    val snoozeMinutes: Int = 5,
    val volume: Int = 80,
    val ringtoneUri: String = "", // empty for default alarm sound
    val ringAtExactAlso: Boolean = false,
    // Location this SUNRISE/SUNSET alarm belongs to. Each alarm is self-contained: its hour/minute
    // are always derived from THESE coordinates, never from whatever location is currently active.
    // This is what keeps each city's sun-alarm independent (switching cities can't overwrite it).
    // Unused for CUSTOM alarms. (0.0/0.0/"" = legacy alarm created before per-location binding.)
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timezoneOffset: Double = 0.0,
    val locationName: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun isRepeating(): Boolean = repeatDays.isNotEmpty()

    /** A sun-based alarm whose location was never recorded (created before per-location binding). */
    fun hasLocation(): Boolean = !(latitude == 0.0 && longitude == 0.0 && locationName.isEmpty())
    
    fun getRepeatDaysList(): List<Int> {
        if (repeatDays.isEmpty()) return emptyList()
        return repeatDays.split(",").mapNotNull { it.trim().toIntOrNull() }
    }
}
