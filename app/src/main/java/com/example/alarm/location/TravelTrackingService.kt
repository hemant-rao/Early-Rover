package com.example.alarm.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.alarm.data.AppDatabase
import com.example.alarm.data.TravelAlarm
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale

class TravelTrackingService : Service(), TextToSpeech.OnInitListener {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var arrivalWakeLock: android.os.PowerManager.WakeLock? = null

    // Handle to the flashlight-blink coroutine so teardown can deterministically
    // wait for it to fully terminate before the synchronous torch force-off, removing
    // the last-write-wins race that could otherwise leave the torch stuck ON.
    @Volatile
    private var blinkJob: Job? = null

    // Set true the moment teardown begins (ACTION_STOP / onDestroy). A queued
    // onLocationResult can still fire after serviceScope is cancelled but before
    // removeLocationUpdates lands; this flag stops it from re-writing the static
    // StateFlows (current location/speed) that cleanupResources just reset, which
    // would otherwise leave stale tracking values visible in the UI.
    @Volatile
    private var stopped = false

    // Ids of alarms that have already fired this session, so a single arrival doesn't
    // re-trigger on every 3-6s location update while still inside the radius.
    private val triggeredAlarmIds = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    companion object {
        private const val TAG = "TravelTrackingService"
        const val CHANNEL_ID = "TRAVEL_TRACKING_LOCATION_CHANNEL"
        const val NOTIFICATION_ID = 23948
        const val ACTION_STOP = "com.example.alarm.location.action.STOP"

        // Live stats exposed to Compose UI in real-time
        private val _isTracking = MutableStateFlow(false)
        val isTracking = _isTracking.asStateFlow()

        private val _currentLocation = MutableStateFlow<Location?>(null)
        val currentLocation = _currentLocation.asStateFlow()

        private val _currentSpeedKmh = MutableStateFlow(0.0)
        val currentSpeedKmh = _currentSpeedKmh.asStateFlow()

        private val _statusMessage = MutableStateFlow("Initializing...")
        val statusMessage = _statusMessage.asStateFlow()

        private val _nearestAlarm = MutableStateFlow<TravelAlarm?>(null)
        val nearestAlarm = _nearestAlarm.asStateFlow()

        private val _distanceToNearestKm = MutableStateFlow(-1.0)
        val distanceToNearestKm = _distanceToNearestKm.asStateFlow()

        private val _startLocation = MutableStateFlow<Location?>(null)
        val startLocation = _startLocation.asStateFlow()

        private val _totalTripDistanceKm = MutableStateFlow(0.0)
        val totalTripDistanceKm = _totalTripDistanceKm.asStateFlow()

        // Static commands to control the service simply
        fun startService(context: Context) {
            val intent = Intent(context, TravelTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            // Prefer an explicit STOP action so the service can tear down the foreground
            // notification cleanly before it is destroyed. This avoids leaving a dangling
            // foreground state that can take down the whole app.
            try {
                val stopIntent = Intent(context, TravelTrackingService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(stopIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending STOP action to service", e)
            }

            // Fallback: ensure the service is stopped even if the STOP action did not land.
            try {
                val intent = Intent(context, TravelTrackingService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed stopping service via stopService fallback", e)
            }
        }

        fun setStartLocation(latitude: Double, longitude: Double) {
            val loc = Location("custom").apply {
                this.latitude = latitude
                this.longitude = longitude
            }
            _startLocation.value = loc
        }

        fun clearNearestAlarm() {
            _nearestAlarm.value = null
            _distanceToNearestKm.value = -1.0
        }
    }

    override fun attachBaseContext(base: Context) {
        // Wrap with the active per-app locale so getString(...) for the notification
        // channel labels resolves under the user's selected language (en/hi).
        super.attachBaseContext(com.example.alarm.util.LocaleHelper.wrap(base))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created")
        stopped = false
        _isTracking.value = true
        _statusMessage.value = "Locating GPS Satellite..."

        // Everything in onCreate is guarded: a foreground-service or permission failure
        // here would otherwise crash the whole app the instant the user taps "Start".
        try {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            textToSpeech = TextToSpeech(this, this)
            initVibrator()
            createNotificationChannel()

            // On Android 10+ the location foreground-service type must be supplied to
            // startForeground (it is declared in the manifest too). ServiceCompat picks
            // the right overload for the running OS version.
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildStaticNotification("Securing transit path...", "Searching location signals"),
                serviceType
            )

            startLocationTracking()
        } catch (e: Exception) {
            // Could not promote to a foreground location service (missing permission,
            // OS restriction, etc.). Fail gracefully instead of crashing the app.
            Log.e(TAG, "Failed to start travel tracking service", e)
            _statusMessage.value = "Unable to start tracking. Check location permission."
            _isTracking.value = false
            stopSelf()
        }
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

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS Language not supported")
            } else {
                isTtsReady = true
                Log.d(TAG, "TTS ready for alerts")
            }
        } else {
            Log.e(TAG, "TTS Initialization failed")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        Log.d(TAG, "Starting location requests")
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            6000L // 6 seconds battery-balanced interval
        ).apply {
            setMinUpdateIntervalMillis(3000L) // limit fastest updates to 3 seconds
            setWaitForAccurateLocation(false)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                // Teardown may have begun (scope cancelled, flows reset) while this
                // callback was already queued; drop it so we don't resurrect stale
                // tracking state after the service stopped.
                if (stopped) return
                val location = locationResult.lastLocation ?: return

                // Always update live location/speed/UI even for a coarse fix, so the UI never
                // freezes. Accuracy is only used later to gate the one-shot arrival trigger.
                _currentLocation.value = location
                
                // Convert m/s speed to km/h speed safely
                val speedKmh = location.speed * 3.6
                _currentSpeedKmh.value = if (speedKmh.isNaN()) 0.0 else speedKmh

                serviceScope.launch(Dispatchers.IO) {
                    processLocationUpdate(location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed requesting location updates: ", e)
            _statusMessage.value = "GPS Error. Check location settings."
        }
    }

    private suspend fun processLocationUpdate(location: Location) {
        try {
            if (_startLocation.value == null) {
                _startLocation.value = location
            }

            val db = AppDatabase.getDatabase(this)
            val activeAlarms = db.travelAlarmDao().getActiveTravelAlarms()

            if (activeAlarms.isEmpty()) {
                _nearestAlarm.value = null
                _distanceToNearestKm.value = -1.0
                _statusMessage.value = "No active travel tracking alarms set."
                updateNotification("No active travel destination preset.", "Travel Alarm idle")
                return
            }

            var closestAlarm: TravelAlarm? = null
            var minDistance = Double.MAX_VALUE

            for (alarm in activeAlarms) {
                val distance = calculateDistanceInKm(
                    location.latitude,
                    location.longitude,
                    alarm.latitude,
                    alarm.longitude
                )
                if (distance < minDistance) {
                    minDistance = distance
                    closestAlarm = alarm
                }

                // Proximity threshold penetrated! Set.add(id) returns false if already fired,
                // so each arrival triggers exactly once instead of on every location update.
                // Jitter protection without a hard accuracy cap: a fix counts as "inside" when
                // its entire accuracy confidence circle fits within the radius (so a coarse
                // 100-200 m urban/transit fix that is clearly inside still wakes the user),
                // OR when accuracy is missing. As a safety net for small radii where the
                // containment test would be too strict, also fire once the user is well inside
                // (half the radius). This scales correctly with the radius, unlike the old
                // 50 m cap which never let a coarse fix trigger.
                val radiusMeters = alarm.radiusKm * 1000.0
                val distanceMeters = distance * 1000.0
                val withinRadius = !location.hasAccuracy() ||
                    (distanceMeters + location.accuracy) <= radiusMeters ||
                    distance <= alarm.radiusKm * 0.5
                if (distance <= alarm.radiusKm && withinRadius && triggeredAlarmIds.add(alarm.id)) {
                    triggerArrivalAlarm(alarm, distance)
                }
            }

            _nearestAlarm.value = closestAlarm
            _distanceToNearestKm.value = minDistance

            // Update total trip distance from start location to the nearest waypoint
            val startLoc = _startLocation.value
            val customStartLat = closestAlarm?.startLatitude
            val customStartLng = closestAlarm?.startLongitude
            if (customStartLat != null && customStartLng != null && closestAlarm != null) {
                _totalTripDistanceKm.value = calculateDistanceInKm(
                    customStartLat,
                    customStartLng,
                    closestAlarm.latitude,
                    closestAlarm.longitude
                )
            } else if (startLoc != null && closestAlarm != null) {
                _totalTripDistanceKm.value = calculateDistanceInKm(
                    startLoc.latitude,
                    startLoc.longitude,
                    closestAlarm.latitude,
                    closestAlarm.longitude
                )
            } else {
                _totalTripDistanceKm.value = 0.0
            }

            val label = closestAlarm?.label ?: "Destination"
            val distFormatted = String.format(Locale.US, "%.2f km", minDistance)
            val speedStr = String.format(Locale.US, "%.1f km/h", _currentSpeedKmh.value)
            _statusMessage.value = "Tracking $label: $distFormatted away at $speedStr"

            updateNotification(
                "Tracking $label: $distFormatted remaining",
                "Speed: $speedStr | Target Radius: ${closestAlarm?.radiusKm ?: 0.0} km"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in processLocationUpdate: ", e)
        }
    }

    private fun triggerArrivalAlarm(alarm: TravelAlarm, distanceKm: Double) {
        try {
            Log.e(TAG, "PROXIMITY DETECTED! Triggering alarm for: ${alarm.label}")
            
            // Build an immediate WakeLock to illuminate screen and sound alerts even in deep suspend
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            try {
                // Release any previous lock and keep a single, non-reference-counted lock we can
                // reliably release in onDestroy (auto-times out after 15s as a safety net).
                arrivalWakeLock?.let { if (it.isHeld) it.release() }
                arrivalWakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "SolarAlarmTravelAlarm::WakeLockTag"
                ).apply {
                    setReferenceCounted(false)
                    acquire(15000L) // hold wakelock for 15 seconds to ensure ring
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire wake lock: ", e)
            }

            // 1. Do NOT permanently disable the alarm in the database. A commuter who takes the
            // same route daily (e.g. "Bus Stop", "Railway Station") needs the saved alarm to keep
            // working on every journey. Re-firing within a single tracking session is already
            // prevented by the in-memory triggeredAlarmIds guard (added in processLocationUpdate
            // and cleared in cleanupResources), so no DB mutation is required here.

            // 2. Play extremely loud sirens
            try {
                playSirenAlarm()
            } catch (e: Exception) {
                Log.e(TAG, "Failed playing siren alarm", e)
            }

            // 3. Shake device continuously
            if (alarm.vibrationEnabled) {
                try {
                    playRumbleVibration()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed starting rumble vibration", e)
                }
            }

            // 3.5. Flash flashlight optionally (Sleep Protection)
            if (alarm.flashEnabled) {
                try {
                    flashLightBlink()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to blink flashlight", e)
                }
            }

            // 4. Fire Speech alerts to wake sleeping commuter
            if (alarm.ttsEnabled && isTtsReady) {
                try {
                    val speakText = "Attention! Wake up. You are approaching ${alarm.label}. It is ${String.format(Locale.US, "%.1f", distanceKm)} kilometers remaining."
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        textToSpeech?.speak(speakText, TextToSpeech.QUEUE_FLUSH, null, "TravelAlarmTts")
                    } else {
                        @Suppress("DEPRECATION")
                        textToSpeech?.speak(speakText, TextToSpeech.QUEUE_FLUSH, null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed speaking TTS", e)
                }
            }

            // 5. Fire full-screen overlay ringing state to MainActivity to display premium wake controls
            try {
                val ringIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("RINGING_ALARM_ID", alarm.id + 500000) // Travel Alarms have high offset
                    putExtra("RINGING_ALARM_TITLE", "Arrival Alert: ${alarm.label}")
                    putExtra("RINGING_ALARM_TYPE", "TRAVEL")
                }
                startActivity(ringIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Background Activity Start restricted or failed starting MainActivity", e)
            }

            // Show urgent arrival notification channel with high priority so they can click it even if activity start is restricted
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                
                // Add fullscreen intent to notification so it works in the lock screen/background safely
                val ringIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("RINGING_ALARM_ID", alarm.id + 500000)
                    putExtra("RINGING_ALARM_TITLE", "Arrival Alert: ${alarm.label}")
                    putExtra("RINGING_ALARM_TYPE", "TRAVEL")
                }
                val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val fullScreenPendingIntent = PendingIntent.getActivity(this, alarm.id + 500200, ringIntent, pendingFlags)

                val urgentNotify = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("ARRIVING NOW: ${alarm.label}!")
                    .setContentText("Distance: ${String.format(Locale.US, "%.2f km", distanceKm)}. Stop is approaching!")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setOngoing(true)
                    .setFullScreenIntent(fullScreenPendingIntent, true)
                    .setContentIntent(fullScreenPendingIntent)
                    .build()
                
                nm.notify(NOTIFICATION_ID, urgentNotify)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger arrival notification fallback", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal inside triggerArrivalAlarm", e)
        }
    }

    private fun playSirenAlarm() {
        if (mediaPlayer == null) {
            try {
                var alarmUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                if (alarmUri == null) {
                    alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                }
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(this@TravelTrackingService, alarmUri!!)
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
                Log.e(TAG, "Error playing audio siren alarm", e)
            }
        }
    }

    private fun playRumbleVibration() {
        val pattern = longArrayOf(0, 800, 400, 800, 400)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun flashLightBlink() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager ?: return
        blinkJob = serviceScope.launch(Dispatchers.IO) {
            try {
                // Pick a camera that actually has a flash unit (index 0 may be the front camera).
                val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (cameraId != null) {
                    // The finally runs even on CancellationException (e.g. STOP/onDestroy during
                    // a blink), so the torch is ALWAYS forced off as this coroutine's last action.
                    try {
                        for (i in 1..25) {
                            try {
                                cameraManager.setTorchMode(cameraId, i % 2 == 1)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error setting camera torch mode: ", e)
                            }
                            kotlinx.coroutines.delay(400L)
                        }
                    } finally {
                        runCatching { cameraManager.setTorchMode(cameraId, false) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run flashlight blinking system", e)
            }
        }
    }

    private fun calculateDistanceInKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Earth radius in KM
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    private fun buildStaticNotification(title: String, content: String): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            // Tapping the tracking notification should land on the Travel page.
            putExtra("NAV_DESTINATION", "travel")
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_SERVICE)
        }
        return builder.build()
    }

    private fun updateNotification(title: String, content: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildStaticNotification(title, content))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Resolve channel labels from resources via the locale-wrapped base context
            // (attachBaseContext) so the system Settings > Notifications labels follow the
            // active per-app locale, matching AlarmService and removing the orphan resources.
            val name = getString(com.example.R.string.channel_travel_name)
            val desc = getString(com.example.R.string.channel_travel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = desc
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand triggered")

        if (intent?.action == ACTION_STOP) {
            // Explicit stop request from the UI ("STOP ARRIVAL SENTRY"). Tear down every
            // resource defensively, then remove the foreground notification and stop the
            // service. Nothing here may throw, or the whole app could be taken down.
            Log.d(TAG, "ACTION_STOP received - tearing down service")
            _isTracking.value = false
            cleanupResources()
            try {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed removing foreground notification", e)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        // This is a user-controlled location sentry with an explicit Start/Stop UI. Do NOT
        // return START_STICKY: that would let the OS resurrect a location-tracking foreground
        // service (re-acquiring GPS + the persistent notification) after a process kill even
        // though the user never asked for it to restart. START_NOT_STICKY keeps the service
        // strictly under user control.
        return START_NOT_STICKY
    }

    // Defensive teardown shared by the explicit STOP action and onDestroy. Every step is
    // individually guarded, and lateinit fields are only touched once initialized, so an
    // early/very-fast stop can never crash the process with UninitializedPropertyAccessException.
    private fun cleanupResources() {
        // Mark teardown in progress FIRST so any location callback already queued on
        // the main looper bails out before it can re-write the static StateFlows that
        // we reset just below.
        stopped = true

        // Deterministically wait for the flashlight-blink coroutine to FULLY terminate before
        // anything else. Its finally block forces the torch off as its last action, so joining
        // here (cancelAndJoin) guarantees the synchronous force-off below happens-after the
        // coroutine's own torch writes, eliminating the last-write-wins race that could leave
        // the torch stuck ON. The blink loop's delay(400) is the cancellation point.
        try {
            blinkJob?.let { job ->
                runBlocking { job.cancelAndJoin() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed cancelling/joining flashlight blink job", e)
        }
        blinkJob = null

        // Cancel any remaining in-flight coroutines (IO DB writes) so they stop before the
        // static StateFlows below are reset and the torch is forced off.
        try {
            serviceScope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed cancelling service scope", e)
        }

        // Force the torch OFF immediately so it does not keep blinking until the loop's
        // final iteration. Guarded: the camera/flash may be unavailable.
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
            if (cameraManager != null) {
                val flashId = cameraManager.cameraIdList.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (flashId != null) {
                    cameraManager.setTorchMode(flashId, false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed forcing torch off", e)
        }

        try {
            _startLocation.value = null
            _totalTripDistanceKm.value = 0.0
            _nearestAlarm.value = null
            _distanceToNearestKm.value = -1.0
            _currentLocation.value = null
            _currentSpeedKmh.value = 0.0
            triggeredAlarmIds.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Failed resetting start location flows", e)
        }

        try {
            if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed removing location updates", e)
        }

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed releasing media player", e)
        }
        mediaPlayer = null

        try {
            arrivalWakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed releasing wake lock", e)
        }
        arrivalWakeLock = null

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed cancelling vibrator", e)
        }

        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Failed shutting down TTS", e)
        }
        textToSpeech = null
    }

    override fun onDestroy() {
        Log.d(TAG, "Service Destroyed")
        _isTracking.value = false

        cleanupResources()

        super.onDestroy()
    }
}
