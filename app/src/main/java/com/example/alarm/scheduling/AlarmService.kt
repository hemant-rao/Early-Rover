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
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.alarm.data.Alarm
import com.example.alarm.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

class AlarmService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var playbackJob: Job? = null
    @Volatile private var stopRequested = false

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
        // A null intent means the OS re-created the service after killing it (sticky restart). This is
        // a transient, self-contained ring re-armed by AlarmManager, so never resurrect a phantom alarm.
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val alarmId = intent.getIntExtra("ALARM_ID", -1)
        val alarmTitle = intent.getStringExtra("ALARM_TITLE") ?: "Alarm"
        val alarmType = intent.getStringExtra("ALARM_TYPE") ?: "CUSTOM"
        val snoozeEnabled = intent.getBooleanExtra("ALARM_SNOOZE_ENABLED", true)
        val action = intent.action

        if (action == "ACTION_DISMISS") {
            dismissAlarm(alarmId)
            return START_NOT_STICKY
        } else if (action == "ACTION_SNOOZE") {
            snoozeAlarm(alarmId, alarmTitle, alarmType)
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildForegroundNotification(alarmId, alarmTitle, alarmType, snoozeEnabled),
                serviceType
            )
        } catch (e: Exception) {
            // On API 34+ a background-started FGS can throw; fall back to a plain notification so
            // the user still sees the alarm, then continue to play sound/vibrate below.
            Log.e("AlarmService", "startForeground failed, falling back to plain notification", e)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildForegroundNotification(alarmId, alarmTitle, alarmType, snoozeEnabled))
        }

        if (alarmId != -1) {
            playbackJob = serviceScope.launch {
                try {
                    val db = AppDatabase.getDatabase(this@AlarmService)
                    val alarmObj = db.alarmDao().getAlarmById(alarmId)
                    playAlarmSound(alarmObj?.ringtoneUri, alarmObj?.volume ?: 80)
                    // Honor the per-alarm vibration toggle; default true when the alarm is missing.
                    if (alarmObj?.vibrationEnabled != false) startVibration()
                } catch (e: Exception) {
                    Log.e("AlarmService", "DB lookup failed for custom ringtone, performing default playback", e)
                    playAlarmSound(null)
                    startVibration()
                }
            }
        } else {
            playAlarmSound(null)
            startVibration()
        }

        // Alarms are (re)armed by AlarmManager, not by service stickiness, so never restart sticky.
        return START_NOT_STICKY
    }

    private fun playAlarmSound(ringtoneUriString: String?, volumePercent: Int = 80) {
        // A dismiss/snooze may have arrived before this (async) coroutine resumed; never resurrect the ring.
        if (stopRequested) return
        val v = volumePercent.coerceIn(0, 100) / 100f
        try {
            var alarmUri: Uri? = null
            if (!ringtoneUriString.isNullOrEmpty()) {
                try {
                    alarmUri = Uri.parse(ringtoneUriString)
                } catch (e: Exception) {
                    Log.e("AlarmService", "Failed to parse custom ringtone uri string: $ringtoneUriString", e)
                }
            }
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            }
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
                setVolume(v, v)
                prepare()
                if (stopRequested) { release(); return }
                start()
            }
        } catch (e: Exception) {
            Log.e("AlarmService", "Failed to play custom alarm ringtone, trying fallback", e)
            try {
                val fallbackUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(this@AlarmService, fallbackUri!!)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    isLooping = true
                    setVolume(v, v)
                    prepare()
                    if (stopRequested) { release(); return }
                    start()
                }
            } catch (e2: Exception) {
                Log.e("AlarmService", "Fallback system default ringtone failed: ", e2)
                try {
                    val notifUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    mediaPlayer = MediaPlayer.create(this@AlarmService, notifUri)
                    mediaPlayer?.isLooping = true
                    mediaPlayer?.setVolume(v, v)
                    if (stopRequested) {
                        mediaPlayer?.release()
                        mediaPlayer = null
                        return
                    }
                    mediaPlayer?.start()
                } catch (e3: Exception) {
                    Log.e("AlarmService", "Fallback notification sound failed, alarm muted!", e3)
                }
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
        // Make dismiss authoritative over any in-flight playback coroutine so it can't resurrect the ring.
        stopRequested = true
        playbackJob?.cancel()
        // Silence the ring immediately, but defer stopSelf() until the DB/reschedule work is done so
        // we don't tear the service (and its scope) down before the coroutine runs.
        mediaPlayer?.stop()
        try { vibrator?.cancel() } catch (e: Exception) { Log.e("AlarmService", "Failed to cancel vibration", e) }
        if (alarmId != -1) {
            serviceScope.launch {
                try {
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
                } finally {
                    stopSelf()
                }
            }
        } else {
            stopSelf()
        }
    }

    private fun snoozeAlarm(alarmId: Int, title: String, type: String) {
        // Make snooze authoritative over any in-flight playback coroutine so it can't resurrect the ring.
        stopRequested = true
        playbackJob?.cancel()
        // Silence the ring immediately, but defer stopSelf() until the snooze is scheduled.
        mediaPlayer?.stop()
        try { vibrator?.cancel() } catch (e: Exception) { Log.e("AlarmService", "Failed to cancel vibration", e) }
        if (alarmId != -1) {
            serviceScope.launch {
                try {
                    val db = AppDatabase.getDatabase(this@AlarmService)
                    val dao = db.alarmDao()
                    val alarm = dao.getAlarmById(alarmId)
                    // Defensively reject a snooze for an alarm that has snooze disabled (e.g. stale/forged intent).
                    if (alarm != null && !alarm.snoozeEnabled) {
                        Log.d("AlarmService", "Snooze ignored: alarm $alarmId has snooze disabled")
                        return@launch
                    }
                    val snoozeTimeMinutes = alarm?.snoozeMinutes ?: 5

                    // Create relative intent for snooze alarm triggers
                    val snoozeIntent = Intent(this@AlarmService, AlarmReceiver::class.java).apply {
                        action = "ACTION_SNOOZE_FIRE" // keep this PendingIntent's identity from colliding with a base alarm's
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
                } finally {
                    stopSelf()
                }
            }
        } else {
            stopSelf()
        }
    }

    private fun buildForegroundNotification(alarmId: Int, title: String, type: String, snoozeEnabled: Boolean = true): Notification {
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
            putExtra("ALARM_TITLE", title)
            putExtra("ALARM_TYPE", type)
        }
        val dismissPendingIntent = PendingIntent.getService(this, alarmId + 300, dismissIntent, pendingFlags)

        val snoozePendingIntent = if (snoozeEnabled) {
            val snoozeIntent = Intent(this, AlarmService::class.java).apply {
                action = "ACTION_SNOOZE"
                putExtra("ALARM_ID", alarmId)
                // Carry title/type so a notification-button snooze keeps the real name & SUNRISE/SUNSET type.
                putExtra("ALARM_TITLE", title)
                putExtra("ALARM_TYPE", type)
            }
            PendingIntent.getService(this, alarmId + 400, snoozeIntent, pendingFlags)
        } else null

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

        if (snoozePendingIntent != null) {
            builder.addAction(android.R.drawable.ic_media_play, "Snooze", snoozePendingIntent)
        }

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
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "SUNRISE_SUNSET_ALARM_CHANNEL_RING"
        const val NOTIFICATION_ID = 91827
    }
}
