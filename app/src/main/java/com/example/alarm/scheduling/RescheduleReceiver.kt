package com.example.alarm.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
                            // Use the shared resolver (single source of truth the scheduler uses) so a
                            // negative-offset sun alarm whose intended instant rolls back to the previous
                            // calendar day is decided fire-vs-miss consistently with every other path.
                            val today = now.atZone(zone).toLocalDate()
                            val intended = SunAlarmResolver.fireDateTimeOn(updated, today)
                                .atZone(zone)
                                .toInstant()

                            if (intended.isAfter(now)) {
                                // Still in the future today: schedule normally.
                                scheduler.schedule(updated)
                            } else {
                                val lateBy = now.toEpochMilli() - intended.toEpochMilli()
                                val graceMillis = 2L * 60 * 60 * 1000 // ~2h grace window
                                if (lateBy <= graceMillis) {
                                    // Recently missed during downtime: fire it now — but NOT by starting the
                                    // mediaPlayback foreground service directly from this boot/time-change
                                    // broadcast (blocked on Android 12+; that FGS type is not boot-exempt and
                                    // would silently degrade to a non-ongoing notification). Instead schedule
                                    // an immediate exact alarm: AlarmManager-driven broadcasts get the
                                    // temporary FGS-start allowlist exemption, so AlarmReceiver's existing
                                    // startForegroundService -> AlarmService.startForeground path rings with
                                    // full audio + full-screen intent as usual.
                                    val fireIntent = Intent(context, AlarmReceiver::class.java).apply {
                                        putExtra("ALARM_ID", updated.id)
                                        putExtra("ALARM_TITLE", updated.title)
                                        putExtra("ALARM_TYPE", updated.alarmType)
                                        // Mirror AlarmScheduler.schedule()'s offset intent so the grace-window
                                        // ring renders the same per-alarm snooze action the user configured.
                                        putExtra("ALARM_SNOOZE_ENABLED", updated.snoozeEnabled)
                                    }
                                    val firePending = PendingIntent.getBroadcast(
                                        context,
                                        updated.id,
                                        fireIntent,
                                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                    )
                                    val alarmManager =
                                        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                                    alarmManager.setExactAndAllowWhileIdle(
                                        AlarmManager.RTC_WAKEUP,
                                        now.toEpochMilli() + 1000L,
                                        firePending
                                    )

                                    // Dual-ring sun alarm missed across boot: also re-fire the exact companion
                                    // that schedule() arms (negative request code, IS_EXACT_ALSO=true), but only
                                    // when that exact instant ALSO fell within the grace window. We fire "now"
                                    // directly rather than via schedule(), which would arm the NEXT occurrence.
                                    if (updated.ringAtExactAlso && updated.offsetMinutes != 0 &&
                                        (updated.alarmType == "SUNRISE" || updated.alarmType == "SUNSET")) {
                                        val exactAlarm = updated.copy(offsetMinutes = 0)
                                        val intendedExact = SunAlarmResolver.fireDateTimeOn(exactAlarm, today)
                                            .atZone(zone)
                                            .toInstant()
                                        val lateByExact = now.toEpochMilli() - intendedExact.toEpochMilli()
                                        if (lateByExact in 0..graceMillis) {
                                            val fireIntentExact = Intent(context, AlarmReceiver::class.java).apply {
                                                putExtra("ALARM_ID", updated.id)
                                                putExtra(
                                                    "ALARM_TITLE",
                                                    updated.title.ifEmpty {
                                                        if (updated.alarmType == "SUNRISE") "Sunrise Exact" else "Sunset Exact"
                                                    }
                                                )
                                                putExtra("ALARM_TYPE", updated.alarmType)
                                                putExtra("IS_EXACT_ALSO", true)
                                                putExtra("ALARM_SNOOZE_ENABLED", updated.snoozeEnabled)
                                            }
                                            val firePendingExact = PendingIntent.getBroadcast(
                                                context,
                                                -updated.id,
                                                fireIntentExact,
                                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                            )
                                            alarmManager.setExactAndAllowWhileIdle(
                                                AlarmManager.RTC_WAKEUP,
                                                now.toEpochMilli() + 1000L,
                                                firePendingExact
                                            )
                                        }
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
