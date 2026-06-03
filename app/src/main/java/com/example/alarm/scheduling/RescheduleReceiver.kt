package com.example.alarm.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.alarm.data.AppDatabase
import com.example.alarm.data.SunAlarmResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
                        scheduler.schedule(updated)
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
