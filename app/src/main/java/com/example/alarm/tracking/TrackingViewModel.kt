package com.example.alarm.tracking

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * §806 — orchestrates the Early Rover tracking screen: registers the Rover ID,
 * loads the consent graph / circles / places / SOS over REST, subscribes to the
 * shared live socket, and drives the foreground share service. Plain AndroidViewModel
 * obtained via ``viewModel()`` (no DI — matches AlarmViewModel).
 */
class TrackingViewModel(app: Application) : AndroidViewModel(app) {

    companion object { private const val TAG = "TrackingVM" }

    private val _me = MutableStateFlow<UserDto?>(null)
    val me: StateFlow<UserDto?> = _me.asStateFlow()

    private val _connections = MutableStateFlow<List<ConnectionDto>>(emptyList())
    val connections: StateFlow<List<ConnectionDto>> = _connections.asStateFlow()

    private val _meLocation = MutableStateFlow<LocationDto?>(null)
    val meLocation: StateFlow<LocationDto?> = _meLocation.asStateFlow()

    private val _circles = MutableStateFlow<List<CircleDto>>(emptyList())
    val circles: StateFlow<List<CircleDto>> = _circles.asStateFlow()

    private val _places = MutableStateFlow<List<PlaceDto>>(emptyList())
    val places: StateFlow<List<PlaceDto>> = _places.asStateFlow()

    private val _sos = MutableStateFlow<List<SosDto>>(emptyList())
    val sos: StateFlow<List<SosDto>> = _sos.asStateFlow()

    /** Fresher-than-REST live positions from the socket, keyed by peer user id. */
    private val _livePeers = MutableStateFlow<Map<Int, LocationDto>>(emptyMap())
    val livePeers: StateFlow<Map<Int, LocationDto>> = _livePeers.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    // §817 — pull-to-refresh spinner state.
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val socketConnected: StateFlow<Boolean> = TrackingBus.socket.connected
    val isSharing: StateFlow<Boolean> = EarlyRoverShareService.isSharing

    init {
        TrackingRepository.init(app)
        viewModelScope.launch {
            try {
                _me.value = TrackingRepository.ensureRegistered(TrackingPrefs.displayName())
                refreshAll()
            } catch (e: Exception) {
                _status.value = "Couldn't connect. Check your internet."
                Log.w(TAG, "register/refresh failed: ${e.message}")
            }
        }
        // Live socket events → update state.
        viewModelScope.launch {
            TrackingBus.socket.events.collect { handleEvent(it) }
        }
    }

    /** Call from the screen's ON_START — opens the socket + refreshes. */
    fun onScreenStart() {
        TrackingBus.acquire()
        refreshAll()
    }

    /** Call from the screen's ON_STOP — releases the socket (closes if nobody else needs it). */
    fun onScreenStop() {
        TrackingBus.release()
    }

    fun refreshAll(showSpinner: Boolean = false) {
        viewModelScope.launch {
            if (showSpinner) _refreshing.value = true
            try {
                // §817 — a fresh install may reach here before the init{} registration
                // finished (or after it failed offline); re-arm identity so the pull-to-
                // refresh gesture can self-heal instead of 401-looping.
                if (_me.value == null) {
                    _me.value = TrackingRepository.ensureRegistered(TrackingPrefs.displayName())
                }
                val c = TrackingRepository.connections()
                _connections.value = c.connections
                _meLocation.value = c.meLocation
                _circles.value = TrackingRepository.circles().circles
                _places.value = TrackingRepository.places().places
                _sos.value = TrackingRepository.sos().events
            } catch (e: Exception) {
                Log.w(TAG, "refreshAll failed: ${e.message}")
                if (showSpinner) _status.value = "Couldn't refresh. Check your internet."
            } finally {
                if (showSpinner) _refreshing.value = false
            }
        }
    }

    private fun handleEvent(o: JSONObject) {
        when (o.optString("type")) {
            "location" -> {
                val uid = o.optInt("user_id", 0)
                if (uid != 0 && o.has("lat") && o.has("lon")) {
                    _livePeers.value = _livePeers.value.toMutableMap().apply {
                        put(uid, LocationDto(
                            lat = o.optDouble("lat"), lon = o.optDouble("lon"),
                            heading = o.optDouble("heading").takeIf { o.has("heading") },
                            speed = o.optDouble("speed").takeIf { o.has("speed") },
                            battery = if (o.has("battery")) o.optInt("battery") else null))
                    }
                }
            }
            "connection_request", "connection_accepted" -> refreshAll()
            "sos" -> {
                _status.value = (o.optJSONObject("user")?.optString("display_name") ?: "Someone") + " raised an SOS!"
                refreshAll()
            }
            "place_alert" -> {
                val ev = o.optString("event")
                val place = o.optJSONObject("place")?.optString("name") ?: "a place"
                _status.value = "A connection ${if (ev == "arrived") "arrived at" else "left"} $place"
            }
        }
    }

    // ── actions ──────────────────────────────────────────────────────────────
    fun addByCode(code: String, onResult: (Boolean, String) -> Unit) = viewModelScope.launch {
        try {
            val r = TrackingRepository.requestConnection(code)
            refreshAll()
            onResult(true, if (r.autoAccepted) "Connected!" else "Request sent — waiting for approval.")
        } catch (e: Exception) { onResult(false, apiError(e)) }
    }

    fun respond(id: Int, accept: Boolean) = viewModelScope.launch {
        runCatching { TrackingRepository.respond(id, accept) }; refreshAll()
    }

    fun pauseConnection(id: Int, on: Boolean) = viewModelScope.launch {
        runCatching { TrackingRepository.pause(id, on) }; refreshAll()
    }

    fun removeConnection(id: Int) = viewModelScope.launch {
        runCatching { TrackingRepository.removeConnection(id) }; refreshAll()
    }

    /** §817 — returns the fresh circle via [onResult] so the UI can surface the invite code. */
    fun createCircle(name: String, onResult: (CircleDto?, String?) -> Unit = { _, _ -> }) = viewModelScope.launch {
        try {
            val circle = TrackingRepository.createCircle(name, null).circle
            refreshAll()
            onResult(circle, null)
        } catch (e: Exception) { onResult(null, apiError(e)) }
    }

    fun removeMember(circleId: Int, userId: Int) = viewModelScope.launch {
        runCatching { TrackingRepository.removeMember(circleId, userId) }; refreshAll()
    }

    fun joinCircle(code: String, onResult: (Boolean, String) -> Unit) = viewModelScope.launch {
        try { TrackingRepository.joinCircle(code); refreshAll(); onResult(true, "Joined circle.") }
        catch (e: Exception) { onResult(false, apiError(e)) }
    }

    fun leaveCircle(id: Int) = viewModelScope.launch {
        runCatching { TrackingRepository.leaveCircle(id) }; refreshAll()
    }

    fun createPlace(name: String, lat: Double, lon: Double, radiusM: Int) = viewModelScope.launch {
        runCatching { TrackingRepository.createPlace(name, lat, lon, radiusM) }; refreshAll()
    }

    fun deletePlace(id: Int) = viewModelScope.launch {
        runCatching { TrackingRepository.deletePlace(id) }; refreshAll()
    }

    fun raiseSos(lat: Double?, lon: Double?) = viewModelScope.launch {
        runCatching { TrackingRepository.raiseSos(SosReq("sos", lat, lon, null)) }
        _status.value = "SOS sent to your circle."
        refreshAll()
    }

    fun resolveSos(id: Int) = viewModelScope.launch {
        runCatching { TrackingRepository.resolveSos(id) }; refreshAll()
    }

    fun setDisplayName(name: String) = viewModelScope.launch {
        runCatching {
            _me.value = TrackingRepository.patchMe(MePatchReq(displayName = name))
            TrackingPrefs.setDisplayName(name)
        }
    }

    fun setPaused(on: Boolean) = viewModelScope.launch {
        runCatching { _me.value = TrackingRepository.patchMe(MePatchReq(paused = on)) }
    }

    fun setGhost(on: Boolean) = viewModelScope.launch {
        runCatching { _me.value = TrackingRepository.patchMe(MePatchReq(ghost = on)) }
    }

    fun setSharePrecise(on: Boolean) = viewModelScope.launch {
        runCatching { _me.value = TrackingRepository.patchMe(MePatchReq(sharePrecise = on)) }
    }

    /** §817 — status line helper so screen actions can toast through the same strip. */
    fun postStatus(msg: String) { _status.value = msg }

    fun startSharing() = EarlyRoverShareService.start(getApplication())
    fun stopSharing() = EarlyRoverShareService.stop(getApplication())

    fun clearStatus() { _status.value = null }

    private fun apiError(e: Exception): String {
        // Retrofit HttpException carries the backend {error:{message}} body.
        return try {
            val he = e as? retrofit2.HttpException
            val raw = he?.response()?.errorBody()?.string()
            if (raw != null) {
                val msg = JSONObject(raw).optJSONObject("detail")?.optJSONObject("error")?.optString("message")
                if (!msg.isNullOrBlank()) msg else "Something went wrong."
            } else "Network error."
        } catch (_: Exception) { "Something went wrong." }
    }
}
