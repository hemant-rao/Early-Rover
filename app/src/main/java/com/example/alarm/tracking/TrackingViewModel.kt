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
 *
 * §820 (full-screen redesign) reworked the feedback + sync layer:
 *  - [Notice] replaces the persistent status strip: auto-hiding toast payloads,
 *    latest-wins, id-guarded clear (two windows may render the same toast).
 *  - presence tracking ([onlineIds]) from WS presence/presence_snapshot frames so
 *    the member card can say "Live now" honestly.
 *  - [mySos] from the SOS poll's ``mine`` field (events excludes self).
 *  - every mutation surfaces failures (no more silent runCatching); deletes treat
 *    404 as success (the thing is already gone); [cancelRequest] maps the 409
 *    cancel/accept race to "they accepted" good news.
 *  - remote consent-graph changes (connection_removed / connection_updated /
 *    circle_updated) resync live; losing MY circle membership posts a notice.
 */
class TrackingViewModel(app: Application) : AndroidViewModel(app) {

    companion object { private const val TAG = "TrackingVM" }

    /** §820 — one auto-hiding feedback toast. Latest wins; no queue. */
    data class Notice(val id: Long, val text: String, val error: Boolean = false)

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

    private val _mySos = MutableStateFlow<SosDto?>(null)
    val mySos: StateFlow<SosDto?> = _mySos.asStateFlow()

    /** Fresher-than-REST live positions from the socket, keyed by peer user id. */
    private val _livePeers = MutableStateFlow<Map<Int, LocationDto>>(emptyMap())
    val livePeers: StateFlow<Map<Int, LocationDto>> = _livePeers.asStateFlow()

    /** §820 — who is online right now (WS presence). Cleared when the socket drops:
     *  edge-triggered offline events sent during the gap are gone forever. */
    private val _onlineIds = MutableStateFlow<Set<Int>>(emptySet())
    val onlineIds: StateFlow<Set<Int>> = _onlineIds.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    // §817 — pull-to-refresh spinner state (now the sheet-header refresh spinner).
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** §820 — first successful refresh landed (gates auto-open + empty states). */
    private val _loadedOnce = MutableStateFlow(false)
    val loadedOnce: StateFlow<Boolean> = _loadedOnce.asStateFlow()

    /** §820 — last refresh failed (drives the sticky retry banner). Clears on success. */
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** §820 — once-per-VM-session auto-open of the People sheet (VM survives the
     *  Dashboard Crossfade AND rotation; a screen-local flag re-fires per visit). */
    var autoOpenedOnce = false

    val socketConnected: StateFlow<Boolean> = TrackingBus.socket.connected
    val isSharing: StateFlow<Boolean> = EarlyRoverShareService.isSharing

    init {
        TrackingRepository.init(app)
        viewModelScope.launch {
            try {
                _me.value = TrackingRepository.ensureRegistered(TrackingPrefs.displayName())
                doRefresh(false)
            } catch (e: Exception) {
                _loadFailed.value = true
                Log.w(TAG, "register/refresh failed: ${e.message}")
            }
        }
        // Live socket events → update state.
        viewModelScope.launch {
            TrackingBus.socket.events.collect { handleEvent(it) }
        }
        // Socket drop → presence unknown = offline (mirrors the web store).
        viewModelScope.launch {
            TrackingBus.socket.connected.collect { up -> if (!up) _onlineIds.value = emptySet() }
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
        viewModelScope.launch { doRefresh(showSpinner) }
    }

    private suspend fun doRefresh(showSpinner: Boolean) {
        if (showSpinner) _refreshing.value = true
        try {
            // §817 — a fresh install may reach here before the init{} registration
            // finished (or after it failed offline); re-arm identity so a refresh
            // gesture can self-heal instead of 401-looping.
            if (_me.value == null) {
                _me.value = TrackingRepository.ensureRegistered(TrackingPrefs.displayName())
            }
            val c = TrackingRepository.connections()
            _connections.value = c.connections
            _meLocation.value = c.meLocation
            _circles.value = TrackingRepository.circles().circles
            _places.value = TrackingRepository.places().places
            val s = TrackingRepository.sos()
            _sos.value = s.events
            _mySos.value = s.mine?.takeIf { it.active }
            // §820 — drop live positions the consent graph no longer lets me see
            // (peer removed me / left a circle): mirrors the web store's
            // reconcilePositions so a dead marker can't linger via livePeers.
            val visible = buildSet {
                _connections.value.forEach { if (it.status == "accepted") add(it.peerId) }
                _circles.value.forEach { circle -> circle.members.forEach { add(it.id) } }
            }
            if (_livePeers.value.keys.any { it !in visible }) {
                _livePeers.value = _livePeers.value.filterKeys { it in visible }
            }
            _loadedOnce.value = true
            _loadFailed.value = false
        } catch (e: Exception) {
            Log.w(TAG, "refreshAll failed: ${e.message}")
            _loadFailed.value = true
        } finally {
            if (showSpinner) _refreshing.value = false
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
                    if (uid != _me.value?.id) _onlineIds.value = _onlineIds.value + uid
                }
            }
            "presence" -> {
                val uid = o.optInt("user_id", 0)
                if (uid != 0) {
                    _onlineIds.value =
                        if (o.optBoolean("online")) _onlineIds.value + uid
                        else _onlineIds.value - uid
                }
            }
            "presence_snapshot" -> {
                // Authoritative replace (not merge) — flushes pre-drop stale entries.
                val arr = o.optJSONArray("online")
                val next = mutableSetOf<Int>()
                if (arr != null) for (i in 0 until arr.length()) next.add(arr.optInt(i))
                _onlineIds.value = next
            }
            "connection_request" -> {
                // §820 — a request arriving with the sheet closed must be more than a
                // 10dp dot: one auto-hiding toast (the row itself appears on refresh).
                val from = o.optJSONObject("from")
                val name = from?.optString("display_name")?.ifBlank { null }
                    ?: from?.optString("rover_id")?.ifBlank { null } ?: "Someone"
                postNotice("$name wants to connect — approve in People.")
                refreshAll()
            }
            "connection_accepted" -> {
                val peer = o.optJSONObject("peer")
                val name = peer?.optString("display_name")?.ifBlank { null }
                    ?: peer?.optString("rover_id")?.ifBlank { null } ?: "Someone"
                postNotice("$name accepted your request.")
                refreshAll()
            }
            // §820 — remote remove/decline/cancel or pause flip: silent resync (the
            // roster/map update IS the message; "they are not alerted" stays true).
            "connection_removed", "connection_updated" -> refreshAll()
            "circle_updated" -> {
                // Resync; if MY membership just ended (kicked / disbanded), say so —
                // a silently emptying map reads as a bug. My own leave/disband never
                // lands here (the backend notifies the OTHER members only).
                val action = o.optString("action")
                viewModelScope.launch {
                    val before = _circles.value
                    doRefresh(false)
                    val gone = before.filter { old -> _circles.value.none { it.id == old.id } }
                    for (c in gone) {
                        postNotice(
                            if (action == "disbanded") "Circle \"${c.name}\" was disbanded."
                            else "You're no longer in \"${c.name}\"."
                        )
                    }
                }
            }
            "sos" -> refreshAll()          // banner derives from _sos — never a toast
            "sos_resolved" -> refreshAll() // §821 — clear the banner live, not on next poll
            "place_alert" -> {
                val who = o.optString("name").ifBlank { "A connection" }
                val ev = o.optString("event")
                val place = o.optJSONObject("place")?.optString("name") ?: "a place"
                postNotice("$who ${if (ev == "arrived") "arrived at" else "left"} $place")
            }
        }
    }

    // ── actions ──────────────────────────────────────────────────────────────
    fun addByCode(code: String, onResult: (Boolean, String) -> Unit) = viewModelScope.launch {
        try {
            val r = TrackingRepository.requestConnection(code)
            doRefresh(false)
            onResult(true, if (r.autoAccepted) "Connected!" else "Request sent — waiting for approval.")
        } catch (e: Exception) { onResult(false, apiError(e)) }
    }

    fun respond(id: Int, accept: Boolean) = viewModelScope.launch {
        try {
            TrackingRepository.respond(id, accept)
            if (!accept) postNotice("Request declined.")
        } catch (e: Exception) {
            when (httpCode(e)) {
                404 -> postNotice("This request was withdrawn.")   // requester cancelled first
                409 -> Unit  // already handled (double-tap / second device) — refresh shows truth
                else -> postNotice(apiError(e), error = true)
            }
        }
        doRefresh(false)
    }

    /** §820 — withdraw MY pending request. Conditional delete: losing the race to
     *  their accept is GOOD news, not an error. */
    fun cancelRequest(id: Int, peerName: String) = viewModelScope.launch {
        try {
            TrackingRepository.cancelRequest(id)
            postNotice("Request cancelled.")
        } catch (e: Exception) {
            when (httpCode(e)) {
                409 -> postNotice("$peerName accepted your request — you're now connected!")
                404 -> Unit                          // already gone — outcome achieved
                else -> postNotice(apiError(e), error = true)
            }
        }
        doRefresh(false)
    }

    fun pauseConnection(id: Int, on: Boolean) = viewModelScope.launch {
        try { TrackingRepository.pause(id, on) }
        catch (e: Exception) { postNotice(apiError(e), error = true) }
        doRefresh(false)
    }

    fun removeConnection(id: Int) = viewModelScope.launch {
        try { TrackingRepository.removeConnection(id) }
        catch (e: Exception) {
            if (httpCode(e) != 404) postNotice(apiError(e), error = true)
        }
        doRefresh(false)
    }

    /** §817 — returns the fresh circle via [onResult] so the UI can surface the invite code. */
    fun createCircle(name: String, onResult: (CircleDto?, String?) -> Unit = { _, _ -> }) = viewModelScope.launch {
        try {
            val circle = TrackingRepository.createCircle(name, null).circle
            doRefresh(false)
            onResult(circle, null)
        } catch (e: Exception) { onResult(null, apiError(e)) }
    }

    fun removeMember(circleId: Int, userId: Int) = viewModelScope.launch {
        try { TrackingRepository.removeMember(circleId, userId) }
        catch (e: Exception) { postNotice(apiError(e), error = true) }
        doRefresh(false)
    }

    fun joinCircle(code: String, onResult: (Boolean, String) -> Unit) = viewModelScope.launch {
        try { TrackingRepository.joinCircle(code); doRefresh(false); onResult(true, "Joined circle.") }
        catch (e: Exception) { onResult(false, apiError(e)) }
    }

    fun leaveCircle(id: Int) = viewModelScope.launch {
        try { TrackingRepository.leaveCircle(id) }
        catch (e: Exception) {
            if (httpCode(e) != 404) postNotice(apiError(e), error = true)
        }
        doRefresh(false)
    }

    /** §820 — success is confirmed, not assumed: the caller keeps its dialog open on
     *  failure so the typed name/search isn't destroyed by an offline blip. */
    fun createPlace(name: String, lat: Double, lon: Double, radiusM: Int,
                    onResult: (Boolean, String) -> Unit = { _, _ -> }) = viewModelScope.launch {
        try {
            TrackingRepository.createPlace(name, lat, lon, radiusM)
            doRefresh(false)
            onResult(true, "Place saved: $name")
        } catch (e: Exception) { onResult(false, apiError(e)) }
    }

    fun deletePlace(id: Int) = viewModelScope.launch {
        try { TrackingRepository.deletePlace(id) }
        catch (e: Exception) {
            if (httpCode(e) != 404) postNotice(apiError(e), error = true)
        }
        doRefresh(false)
    }

    /** §820 — SOS success is confirmed, not assumed (the old unconditional "SOS sent"
     *  toast on a dead network was a false promise in an emergency). */
    fun raiseSos(lat: Double?, lon: Double?, onResult: (Boolean) -> Unit = {}) = viewModelScope.launch {
        try {
            val r = TrackingRepository.raiseSos(SosReq("sos", lat, lon, null))
            _mySos.value = r.sos
            postNotice("SOS sent to your circle.")
            doRefresh(false)
            onResult(true)
        } catch (e: Exception) {
            onResult(false)
        }
    }

    fun resolveSos(id: Int) = viewModelScope.launch {
        try {
            TrackingRepository.resolveSos(id)
            _mySos.value = null
        } catch (e: Exception) {
            if (httpCode(e) == 404) _mySos.value = null   // already resolved elsewhere
            else postNotice("Couldn't resolve the SOS — try again.", error = true)
        }
        doRefresh(false)
    }

    fun setDisplayName(name: String) = viewModelScope.launch {
        try {
            _me.value = TrackingRepository.patchMe(MePatchReq(displayName = name))
            TrackingPrefs.setDisplayName(name)
        } catch (e: Exception) { postNotice(apiError(e), error = true) }
    }

    fun setPaused(on: Boolean) = viewModelScope.launch {
        try { _me.value = TrackingRepository.patchMe(MePatchReq(paused = on)) }
        catch (e: Exception) { postNotice(apiError(e), error = true) }
    }

    fun setGhost(on: Boolean) = viewModelScope.launch {
        try { _me.value = TrackingRepository.patchMe(MePatchReq(ghost = on)) }
        catch (e: Exception) { postNotice(apiError(e), error = true) }
    }

    fun setSharePrecise(on: Boolean) = viewModelScope.launch {
        try { _me.value = TrackingRepository.patchMe(MePatchReq(sharePrecise = on)) }
        catch (e: Exception) { postNotice(apiError(e), error = true) }
    }

    // ── notices (§820 auto-hide feedback) ───────────────────────────────────
    private var noticeSeq = 0L

    /** Post an auto-hiding toast (latest wins). A fresh id even for identical text
     *  restarts the hide timer. */
    fun postNotice(text: String, error: Boolean = false) {
        _notice.value = Notice(++noticeSeq, text, error)
    }

    /** Id-guarded so a stale timer (two windows render the toast → two timers)
     *  can never kill a NEWER notice. */
    fun clearNotice(id: Long) {
        if (_notice.value?.id == id) _notice.value = null
    }

    /** §817 compat — screen helpers that already pass translated strings. */
    fun postStatus(msg: String) = postNotice(msg)

    fun startSharing() = EarlyRoverShareService.start(getApplication())
    fun stopSharing() = EarlyRoverShareService.stop(getApplication())

    private fun httpCode(e: Exception): Int? = (e as? retrofit2.HttpException)?.code()

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
