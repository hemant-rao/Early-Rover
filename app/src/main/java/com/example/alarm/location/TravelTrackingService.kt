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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class TravelTrackingService : Service(), TextToSpeech.OnInitListener {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var arrivalWakeLock: android.os.PowerManager.WakeLock? = null

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created")
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
                val location = locationResult.lastLocation ?: return
                
                // Ignore updates with accuracy worse than 50 meters to prevent GPS jitter and false triggers
                if (location.hasAccuracy() && location.accuracy > 50f) {
                    Log.d(TAG, "Ignoring location update due to high GPS jitter/poor accuracy: ${location.accuracy}m")
                    return
                }

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
                if (distance <= alarm.radiusKm && triggeredAlarmIds.add(alarm.id)) {
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

    private fun triggerArrivalAlarm(alarm: TravelAlarm, distanceKm: Int /* unused but let's take double */) {}
    
    // Proper double signature overload
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

            // 1. Permanently update destination state in database so we loop only once cleanly
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val db = AppDatabase.getDatabase(this@TravelTrackingService)
                    db.travelAlarmDao().updateTravelAlarm(alarm.copy(active = false))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update travel alarm status in db", e)
                }
            }

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
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Pick a camera that actually has a flash unit (index 0 may be the front camera).
                val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (cameraId != null) {
                    for (i in 1..25) {
                        try {
                            cameraManager.setTorchMode(cameraId, i % 2 == 1)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error setting camera torch mode: ", e)
                        }
                        kotlinx.coroutines.delay(400L)
                    }
                    // Clean up and ensure torch is OFF
                    try {
                        cameraManager.setTorchMode(cameraId, false)
                    } catch (e: Exception) {
                        e.printStackTrace()
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
            val name = "Travel Intelligent Watchdog"
            val desc = "Keeps secure location tracking active while commuter is resting"
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

        return START_STICKY
    }

    // Defensive teardown shared by the explicit STOP action and onDestroy. Every step is
    // individually guarded, and lateinit fields are only touched once initialized, so an
    // early/very-fast stop can never crash the process with UninitializedPropertyAccessException.
    private fun cleanupResources() {
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
