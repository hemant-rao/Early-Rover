package com.example.alarm.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

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

        // 2. Launch MainActivity as full-screen companion
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
