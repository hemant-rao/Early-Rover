package com.example.alarm.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.Manifest
import com.example.MainActivity
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * §806 — battery-safe foreground service that shares this device's location with
 * the user's connections/circles while "Sharing" is ON. Modeled on
 * [com.example.alarm.location.TravelTrackingService] (FGS type ``location`` is
 * already declared in the manifest).
 *
 * Best practice (Android docs): PRIORITY_BALANCED_POWER_ACCURACY + a modest
 * interval + batched delivery, NOT sustained HIGH_ACCURACY (which drains battery
 * and is throttled in the background anyway). Each fix is relayed to the backend
 * via the shared [TrackingBus] socket when connected, else the REST /location
 * fallback (the backend fans out to peers either way).
 */
class EarlyRoverShareService : Service() {

    private lateinit var fused: FusedLocationProviderClient
    private var callback: LocationCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var stopped = false

    companion object {
        private const val TAG = "EarlyRoverShare"
        const val CHANNEL_ID = "EARLY_ROVER_SHARE_CHANNEL"
        const val NOTIFICATION_ID = 80601
        const val ACTION_STOP = "com.example.alarm.tracking.action.STOP"

        private val _isSharing = MutableStateFlow(false)
        val isSharing = _isSharing.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, EarlyRoverShareService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, EarlyRoverShareService::class.java).apply { action = ACTION_STOP }
            try { context.startService(intent) } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            return START_NOT_STICKY
        }
        return START_STICKY  // keep sharing across process pressure while ON
    }

    override fun onCreate() {
        super.onCreate()
        stopped = false
        _isSharing.value = true
        try {
            fused = LocationServices.getFusedLocationProviderClient(this)
            createChannel()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID,
                buildNotification(), type)
            TrackingBus.acquire()
            startUpdates()
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            _isSharing.value = false
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "no location permission")
            return
        }
        // Balanced power; deliver ~every 12s, batch up to 30s to save battery.
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 12_000L)
            .setMinUpdateIntervalMillis(8_000L)
            .setMaxUpdateDelayMillis(30_000L)
            .setWaitForAccurateLocation(false)
            .build()
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (stopped) return
                val loc = result.lastLocation ?: return
                relay(loc.latitude, loc.longitude,
                    if (loc.hasBearing()) loc.bearing.toDouble() else null,
                    if (loc.hasSpeed()) loc.speed.toDouble() else null,
                    if (loc.hasAccuracy()) loc.accuracy.toDouble() else null)
            }
        }
        try {
            fused.requestLocationUpdates(request, callback!!, Looper.getMainLooper())
        } catch (e: Exception) {
            Log.e(TAG, "requestLocationUpdates failed", e)
        }
    }

    private fun relay(lat: Double, lon: Double, heading: Double?, speed: Double?, accuracy: Double?) {
        val battery = batteryPct()
        val charging = isCharging()
        val sock = TrackingBus.socket
        if (sock.connected.value) {
            sock.sendLocation(lat, lon, heading, speed, accuracy, battery, charging)
        } else {
            // REST fallback — backend updates last-known + fans out to peers.
            scope.launch {
                try {
                    TrackingRepository.postLocation(
                        LocationReq(lat, lon, accuracy, heading, speed, battery, charging))
                } catch (e: Exception) {
                    Log.w(TAG, "REST location fallback failed: ${e.message}")
                }
            }
        }
    }

    private fun batteryPct(): Int? {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        } catch (_: Exception) { null }
    }

    private fun isCharging(): Boolean? {
        return try {
            val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            plugged > 0
        } catch (_: Exception) { null }
    }

    private fun teardown() {
        stopped = true
        _isSharing.value = false
        try { callback?.let { fused.removeLocationUpdates(it) } } catch (_: Exception) {}
        callback = null
        TrackingBus.release()
        try { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() } catch (_: Exception) {}
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopped) teardown()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "Location sharing", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Shows while you're sharing your live location." })
            }
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EarlyRoverShareService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Sharing your location")
            .setContentText("Your circle can see where you are.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
