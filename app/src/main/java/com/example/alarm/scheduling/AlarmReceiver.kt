package com.example.alarm.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.alarm.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra("ALARM_ID", -1)
        val alarmTitle = intent.getStringExtra("ALARM_TITLE") ?: "Alarm"
        val alarmType = intent.getStringExtra("ALARM_TYPE") ?: "CUSTOM"

        Log.d("AlarmReceiver", "Alarm fired! Id: $alarmId, Title: $alarmTitle, Type: $alarmType")

        // 1. Kick off the foreground media playing service
        val serviceIntent = Intent(context, AlarmService::class.java).apply {
            putExtra("ALARM_ID", alarmId)
            putExtra("ALARM_TITLE", alarmTitle)
            putExtra("ALARM_TYPE", alarmType)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        // 1b. Re-arm the next occurrence independently of the notification dismiss path so a
        // repeating alarm survives even if the user never opens/dismisses the notification.
        // schedule() computes the NEXT occurrence, so this won't immediately re-trigger; it shares
        // the same request code as the dismiss-path re-arm, making it idempotent.
        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (alarmId != -1) {
                    val a = AppDatabase.getDatabase(context).alarmDao().getAlarmById(alarmId)
                    if (a != null && a.active && a.isRepeating()) AlarmScheduler(context).schedule(a)
                }
            } catch (e: Exception) {
                Log.e("AlarmReceiver", "re-arm failed", e)
            } finally {
                pr.finish()
            }
        }

        // 2. Launch MainActivity as full-screen companion.
        // On Android 10+ (Q) Background Activity Start restrictions block raw startActivity from a
        // broadcast, and the AlarmService notification's IMPORTANCE_HIGH setFullScreenIntent already
        // delivers the full-screen ring UI reliably and within BAL rules. Doing a second
        // NEW_TASK+CLEAR_TASK launch here would only compete with that path and can flicker/duplicate
        // the ring screen, so this direct launch is gated to pre-Q devices as a best-effort fallback.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val mainActivityClass = Class.forName("com.example.MainActivity")
                val activityIntent = Intent(context, mainActivityClass).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("RINGING_ALARM_ID", alarmId)
                    putExtra("RINGING_ALARM_TITLE", alarmTitle)
                    putExtra("RINGING_ALARM_TYPE", alarmType)
                }
                context.startActivity(activityIntent)
            } catch (e: Exception) {
                Log.e("AlarmReceiver", "Could not start main active full screen companion", e)
            }
        }
    }
}
