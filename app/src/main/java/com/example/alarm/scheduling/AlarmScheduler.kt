package com.example.alarm.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.alarm.data.Alarm
import com.example.alarm.data.SunAlarmResolver
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar

class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(alarm: Alarm) {
        if (!alarm.active) {
            cancel(alarm)
            return
        }

        val targetCal = getNextOccurrence(alarm)
        val intentOffset = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("ALARM_ID", alarm.id)
            putExtra("ALARM_TITLE", alarm.title)
            putExtra("ALARM_TYPE", alarm.alarmType)
        }
        val pendingIntentOffset = PendingIntent.getBroadcast(
            context,
            alarm.id,
            intentOffset,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        schedulePendingIntent(targetCal.timeInMillis, pendingIntentOffset, alarm.id)
        
        // Also schedule exact time if requested and offset is present
        if (alarm.ringAtExactAlso && alarm.offsetMinutes != 0 && (alarm.alarmType == "SUNRISE" || alarm.alarmType == "SUNSET")) {
            val targetCalExact = targetCal.clone() as Calendar
            targetCalExact.add(Calendar.MINUTE, -alarm.offsetMinutes) // revert the offset
            
            val intentExact = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("ALARM_ID", alarm.id)
                putExtra("ALARM_TITLE", alarm.title.ifEmpty { if (alarm.alarmType == "SUNRISE") "Sunrise Exact" else "Sunset Exact" })
                putExtra("ALARM_TYPE", alarm.alarmType)
                putExtra("IS_EXACT_ALSO", true)
            }
            // Use negative ID to distinguish pending intent
            val exactReqId = -alarm.id 
            val pendingIntentExact = PendingIntent.getBroadcast(
                context,
                exactReqId,
                intentExact,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            schedulePendingIntent(targetCalExact.timeInMillis, pendingIntentExact, exactReqId)
        } else {
            // Cancel any old exact also alarm if it was unchecked
            val exactReqId = -alarm.id
            val intentExact = Intent(context, AlarmReceiver::class.java)
            val pendingOld = PendingIntent.getBroadcast(
                context,
                exactReqId,
                intentExact,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingOld != null) {
                alarmManager.cancel(pendingOld)
                pendingOld.cancel()
            }
        }
    }

    private fun schedulePendingIntent(triggerTime: Long, pendingIntent: PendingIntent, reqId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val showIntent = Intent(context, Class.forName("com.example.MainActivity")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val showPendingIntent = PendingIntent.getActivity(
                context,
                reqId,
                showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // On API 31+ the SCHEDULE_EXACT_ALARM permission is user-revocable. If it is not
            // granted, setAlarmClock()/setExact*() throw SecurityException, so skip straight to
            // the inexact path that requires no permission.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                Log.w("AlarmScheduler", "Exact alarm permission not granted; using inexact alarm for reqId: $reqId")
                scheduleInexact(triggerTime, pendingIntent)
                return
            }

            val info = AlarmManager.AlarmClockInfo(triggerTime, showPendingIntent)
            try {
                alarmManager.setAlarmClock(info, pendingIntent)
                Log.d("AlarmScheduler", "Alarm scheduled for triggerTime $triggerTime with reqId: $reqId")
            } catch (e: SecurityException) {
                scheduleFallback(triggerTime, pendingIntent)
            }
        } else {
            scheduleFallback(triggerTime, pendingIntent)
        }
    }

    private fun scheduleFallback(triggerTime: Long, pendingIntent: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        } catch (e: SecurityException) {
            // Exact-alarm access revoked: degrade to an inexact alarm that needs no permission so
            // schedule() never throws (which would crash the caller / abort batch rescheduling).
            Log.w("AlarmScheduler", "Exact fallback denied; degrading to inexact alarm", e)
            scheduleInexact(triggerTime, pendingIntent)
        }
    }

    private fun scheduleInexact(triggerTime: Long, pendingIntent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }
    }

    fun cancel(alarm: Alarm) {
        val intentOffset = Intent(context, AlarmReceiver::class.java)
        val pendingIntentOffset = PendingIntent.getBroadcast(
            context,
            alarm.id,
            intentOffset,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntentOffset != null) {
            alarmManager.cancel(pendingIntentOffset)
            pendingIntentOffset.cancel()
            Log.d("AlarmScheduler", "Alarm canceled with id: ${alarm.id}")
        }
        
        val intentExact = Intent(context, AlarmReceiver::class.java)
        val pendingIntentExact = PendingIntent.getBroadcast(
            context,
            -alarm.id,
            intentExact,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntentExact != null) {
            alarmManager.cancel(pendingIntentExact)
            pendingIntentExact.cancel()
        }
    }

    companion object {
        /**
         * Next fire time as a [Calendar]. The actual timezone-aware logic lives in the pure,
         * unit-testable [SunAlarmResolver.nextTriggerInstant]; this only adapts it to the device
         * clock ("now") and the [Calendar] the scheduling APIs expect.
         */
        fun getNextOccurrence(alarm: Alarm): Calendar {
            val instant = SunAlarmResolver.nextTriggerInstant(alarm, Instant.now(), ZoneId.systemDefault())
            return Calendar.getInstance().apply { timeInMillis = instant.toEpochMilli() }
        }
    }
}
