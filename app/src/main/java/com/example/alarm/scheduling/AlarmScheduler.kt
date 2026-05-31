package com.example.alarm.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.alarm.data.Alarm
import java.util.Calendar

class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(alarm: Alarm) {
        if (!alarm.active) {
            cancel(alarm)
            return
        }

        val targetCal = getNextOccurrence(alarm)
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("ALARM_ID", alarm.id)
            putExtra("ALARM_TITLE", alarm.title)
            putExtra("ALARM_TYPE", alarm.alarmType)
        }

        // Must be unique for each alarm to prevent overwriting
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = targetCal.timeInMillis

        // Use standard AlarmClockInfo to ensure reliable firing even during Doze/Standby
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val showIntent = Intent(context, Class.forName("com.example.MainActivity")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val showPendingIntent = PendingIntent.getActivity(
                context,
                alarm.id,
                showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val info = AlarmManager.AlarmClockInfo(triggerTime, showPendingIntent)
            try {
                alarmManager.setAlarmClock(info, pendingIntent)
                Log.d("AlarmScheduler", "Alarm scheduled for ${targetCal.time} with id: ${alarm.id}")
            } catch (e: SecurityException) {
                // Fallback if SCHEDULE_EXACT_ALARM is missing
                scheduleFallback(triggerTime, pendingIntent)
            }
        } else {
            scheduleFallback(triggerTime, pendingIntent)
        }
    }

    private fun scheduleFallback(triggerTime: Long, pendingIntent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }
    }

    fun cancel(alarm: Alarm) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d("AlarmScheduler", "Alarm canceled with id: ${alarm.id}")
        }
    }

    companion object {
        fun getNextOccurrence(alarm: Alarm): Calendar {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, alarm.hour)
                set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            
            val repeatDays = alarm.getRepeatDaysList()
            if (repeatDays.isEmpty()) {
                if (target.timeInMillis <= now.timeInMillis) {
                    target.add(Calendar.DAY_OF_YEAR, 1)
                }
                return target
            }
            
            // Repeating alarm - find nearest upcoming day
            var daysToAdd = 0
            while (daysToAdd < 8) {
                val testCal = Calendar.getInstance().apply {
                    timeInMillis = target.timeInMillis
                    add(Calendar.DAY_OF_YEAR, daysToAdd)
                }
                
                val calDow = testCal.get(Calendar.DAY_OF_WEEK)
                val ourDow = when (calDow) {
                    Calendar.MONDAY -> 1
                    Calendar.TUESDAY -> 2
                    Calendar.WEDNESDAY -> 3
                    Calendar.THURSDAY -> 4
                    Calendar.FRIDAY -> 5
                    Calendar.SATURDAY -> 6
                    Calendar.SUNDAY -> 7
                    else -> 1
                }
                
                if (repeatDays.contains(ourDow)) {
                    if (daysToAdd == 0 && testCal.timeInMillis <= now.timeInMillis) {
                        // Today matches but target time already elapsed today
                    } else {
                        return testCal
                    }
                }
                daysToAdd++
            }
            return target
        }
    }
}
