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
import androidx.core.app.NotificationCompat
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

    companion object {
        private const val TAG = "TravelTrackingService"
        const val CHANNEL_ID = "TRAVEL_TRACKING_LOCATION_CHANNEL"
        const val NOTIFICATION_ID = 23948

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
            val intent = Intent(context, TravelTrackingService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created")
        _isTracking.value = true
        _statusMessage.value = "Locating GPS Satellite..."

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        textToSpeech = TextToSpeech(this, this)
        initVibrator()
        createNotificationChannel()

        // Build elegant foreground persistent safety notification
        startForeground(NOTIFICATION_ID, buildStaticNotification("Securing transit path...", "Searching location signals"))

        startLocationTracking()
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

                // Proximity threshold penetrated!
                if (distance <= alarm.radiusKm) {
                    triggerArrivalAlarm(alarm, distance)
                }
            }

            _nearestAlarm.value = closestAlarm
            _distanceToNearestKm.value = minDistance

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
            val wakeLock = powerManager.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "SolarisTravelAlarm::WakeLockTag"
            )
            try {
                wakeLock.acquire(15000L) // hold wakelock for 15 seconds to ensure ring
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
                val cameraIdList = cameraManager.cameraIdList
                if (cameraIdList.isNotEmpty()) {
                    val cameraId = cameraIdList[0] // usually camera index 0 is first back camera with flash
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
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
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
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service Destroyed")
        _isTracking.value = false
        
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        
        vibrator?.cancel()

        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null

        super.onDestroy()
    }
}
