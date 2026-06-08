package com.example.alarm.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "travel_alarms")
data class TravelAlarm(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val label: String, // e.g. "Delhi Junction", "Office Bus Stop..."
    val category: String, // "HOME", "OFFICE", "STATION", "BUS_STOP", "CUSTOM"
    val latitude: Double,
    val longitude: Double,
    val radiusKm: Double = 2.0, // default 2 km
    val active: Boolean = true,
    val ttsEnabled: Boolean = true,
    val flashEnabled: Boolean = false,
    val vibrationEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "My Location") val startLabel: String = "My Location",
    val startLatitude: Double? = null,
    val startLongitude: Double? = null,
    val createdAt: Long = System.currentTimeMillis()
)
