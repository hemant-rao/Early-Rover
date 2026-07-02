package com.example.alarm.tracking

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * §806 — Early Rover realtime client over ``okhttp3.WebSocket`` (OkHttp is already
 * a project dependency — no new libs). Connects to {server}/ws/earlyrover with the
 * bearer token as an Authorization header, auto-reconnects with backoff, sends a
 * heartbeat, and emits every inbound JSON event on [events]. Outbound: [sendLocation].
 *
 * Deliberately transport-only + reusable — it knows nothing about the UI. The
 * ViewModel collects [events] and updates state.
 */
class TrackingSocket {

    companion object { private const val TAG = "TrackingSocket" }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)   // OkHttp-level ping keeps the socket warm
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)  // long-lived
        .build()

    private var ws: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    @Volatile private var closedByUser = false
    @Volatile private var attempt = 0

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val events: SharedFlow<JSONObject> = _events.asSharedFlow()

    fun start() {
        if (loopJob != null) return
        closedByUser = false
        loopJob = scope.launch {
            while (!closedByUser) {
                connectOnce()
                if (closedByUser) break
                // Backoff: 2s, 4s, 8s … capped at 30s.
                val backoff = (2000L shl attempt.coerceAtMost(4)).coerceAtMost(30000L)
                attempt++
                delay(backoff)
            }
        }
    }

    fun stop() {
        closedByUser = true
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        _connected.value = false
        loopJob?.cancel()
        loopJob = null
    }

    /** Send a location frame (no-op if the socket isn't open). */
    fun sendLocation(lat: Double, lon: Double, heading: Double?, speed: Double?,
                     accuracy: Double?, battery: Int?, isCharging: Boolean?) {
        val sock = ws ?: return
        val o = JSONObject()
            .put("type", "location").put("lat", lat).put("lon", lon)
            .put("ts", System.currentTimeMillis())
        heading?.let { o.put("heading", it) }
        speed?.let { o.put("speed", it) }
        accuracy?.let { o.put("accuracy", it) }
        battery?.let { o.put("battery", it) }
        isCharging?.let { o.put("is_charging", it) }
        try { sock.send(o.toString()) } catch (_: Exception) {}
    }

    private suspend fun connectOnce() {
        val token = TrackingPrefs.token
        if (token.isNullOrBlank()) { delay(1500); return }
        val req = Request.Builder()
            .url(TrackingRepository.socketUrl())
            .addHeader("Authorization", "Bearer $token")
            .build()
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "connected")
                attempt = 0
                _connected.value = true
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { _events.tryEmit(JSONObject(text)) } catch (_: Exception) {}
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _connected.value = false
                try { webSocket.close(1000, null) } catch (_: Exception) {}
                if (!done.isCompleted) done.complete(Unit)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failure: ${t.message}")
                _connected.value = false
                if (!done.isCompleted) done.complete(Unit)
            }
        }
        ws = client.newWebSocket(req, listener)
        done.await()          // suspend until this connection drops → outer loop reconnects
        _connected.value = false
    }
}
