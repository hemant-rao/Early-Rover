package com.example.alarm.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.alarm.data.AppDatabase
import com.example.alarm.data.SunAlarmResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == Intent.ACTION_TIME_CHANGED || 
            action == Intent.ACTION_TIMEZONE_CHANGED) {
            
            Log.d("RescheduleReceiver", "Rescheduling system wake locks due to: $action")
            
            val pendingResult = goAsync()
            val database = AppDatabase.getDatabase(context)
            val scheduler = AlarmScheduler(context)
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val activeAlarms = database.alarmDao().getActiveAlarms()
                    for (alarm in activeAlarms) {
                        // Recompute SUNRISE/SUNSET alarms against each alarm's OWN stored location so a
                        // boot/timezone change (e.g. while travelling) still fires each one at its own
                        // city's sun time. A bound alarm uses its own location; one without a recorded
                        // location is left as-is (no active location is available in this receiver).
                        val updated = if (alarm.hasLocation()) {
                            // fallback is unused here: hasLocation() == true means recalibrate uses the
                            // alarm's own coordinates. Pass its own location to satisfy the signature.
                            val own = SunAlarmResolver.Location(
                                alarm.latitude, alarm.longitude, alarm.timezoneOffset, alarm.locationName
                            )
                            // No date passed: calibrate against "today" in the alarm's own timezone.
                            SunAlarmResolver.recalibrate(alarm, own)
                        } else {
                            alarm
                        }
                        if (updated !== alarm) database.alarmDao().updateAlarm(updated)

                        if (updated.isRepeating()) {
                            // Repeating alarms: keep the existing recalibrate + schedule behavior.
                            scheduler.schedule(updated)
                        } else {
                            // One-time alarms: a boot/time change may have happened AFTER the intended
                            // fire instant. Decide based on how stale it is rather than blindly
                            // re-arming 24h ahead (which would make it fire a day late).
                            val now = Instant.now()
                            val zone: ZoneId =
                                if ((updated.alarmType == "SUNRISE" || updated.alarmType == "SUNSET") && updated.hasLocation()) {
                                    SunAlarmResolver.zoneOf(updated.timezoneOffset)
                                } else {
                                    ZoneId.systemDefault()
                                }
                            val intendedToday = now.atZone(zone).toLocalDate()
                                .atTime(updated.hour, updated.minute)
                                .atZone(zone)
                                .toInstant()

                            if (intendedToday.isAfter(now)) {
                                // Still in the future today: schedule normally.
                                scheduler.schedule(updated)
                            } else {
                                val lateBy = now.toEpochMilli() - intendedToday.toEpochMilli()
                                val graceMillis = 2L * 60 * 60 * 1000 // ~2h grace window
                                if (lateBy <= graceMillis) {
                                    // Recently missed during downtime: fire it now.
                                    val serviceIntent = Intent(context, AlarmService::class.java).apply {
                                        putExtra("ALARM_ID", updated.id)
                                        putExtra("ALARM_TITLE", updated.title)
                                        putExtra("ALARM_TYPE", updated.alarmType)
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(serviceIntent)
                                    } else {
                                        context.startService(serviceIntent)
                                    }
                                    database.alarmDao().updateAlarm(updated.copy(active = false))
                                } else {
                                    // Past the grace window: mark missed instead of firing a day late.
                                    database.alarmDao().updateAlarm(updated.copy(active = false))
                                }
                            }
                        }
                    }
                    Log.d("RescheduleReceiver", "Successfully re-calibrated ${activeAlarms.size} active alarms.")
                } catch (e: Exception) {
                    Log.e("RescheduleReceiver", "Failed reschedule processing", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
