package com.example.alarm.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.alarm.data.AppDatabase
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
                        scheduler.schedule(alarm)
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
