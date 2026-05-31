package com.example.alarm.scheduling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.alarm.data.Alarm
import com.example.alarm.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

class AlarmService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        initVibrator()
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getIntExtra("ALARM_ID", -1) ?: -1
        val alarmTitle = intent?.getStringExtra("ALARM_TITLE") ?: "Alarm"
        val alarmType = intent?.getStringExtra("ALARM_TYPE") ?: "CUSTOM"
        val action = intent?.action

        if (action == "ACTION_DISMISS") {
            dismissAlarm(alarmId)
            return START_NOT_STICKY
        } else if (action == "ACTION_SNOOZE") {
            snoozeAlarm(alarmId, alarmTitle, alarmType)
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification(alarmId, alarmTitle, alarmType))

        playAlarmSound()
        startVibration()

        return START_STICKY
    }

    private fun playAlarmSound() {
        try {
            var alarmUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmService, alarmUri!!)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("AlarmService", "Failed to play alarm ringtone", e)
            // fallback sound
            try {
                mediaPlayer = MediaPlayer.create(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                mediaPlayer?.isLooping = true
                mediaPlayer?.start()
            } catch (e2: Exception) {
                Log.e("AlarmService", "Fallback ringtone failed as well", e2)
            }
        }
    }

    private fun startVibration() {
        val pattern = longArrayOf(0, 1000, 1000) // vibrate, pause, vibrate
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e("AlarmService", "Failed to start vibration provider", e)
        }
    }

    private fun dismissAlarm(alarmId: Int) {
        stopSelf()
        if (alarmId != -1) {
            serviceScope.launch {
                val db = AppDatabase.getDatabase(this@AlarmService)
                val dao = db.alarmDao()
                val alarm = dao.getAlarmById(alarmId)
                if (alarm != null) {
                    if (!alarm.isRepeating()) {
                        // Deactivate single-use alarm
                        dao.updateAlarm(alarm.copy(active = false))
                    } else {
                        // Repeating alarm, reschedule next occurrence
                        AlarmScheduler(this@AlarmService).schedule(alarm)
                    }
                }
            }
        }
    }

    private fun snoozeAlarm(alarmId: Int, title: String, type: String) {
        stopSelf()
        if (alarmId != -1) {
            serviceScope.launch {
                val db = AppDatabase.getDatabase(this@AlarmService)
                val dao = db.alarmDao()
                val alarm = dao.getAlarmById(alarmId)
                val snoozeTimeMinutes = alarm?.snoozeMinutes ?: 5
                
                // Create relative intent for snooze alarm triggers
                val snoozeIntent = Intent(this@AlarmService, AlarmReceiver::class.java).apply {
                    putExtra("ALARM_ID", alarmId)
                    putExtra("ALARM_TITLE", "$title (Snoozed)")
                    putExtra("ALARM_TYPE", type)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    this@AlarmService,
                    alarmId + 100000, // offset request code to avoid collision
                    snoozeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                
                val triggerAtMillis = System.currentTimeMillis() + (snoozeTimeMinutes * 60 * 1000)
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
                Log.d("AlarmService", "Snoozed alarm $alarmId for $snoozeTimeMinutes minutes")
            }
        }
    }

    private fun buildForegroundNotification(alarmId: Int, title: String, type: String): Notification {
        val mainActivityClass = try {
            Class.forName("com.example.MainActivity")
        } catch (e: Exception) {
            null
        }

        val fullScreenIntent = if (mainActivityClass != null) {
            Intent(this, mainActivityClass).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("RINGING_ALARM_ID", alarmId)
                putExtra("RINGING_ALARM_TITLE", title)
                putExtra("RINGING_ALARM_TYPE", type)
            }
        } else null

        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val fullScreenPendingIntent = fullScreenIntent?.let {
            PendingIntent.getActivity(this, alarmId + 200, it, pendingFlags)
        }

        val dismissIntent = Intent(this, AlarmService::class.java).apply {
            action = "ACTION_DISMISS"
            putExtra("ALARM_ID", alarmId)
        }
        val dismissPendingIntent = PendingIntent.getService(this, alarmId + 300, dismissIntent, pendingFlags)

        val snoozeIntent = Intent(this, AlarmService::class.java).apply {
            action = "ACTION_SNOOZE"
            putExtra("ALARM_ID", alarmId)
        }
        val snoozePendingIntent = PendingIntent.getService(this, alarmId + 400, snoozeIntent, pendingFlags)

        val alarmDescription = when (type) {
            "SUNRISE" -> "Solar Sunrise Alert"
            "SUNSET" -> "Solar Sunset Alert"
            else -> "Standard Time Alarm"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(alarmDescription)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)
            .addAction(android.R.drawable.ic_media_play, "Snooze", snoozePendingIntent)

        if (fullScreenPendingIntent != null) {
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
            builder.setContentIntent(fullScreenPendingIntent)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Sunrise Sunset Active Alarms"
            val descriptionText = "Rings and alerts when alarm thresholds are matched"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                setSound(null, null) // controlled byMediaPlayer
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        vibrator?.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "SUNRISE_SUNSET_ALARM_CHANNEL_RING"
        const val NOTIFICATION_ID = 91827
    }
}
