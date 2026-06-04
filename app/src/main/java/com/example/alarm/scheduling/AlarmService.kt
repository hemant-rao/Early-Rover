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
import android.os.PowerManager
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

    // Per-ring state keyed by notificationId so the dual-ring (offset + exact) feature — which delivers
    // two onStartCommand calls to this SINGLE service instance — does not collapse into shared single
    // values (which leaked the first MediaPlayer and let a dismiss of one ring silence the other).
    private val mediaPlayers = java.util.concurrent.ConcurrentHashMap<Int, MediaPlayer>()
    private val playbackJobs = java.util.concurrent.ConcurrentHashMap<Int, Job>()
    private val stopRequestedIds = java.util.Collections.synchronizedSet(HashSet<Int>())
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    // Wrap the base context with the saved locale so getString() (channel name/description) resolves the
    // per-app language rather than the system default.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(com.example.alarm.util.LocaleHelper.wrap(base))
    }

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

    /**
     * Acquire a partial wakelock to bridge the gap between the (short, ~10s) AlarmManager wake window
     * and MediaPlayer.start(). Without this the device can re-enter Doze before the ringtone reliably
     * starts/loops. PARTIAL only — audio + the FGS full-screen-intent handle the screen.
     */
    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SolarAlarm::AlarmRing").apply {
                setReferenceCounted(false)
                acquire(60_000L) // safety-net timeout; explicitly released on dismiss/snooze/destroy
            }
        } catch (e: Exception) {
            Log.e("AlarmService", "Failed to acquire wakelock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.e("AlarmService", "Failed to release wakelock", e)
        }
        wakeLock = null
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
        // The dual-ring (offset + exact) feature fires two separate service starts for one alarm; give the
        // exact companion a distinct notification id & action request codes so the two are independently
        // controllable instead of coalescing into one notification.
        val isExactAlso = intent.getBooleanExtra("IS_EXACT_ALSO", false)
        val notificationId = if (isExactAlso) NOTIFICATION_ID + 1 else NOTIFICATION_ID
        val action = intent.action

        if (action == "ACTION_DISMISS") {
            dismissAlarm(alarmId, notificationId, startId)
            return START_NOT_STICKY
        } else if (action == "ACTION_SNOOZE") {
            snoozeAlarm(alarmId, alarmTitle, alarmType, isExactAlso, notificationId, startId)
            return START_NOT_STICKY
        }

        // Fresh ring on a (possibly reused) service instance: clear any stale stop flag from a prior
        // dismiss/snooze for THIS ring only so this new alarm is not silenced before it starts (and so a
        // dismiss of the companion ring cannot pre-empt this one).
        stopRequestedIds.remove(notificationId)
        // Bridge the AlarmManager wake window until MediaPlayer.start() so the ring survives Doze.
        acquireWakeLock()

        createNotificationChannel()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Must match the manifest's android:foregroundServiceType="specialUse"
            // (with the PROPERTY_SPECIAL_USE_FGS_SUBTYPE property) or API 34+ throws
            // MissingForegroundServiceTypeException and the alarm is downgraded.
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(
                this,
                notificationId,
                buildForegroundNotification(alarmId, alarmTitle, alarmType, snoozeEnabled, isExactAlso),
                serviceType
            )
        } catch (e: Exception) {
            // On API 34+ a background-started FGS can throw; fall back to a plain notification so
            // the user still sees the alarm, then continue to play sound/vibrate below.
            Log.e("AlarmService", "startForeground failed, falling back to plain notification", e)
            // The service is now a plain background service the OS can kill within seconds. The wakelock
            // (already acquired above) keeps the CPU alive long enough for the fallback audio to be heard.
            acquireWakeLock()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(notificationId, buildForegroundNotification(alarmId, alarmTitle, alarmType, snoozeEnabled, isExactAlso))
            // Bound the orphan: as a plain (killable) background service onDestroy() may never run, so a
            // ring with no user interaction could leak the MediaPlayer/vibrator/wakelock indefinitely.
            // Schedule a deterministic self-stop that releases this ring's resources after the ring window.
            serviceScope.launch {
                kotlinx.coroutines.delay(FALLBACK_RING_TIMEOUT_MS)
                if (!stopRequestedIds.contains(notificationId)) {
                    Log.w("AlarmService", "Fallback ring watchdog firing; releasing ring $notificationId")
                    dismissAlarm(alarmId, notificationId, startId)
                }
            }
        }

        if (alarmId != -1) {
            // Run on IO: getAlarmById is a synchronous Room read and MediaPlayer.prepare() does blocking
            // I/O — neither must run on the main looper or it can ANR right as the alarm should ring.
            playbackJobs[notificationId] = serviceScope.launch(Dispatchers.IO) {
                try {
                    val db = AppDatabase.getDatabase(this@AlarmService)
                    val alarmObj = db.alarmDao().getAlarmById(alarmId)
                    // If snooze is disabled for this alarm, re-post the live notification without the Snooze
                    // action (the initial startForeground defaults to showing it before the row is known).
                    if (alarmObj?.snoozeEnabled == false) {
                        try {
                            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                                .notify(
                                    notificationId,
                                    buildForegroundNotification(alarmId, alarmTitle, alarmType, snoozeEnabled = false, isExactAlso = isExactAlso)
                                )
                        } catch (e: Exception) {
                            Log.e("AlarmService", "Failed to update notification without snooze action", e)
                        }
                    }
                    playAlarmSound(notificationId, alarmObj?.ringtoneUri, alarmObj?.volume ?: 80)
                    // Honor the per-alarm vibration toggle; default true when the alarm is missing.
                    if (alarmObj?.vibrationEnabled != false) startVibration()
                } catch (e: Exception) {
                    Log.e("AlarmService", "DB lookup failed for custom ringtone, performing default playback", e)
                    playAlarmSound(notificationId, null)
                    startVibration()
                }
            }
        } else {
            // Off-main to avoid blocking on MediaPlayer.prepare().
            playbackJobs[notificationId] = serviceScope.launch(Dispatchers.IO) {
                playAlarmSound(notificationId, null)
                startVibration()
            }
        }

        // Alarms are (re)armed by AlarmManager, not by service stickiness, so never restart sticky.
        return START_NOT_STICKY
    }

    private fun playAlarmSound(notificationId: Int, ringtoneUriString: String?, volumePercent: Int = 80) {
        // A dismiss/snooze may have arrived before this (async) coroutine resumed; never resurrect the ring.
        if (stopRequestedIds.contains(notificationId)) return
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
            // Defensive: tear down any previous player for THIS ring before reassigning so a re-delivered
            // start cannot orphan a still-looping MediaPlayer.
            releasePlayer(notificationId)
            val player = MediaPlayer().apply {
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
            }
            if (stopRequestedIds.contains(notificationId)) { player.release(); return }
            mediaPlayers[notificationId] = player
            player.start()
        } catch (e: Exception) {
            Log.e("AlarmService", "Failed to play custom alarm ringtone, trying fallback", e)
            try {
                val fallbackUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                releasePlayer(notificationId)
                val player = MediaPlayer().apply {
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
                }
                if (stopRequestedIds.contains(notificationId)) { player.release(); return }
                mediaPlayers[notificationId] = player
                player.start()
            } catch (e2: Exception) {
                Log.e("AlarmService", "Fallback system default ringtone failed: ", e2)
                try {
                    val notifUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    // Use an explicit MediaPlayer with USAGE_ALARM AudioAttributes instead of
                    // MediaPlayer.create (which defaults to USAGE_MEDIA) so this last-ditch fallback still
                    // routes through the alarm stream and is not suppressed by DND/silent mode.
                    releasePlayer(notificationId)
                    val player = MediaPlayer().apply {
                        setDataSource(this@AlarmService, notifUri!!)
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        isLooping = true
                        setVolume(v, v)
                        prepare()
                    }
                    if (stopRequestedIds.contains(notificationId)) { player.release(); return }
                    mediaPlayers[notificationId] = player
                    player.start()
                } catch (e3: Exception) {
                    Log.e("AlarmService", "Fallback notification sound failed, alarm muted!", e3)
                }
            }
        }
    }

    /** Stop and release the MediaPlayer for a single ring, if any. */
    private fun releasePlayer(notificationId: Int) {
        mediaPlayers.remove(notificationId)?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
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

    private fun dismissAlarm(alarmId: Int, notificationId: Int, startId: Int) {
        // Make dismiss authoritative over any in-flight playback coroutine for THIS ring so it can't
        // resurrect the ring — without silencing the companion ring (which has its own notificationId).
        stopRequestedIds.add(notificationId)
        playbackJobs.remove(notificationId)?.cancel()
        // Silence only this ring; defer stopSelf() until the DB/reschedule work is done so we don't tear
        // the service (and its scope) down before the coroutine runs.
        releasePlayer(notificationId)
        // Cancel the notification for this ring explicitly so a fallback (non-foreground) notification or
        // the companion's foreground notification cannot be left stuck.
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId)
        } catch (e: Exception) { Log.e("AlarmService", "Failed to cancel notification $notificationId", e) }
        maybeStopShared()
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
                    stopSelfIfIdle(startId)
                }
            }
        } else {
            stopSelfIfIdle(startId)
        }
    }

    /** Cancel vibration / release the wakelock only when no ring remains active (shared device resources). */
    private fun maybeStopShared() {
        if (mediaPlayers.isEmpty() && playbackJobs.isEmpty()) {
            try { vibrator?.cancel() } catch (e: Exception) { Log.e("AlarmService", "Failed to cancel vibration", e) }
            releaseWakeLock()
        }
    }

    /** Stop the service only when no other ring is still active, so dismissing one dual-ring companion
     *  does not tear down the service while the other is still ringing. */
    private fun stopSelfIfIdle(startId: Int) {
        if (mediaPlayers.isEmpty() && playbackJobs.isEmpty()) {
            stopSelf(startId)
        }
    }

    private fun snoozeAlarm(alarmId: Int, title: String, type: String, isExactAlso: Boolean, notificationId: Int, startId: Int) {
        // Make snooze authoritative over any in-flight playback coroutine for THIS ring so it can't
        // resurrect the ring — without silencing the companion ring.
        stopRequestedIds.add(notificationId)
        playbackJobs.remove(notificationId)?.cancel()
        // Silence only this ring; defer stopSelf() until the snooze is scheduled.
        releasePlayer(notificationId)
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId)
        } catch (e: Exception) { Log.e("AlarmService", "Failed to cancel notification $notificationId", e) }
        maybeStopShared()
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
                        // Carry the snooze flag for consistency with every other arming path so the
                        // re-fired ring's initial notification renders correctly before the async DB
                        // lookup. (Always true here given the line-391 guard; the async re-post at
                        // lines 176-186 is what closes a mid-flight snooze-disable window.)
                        putExtra("ALARM_SNOOZE_ENABLED", alarm?.snoozeEnabled ?: true)
                        // Preserve the dual-ring identity so the re-fire comes back as the same ring
                        // (correct notificationId & request-code bias) instead of collapsing onto the base ring.
                        putExtra("IS_EXACT_ALSO", isExactAlso)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        this@AlarmService,
                        // Bias the exact companion's re-arm request code so two snoozed rings of the same
                        // alarm don't overwrite each other via FLAG_UPDATE_CURRENT.
                        alarmId + 100000 + (if (isExactAlso) 50000 else 0),
                        snoozeIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val triggerAtMillis = System.currentTimeMillis() + (snoozeTimeMinutes * 60 * 1000)
                    val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager

                    // Mirror AlarmScheduler.scheduleFallback: if exact-alarm access has been revoked
                    // (Play stripping USE_EXACT_ALARM, or an OEM build) the exact call throws
                    // SecurityException on this coroutine and the snooze would be silently lost.
                    // Pre-check on API 31+ and always wrap in try/catch so we degrade to an inexact
                    // (but allow-while-idle) alarm rather than dropping the snooze entirely.
                    val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        alarmManager.canScheduleExactAlarms()
                    } else true
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && canExact) {
                            alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                        } else {
                            alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                        }
                    } catch (se: SecurityException) {
                        Log.w("AlarmService", "Exact snooze not permitted; falling back to inexact", se)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                        } else {
                            alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                        }
                    }
                    Log.d("AlarmService", "Snoozed alarm $alarmId for $snoozeTimeMinutes minutes")
                } finally {
                    stopSelfIfIdle(startId)
                }
            }
        } else {
            stopSelfIfIdle(startId)
        }
    }

    private fun buildForegroundNotification(alarmId: Int, title: String, type: String, snoozeEnabled: Boolean = true, isExactAlso: Boolean = false): Notification {
        // Bias request codes for the exact dual-ring companion so its PendingIntents don't collide with
        // the offset ring's, keeping the two notifications independently dismissable/snoozable.
        val rcBias = if (isExactAlso) 50000 else 0
        val alarmActivityClass = try {
            Class.forName("com.example.alarm.ui.AlarmActivity")
        } catch (e: Exception) {
            null
        }

        val fullScreenIntent = if (alarmActivityClass != null) {
            Intent(this, alarmActivityClass).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("RINGING_ALARM_ID", alarmId)
                putExtra("RINGING_ALARM_TITLE", title)
                putExtra("RINGING_ALARM_TYPE", type)
                // Thread the snooze flag into the full-screen ring so its UI matches the notification:
                // a snooze-disabled alarm must NOT show a Snooze button (tapping it would be a silent
                // no-op, since AlarmService.snoozeAlarm defensively rejects a disabled-snooze alarm).
                putExtra("RINGING_ALARM_SNOOZE_ENABLED", snoozeEnabled)
            }
        } else null

        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val fullScreenPendingIntent = fullScreenIntent?.let {
            PendingIntent.getActivity(this, alarmId + 200 + rcBias, it, pendingFlags)
        }

        val dismissIntent = Intent(this, AlarmService::class.java).apply {
            action = "ACTION_DISMISS"
            putExtra("ALARM_ID", alarmId)
            putExtra("ALARM_TITLE", title)
            putExtra("ALARM_TYPE", type)
            putExtra("IS_EXACT_ALSO", isExactAlso)
        }
        val dismissPendingIntent = PendingIntent.getService(this, alarmId + 300 + rcBias, dismissIntent, pendingFlags)

        val snoozePendingIntent = if (snoozeEnabled) {
            val snoozeIntent = Intent(this, AlarmService::class.java).apply {
                action = "ACTION_SNOOZE"
                putExtra("ALARM_ID", alarmId)
                // Carry title/type so a notification-button snooze keeps the real name & SUNRISE/SUNSET type.
                putExtra("ALARM_TITLE", title)
                putExtra("ALARM_TYPE", type)
                putExtra("IS_EXACT_ALSO", isExactAlso)
            }
            PendingIntent.getService(this, alarmId + 400 + rcBias, snoozeIntent, pendingFlags)
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
            // Resolve channel labels from resources via the locale-wrapped base context (attachBaseContext)
            // so the OS-surfaced channel name/description honor the per-app language. Recreated on each
            // service start so a language change takes effect on the next ring.
            val name = getString(com.example.R.string.channel_alarm_name)
            val descriptionText = getString(com.example.R.string.channel_alarm_description)
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
        // Release every ring's MediaPlayer (the dual-ring feature can hold more than one).
        for (id in mediaPlayers.keys.toList()) {
            releasePlayer(id)
        }
        try { vibrator?.cancel() } catch (e: Exception) { Log.e("AlarmService", "Failed to cancel vibration in onDestroy", e) }
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "SUNRISE_SUNSET_ALARM_CHANNEL_RING"
        const val NOTIFICATION_ID = 91827
        // Upper bound on how long the fallback (non-foreground) path may keep resources alive when the OS
        // refuses foreground promotion and onDestroy() may never run.
        private const val FALLBACK_RING_TIMEOUT_MS = 60_000L
    }
}
