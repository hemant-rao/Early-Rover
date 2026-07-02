<!-- Authored 2026-07-02 (§808 follow-up). Compile-review spec — founder builds the APK. NOT auto-committed. -->

# Early Rover — Android Upgrade Spec (§807 pairing + §808 moderation + robustness)

Target repo: `com.example.alarm.tracking` (separate repo; founder builds the APK). Package under review: `app/src/main/java/com/example/alarm/tracking/`. All code below matches the existing Moshi `@JsonClass`, `object`-singleton repo, `AndroidViewModel`, and Compose+`translate()` conventions already in the package. No new libraries — OkHttp, Retrofit/Moshi, Compose Material3, and Play Services location are all already dependencies.

---

## 1. Executive summary

**Current state.** The tracking package is a clean, login-less identity + live-map client. `TrackingApi`/`TrackingModels`/`TrackingRepository` cover register/me/connections/circles/places/location/sos/push-token with good DTO parity and Moshi unknown-key tolerance. `TrackingSocket` is an OkHttp WebSocket with reconnect/backoff, `TrackingBus` ref-counts one shared socket between the map ViewModel and the FGS, and `EarlyRoverShareService` is a correctly-declared Android 14 `location` foreground service (balanced-power GPS, ~12s cadence, well under the server's 1-frame/2s guard). The happy path is solid.

**The 3 most important upgrades:**

1. **P0 — §807 "Link Web" device pairing is 100% absent** (no `/pair/start` endpoint, DTO, repo/VM method, or UI). This is the explicitly-flagged #1 follow-up gap: the phone is the only authed party that can mint the 6-char Crockford code the user types at `odiobook.com/early-rover/app`. Zero client support today.
2. **P1 — §808 blocked-account handling is missing and actively harmful.** A blocked socket hot-loops reconnect forever (worse: `onOpen` resets `attempt=0` on every accepted-then-closed socket, so backoff never grows → reconnect every ~2s indefinitely), the FGS never stops, and `ensureRegistered` re-registers on *any* `me()` failure — minting a fresh un-blocked identity and thereby bypassing moderation. REST 403s are swallowed in `refreshAll`; there's no "account unavailable" state.
3. **P2/P3 — robustness + UX polish:** no app-level `{type:ping}` keepalive against the 120s idle timeout; background-location requested in the same batch as foreground (silently denied on Android 11+); SOS is raise-only (no confirm, no resolve, peer SOS invisible); `share_precise` unexposed; presence/`location_stopped` events dropped so offline peers look frozen-but-live; `rover_id` vs `rover_code` handle mismatch risk.

---

## 2. P0 — §807 "Link Web" device pairing

The phone calls **`POST /pair/start` (authed)** and displays `pair.code` + a live TTL countdown; the browser does `/pair/claim`. The app only needs `/pair/start`.

### 2.1 DTOs — `TrackingModels.kt`

Add near the identity block (after `MeResp`, line 42). We deliberately **omit `rover_id`** from the response DTO — we don't render it, and its JSON type is ambiguous across the two reviews (int vs string); Moshi's unknown-key tolerance drops it safely and avoids a `JsonDataException` on a type mismatch.

```kotlin
// ── §807 device pairing (Link Web) ──────────────────────────────────────────
@JsonClass(generateAdapter = true)
data class PairDto(
    val code: String = "",
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "ttl_seconds") val ttlSeconds: Int = 300
)

@JsonClass(generateAdapter = true)
data class PairStartResp(val pair: PairDto = PairDto())
```

### 2.2 Endpoint — `TrackingApi.kt`

Add after `pushToken` (line 78). No body — the bearer token identifies the rover.

```kotlin
@POST("api/earlyrover/v1/pair/start")
suspend fun pairStart(): PairStartResp
```

### 2.3 Repository — `TrackingRepository.kt`

Add beside the other one-liners (after `pushToken`, line 115):

```kotlin
suspend fun pairStart(): PairStartResp = io { api().pairStart() }
```

### 2.4 ViewModel — `TrackingViewModel.kt`

Add a `pairCode` state + action. Reuse the existing `apiError()` path so a §808 403 or a 429 renders the same friendly copy (see §3.3).

```kotlin
// state — add beside the other MutableStateFlows (near line 45)
private val _pairCode = MutableStateFlow<PairDto?>(null)
val pairCode: StateFlow<PairDto?> = _pairCode.asStateFlow()

// action — add in the "actions" block (near line 121)
fun startPairing() = viewModelScope.launch {
    try {
        _pairCode.value = TrackingRepository.pairStart().pair
    } catch (e: Exception) {
        onApiError(e)                 // classifies 403/429 (see §3)
        _status.value = apiError(e)
    }
}

fun clearPairCode() { _pairCode.value = null }
```

### 2.5 Compose UI — `GroupTrackingScreen.kt`

**Entry point.** Add a "Link web" `OutlinedButton` in the **My sharing** section, directly beside the existing "Set my name" button (inside the `me?.let { u -> … }` block, around line 230):

```kotlin
OutlinedButton(onClick = { vm.startPairing() }, Modifier.padding(top = 8.dp)) {
    Icon(Icons.Default.Computer, null, Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp)); Text(translate("Link web / open on website"))
}
```

**Collect the state** near the other `collectAsStateWithLifecycle()` calls (top of the composable, ~line 68):

```kotlin
val pairCode by vm.pairCode.collectAsStateWithLifecycle()
```

**The dialog** — add to the `// ── dialogs ──` block (after the `showAddPlace` dialog, ~line 262). It shows the code big, the website URL, and a live countdown seeded from `ttlSeconds`; on expiry it auto-dismisses. Style matches the existing `AlertDialog`/`InputDialog` usage.

```kotlin
pairCode?.let { pc -> PairDialog(pc, translate, onDismiss = { vm.clearPairCode() }) }
```

**The composable** — add to the `// ── components ──` section (e.g. after `RoverIdChip`, line 279):

```kotlin
@Composable
private fun PairDialog(pair: PairDto, translate: (String) -> String, onDismiss: () -> Unit) {
    val clip = LocalClipboardManager.current
    // Live TTL countdown seeded from ttl_seconds; ticks down once a second.
    var remaining by remember(pair.code) { mutableStateOf(pair.ttlSeconds) }
    LaunchedEffect(pair.code) {
        while (remaining > 0) { kotlinx.coroutines.delay(1000L); remaining-- }
        onDismiss()   // code expired — close so the user re-requests a fresh one
    }
    val mins = remaining / 60
    val secs = remaining % 60
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(translate("Link web / open on website")) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(translate("Enter this code at"), fontSize = 13.sp, color = Color.Gray)
                Text("odiobook.com/early-rover/app", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Surface(shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.clickable { clip.setText(AnnotatedString(pair.code)) }) {
                    Text(pair.code, Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = 4.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (remaining > 0)
                        translate("Expires in ") + String.format("%d:%02d", mins, secs)
                    else translate("Expired — tap Link web again"),
                    fontSize = 12.sp,
                    color = if (remaining <= 30) Color(0xFFEF4444) else Color.Gray
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(translate("Done")) } }
    )
}
```

Add the import `androidx.compose.material.icons.filled.Computer` is covered by the existing wildcard `import androidx.compose.material.icons.filled.*` (line 16). `LaunchedEffect`, `mutableStateOf` are covered by `runtime.*` (line 18).

**Effort: M.** Two DTOs, one endpoint, one repo line, one VM action + state, one button + one dialog composable.

---

## 3. P1 — §808 blocked-account handling + robustness

Three defects must be fixed together: (a) the socket hot-loop, (b) identity churn in `ensureRegistered`, (c) no terminal "account unavailable" state / swallowed 403s. Plus the 429 harvest-cap copy.

### 3.1 Socket: detect the blocked close, stop the loop — `TrackingSocket.kt`

The root cause of the hot-loop is twofold: `onOpen` resets `attempt = 0` (line 107) *even for a socket the server immediately closes*, so backoff is recomputed as `2000 shl 0 = 2s` forever; and nothing marks a close as permanent (`closedByUser` is only set by `stop()`). Fix: peek the inbound type, set a `@Volatile permanentStop`, expose a `blocked` StateFlow, and guard the loop.

Add fields (near line 47):

```kotlin
@Volatile private var permanentStop = false

private val _blocked = MutableStateFlow(false)
val blocked: StateFlow<Boolean> = _blocked.asStateFlow()
```

Replace `start()` (lines 56–69) — guard the loop on `permanentStop`, reset it on a fresh start, and don't reset `attempt` inside `onOpen` for a socket that gets closed immediately (we increment backoff in the loop regardless; `onOpen` should only reset once we've actually *stayed* connected — simplest safe fix is to keep the reset but rely on `permanentStop` to break the loop):

```kotlin
fun start() {
    if (loopJob != null) return
    closedByUser = false
    permanentStop = false
    loopJob = scope.launch {
        while (!closedByUser && !permanentStop) {
            connectOnce()
            if (closedByUser || permanentStop) break
            // Backoff: 2s, 4s, 8s … capped at 30s.
            val backoff = (2000L shl attempt.coerceAtMost(4)).coerceAtMost(30000L)
            attempt++
            delay(backoff)
        }
    }
}
```

Update `stop()` to also clear the blocked flag on a genuine user stop is **not** wanted (a blocked account should stay blocked); leave `stop()` as-is but it already sets `closedByUser = true`, which combined with `permanentStop` halts everything.

Replace `onMessage` (lines 109–111) to intercept the terminal signal:

```kotlin
override fun onMessage(webSocket: WebSocket, text: String) {
    val o = try { JSONObject(text) } catch (_: Exception) { return }
    if (o.optString("type") == "location_stopped" && o.optString("reason") == "blocked") {
        permanentStop = true              // stop the reconnect loop for good
        _blocked.value = true
        _connected.value = false
        try { webSocket.close(1000, "blocked") } catch (_: Exception) {}
    }
    _events.tryEmit(o)                     // still surface it so the VM can react
}
```

### 3.2 App-level keepalive + `?token=` fallback — `TrackingSocket.kt`

Two robustness fixes folded into `connectOnce` (lines 95–126):

- **`?token=` query param** as belt-and-suspenders (some proxies strip `Authorization` on the WS upgrade; the contract lists `?token=` first). Keep the header too.
- **App-level `{type:ping}`** every ~35s while connected (well under the server's 120s idle timeout; OkHttp's protocol ping at line 39 does *not* reset a FastAPI receive-loop idle timer). Cancel the ping when the socket drops.

```kotlin
private var pingJob: Job? = null

private suspend fun connectOnce() {
    val token = TrackingPrefs.token
    if (token.isNullOrBlank()) { delay(1500); return }
    val url = TrackingRepository.socketUrl() +
        "?token=" + java.net.URLEncoder.encode(token, "UTF-8")
    val req = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer $token")
        .build()
    val done = kotlinx.coroutines.CompletableDeferred<Unit>()
    val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "connected")
            attempt = 0
            _connected.value = true
            pingJob = scope.launch {
                while (_connected.value) {
                    delay(35_000L)
                    try { webSocket.send(JSONObject().put("type", "ping").toString()) }
                    catch (_: Exception) {}
                }
            }
        }
        override fun onMessage(webSocket: WebSocket, text: String) { /* §3.1 body */ }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            _connected.value = false
            pingJob?.cancel(); pingJob = null
            try { webSocket.close(1000, null) } catch (_: Exception) {}
            if (!done.isCompleted) done.complete(Unit)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failure: ${t.message}")
            _connected.value = false
            pingJob?.cancel(); pingJob = null
            if (!done.isCompleted) done.complete(Unit)
        }
    }
    ws = client.newWebSocket(req, listener)
    done.await()
    _connected.value = false
    pingJob?.cancel(); pingJob = null
}
```

Note `"pong"` needs no handling — it's already just another inbound event the VM ignores.

### 3.3 Narrow `ensureRegistered` — `TrackingRepository.kt`

Replace the over-broad `catch (e: Exception)` (lines 74–89). Re-register **only** on a genuinely invalid token (401/404). On 403 (blocked) rethrow so the VM shows "account unavailable"; on 5xx/IO keep the stored identity and rethrow so a transient blip never mints a new rover.

```kotlin
suspend fun ensureRegistered(displayName: String?): UserDto = withContext(Dispatchers.IO) {
    if (TrackingPrefs.isRegistered) {
        try {
            return@withContext api().me().user
        } catch (e: retrofit2.HttpException) {
            when (e.code()) {
                401, 404 -> Log.w(TAG, "token invalid (${e.code()}) — re-registering")
                else -> throw e   // 403 blocked / 5xx: do NOT re-register; surface it
            }
            // fall through to register() only for a truly invalid/unknown token
        }
        // IOException / other network errors propagate → keep the stored token, retry later
    }
    val resp = api().register(
        RegisterReq(displayName = displayName, deviceSecret = TrackingPrefs.deviceSecret())
    )
    TrackingPrefs.save(resp.token, resp.user.id, resp.user.roverId, resp.user.displayName)
    synchronized(this@TrackingRepository) { _api = null }
    resp.user
}
```

### 3.4 `TrackingBus.forceClose()` — `TrackingBus.kt`

Add so a blocked account can hard-stop the shared socket regardless of ref-count:

```kotlin
@Synchronized fun forceClose() {
    refs = 0
    socket.stop()
}
```

### 3.5 ViewModel: terminal blocked state + 403/429 classification — `TrackingViewModel.kt`

Add a `blocked` state, an `onApiError` classifier, a `location_stopped` handler, and observe the socket's transport-level `blocked` flag (so a block detected purely on the socket also stops sharing even if no REST call fired).

State (near line 45):

```kotlin
private val _blocked = MutableStateFlow(false)
val blocked: StateFlow<Boolean> = _blocked.asStateFlow()
```

In `init`, observe the socket flag (after the events collector, ~line 65):

```kotlin
viewModelScope.launch {
    TrackingBus.socket.blocked.collect { if (it) enterBlocked() }
}
```

`refreshAll` — classify the swallowed exception (lines 88–90):

```kotlin
} catch (e: Exception) {
    onApiError(e)
    Log.w(TAG, "refreshAll failed: ${e.message}")
}
```

`handleEvent` — add the `location_stopped` and (see §5) presence/pong branches (in the `when` at line 95):

```kotlin
"location_stopped" -> when (o.optString("reason")) {
    "blocked" -> enterBlocked()
    "paused"  -> _status.value = "Sharing is paused — others can't see you."
    "ghost"   -> _status.value = "Ghost mode on — you're frozen at your last point."
}
"pong", "presence" -> { /* keepalive / presence handled in §5 */ }
```

New helpers (in the actions block):

```kotlin
private fun enterBlocked() {
    if (_blocked.value) return
    _blocked.value = true
    _status.value = "This Rover is unavailable."
    EarlyRoverShareService.stop(getApplication())   // stop the FGS — no more GPS/REST
    TrackingBus.forceClose()                         // stop reconnecting for good
}

private fun onApiError(e: Exception) {
    if ((e as? retrofit2.HttpException)?.code() == 403) enterBlocked()
}
```

Update `apiError` (line 193) to special-case 429 with rate-limit copy and detect the blocked 403:

```kotlin
private fun apiError(e: Exception): String {
    val he = e as? retrofit2.HttpException
    if (he?.code() == 429) return "Too many requests — please wait a minute and try again."
    if (he?.code() == 403) return "This Rover is unavailable."
    return try {
        val raw = he?.response()?.errorBody()?.string()
        if (raw != null) {
            val msg = JSONObject(raw).optJSONObject("detail")
                ?.optJSONObject("error")?.optString("message")
            if (!msg.isNullOrBlank()) msg else "Something went wrong."
        } else "Network error."
    } catch (_: Exception) { "Something went wrong." }
}
```

Also call `onApiError(e)` inside the `catch` of `addByCode`/`joinCircle` (lines 122–149) so a 403 there also flips the terminal state:

```kotlin
catch (e: Exception) { onApiError(e); onResult(false, apiError(e)) }
```

### 3.6 Full-screen "account unavailable" state — `GroupTrackingScreen.kt`

Collect the flag (top, ~line 68) and short-circuit the whole screen before the map:

```kotlin
val blocked by vm.blocked.collectAsStateWithLifecycle()
```

At the very top of the `Column` body (before the map `Box`, line 114):

```kotlin
if (blocked) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Default.Block, null, tint = Color(0xFFEF4444),
                modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(translate("Account unavailable"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text(translate("This Rover has been blocked. Please contact support."),
                fontSize = 13.sp, color = Color.Gray)
        }
    }
    return
}
```

**Effort: M** (socket L, VM/UI M, repo/bus S). This is the highest-priority correctness/battery fix.

---

## 4. P2 — battery / socket robustness

| Concern | Contract | Current | Fix |
|---|---|---|---|
| **Reconnect backoff** | no hot-loop on permanent close | caps at 30s but `attempt` reset on every accepted-then-closed open → 2s forever on block | §3.1 `permanentStop` breaks the loop; §3.5 tears down FGS |
| **Idle timeout** | 120s app-level; `{type:ping}`→`pong` | only OkHttp protocol ping (20s), which may not reset the server receive-loop timer | §3.2 app-level `{type:ping}` every 35s; `pong` ignored |
| **Frame cadence** | ≤ 1 frame / 2s; server drops paused/ghost/blocked | ~12s (`LocationRequest` 12s, min 8s) — safely under the guard | no change needed; already compliant |
| **REST fallback spam** | — | fires REST `/location` every 12s whenever socket down → 403 flood when blocked | §4.1 short-circuit relay on `blocked` |
| **Paused/ghost waste** | server drops paused/ghost frames | FGS still wakes GPS + sends every 12s | §4.2 short-circuit relay when self paused/ghost |

### 4.1 + 4.2 `EarlyRoverShareService.relay()` short-circuit — `EarlyRoverShareService.kt`

The service already has `TrackingBus.socket`; add a `blocked` guard and a paused/ghost guard at the top of `relay` (line 135). For paused/ghost, expose the self state via a small `StateFlow` the VM updates in `patchMe`, or (simplest, no plumbing) read the FGS-visible flag off the socket. Minimal version using the socket's `blocked` plus a shared self-flag:

Add to `TrackingSocket.kt` a lightweight self-mute flag the VM sets:

```kotlin
private val _selfMuted = MutableStateFlow(false)   // paused OR ghost
val selfMuted: StateFlow<Boolean> = _selfMuted.asStateFlow()
fun setSelfMuted(v: Boolean) { _selfMuted.value = v }
```

VM `setPaused`/`setGhost` (lines 180–186) push the combined state after patch:

```kotlin
fun setPaused(on: Boolean) = viewModelScope.launch {
    runCatching {
        _me.value = TrackingRepository.patchMe(MePatchReq(paused = on))
        TrackingBus.socket.setSelfMuted(_me.value?.paused == true || _me.value?.ghost == true)
    }
}
// mirror the same trailing line in setGhost
```

`relay()` guard (top of line 135):

```kotlin
private fun relay(lat: Double, lon: Double, heading: Double?, speed: Double?, accuracy: Double?) {
    if (TrackingBus.socket.blocked.value) { teardown(); return }   // §808: stop entirely
    if (TrackingBus.socket.selfMuted.value) return                 // paused/ghost: don't burn GPS/data
    ...existing socket-or-REST send...
}
```

*(Optional stronger battery win: `fused.removeLocationUpdates` while muted and re-arm on un-mute — deferred as L effort; the short-circuit above is the S/M fix.)*

**Effort: M.**

---

## 5. P3 — UX polish

### 5.1 Rover-ID handle correctness — `GroupTrackingScreen.kt`
Contract handle is **`rover_code`** ("ROVER-XXXXXXX"); the chip currently shows `it.roverId` (line 131). If the backend puts an internal/namespaced value in `rover_id`, peers can't add it. Display and copy `roverCode ?: roverId`:

```kotlin
me?.let { RoverIdChip(it.roverCode ?: it.roverId, Modifier.align(Alignment.TopStart).padding(12.dp)) }
```
**Effort: S.** (Confirm against the backend serializer which field is the shareable handle.)

### 5.2 Onboarding + share sheet — `RoverIdChip`
The chip's only affordance is a silent clipboard copy. Switch to an `ACTION_SEND` share sheet (with the web link) and add a snackbar/toast confirming, plus a first-run explainer card ("Your Rover ID — share it, approve requests"). **Effort: M.**

```kotlin
val ctx = LocalContext.current
// inside RoverIdChip's clickable:
.clickable {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT,
            "Add me on Early Rover: $roverId\nhttps://odiobook.com/early-rover/app")
    }
    ctx.startActivity(Intent.createChooser(send, null))
}
```

### 5.3 SOS clarity — confirm + resolve banner + peer SOS
SOS is raise-only (FAB fires instantly, line 134; no resolve; peer SOS invisible). Add:
- **Confirmation dialog** before `vm.raiseSos(...)` (guards accidental taps).
- **Persistent banner** collecting `vm.sos` — own active SOS → **Resolve** button (`vm.resolveSos(id)` already exists); peer SOS → name + "on map".

```kotlin
var confirmSos by remember { mutableStateOf(false) }
// FAB onClick = { confirmSos = true }
if (confirmSos) AlertDialog(
    onDismissRequest = { confirmSos = false },
    title = { Text(translate("Send SOS?")) },
    text = { Text(translate("Your circle will be alerted with your location.")) },
    confirmButton = { TextButton(onClick = {
        confirmSos = false; vm.raiseSos(deviceLat, deviceLon) }) {
        Text(translate("Send SOS"), color = Color(0xFFEF4444)) } },
    dismissButton = { TextButton(onClick = { confirmSos = false }) { Text(translate("Cancel")) } })

// banner (below the status strip, collecting vm.sos):
val sosList by vm.sos.collectAsStateWithLifecycle()
sosList.filter { it.active }.forEach { s ->
    Surface(color = Color(0xFFEF4444)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            val mine = s.user?.id == me?.id
            Text(if (mine) translate("Your SOS is active")
                 else (s.user?.displayName ?: "Someone") + translate(" raised an SOS"),
                Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold)
            if (mine) TextButton(onClick = { vm.resolveSos(s.id) }) {
                Text(translate("Resolve"), color = Color.White) }
        }
    }
}
```
Also add `resolved_at`/`resolved_by` to `SosDto` so the resolve response isn't lost:

```kotlin
// TrackingModels.kt SosDto (after createdAt, line 144)
@Json(name = "resolved_at") val resolvedAt: String? = null,
@Json(name = "resolved_by") val resolvedBy: PublicUser? = null
```
**Effort: M.**

### 5.4 Permissions UX — two-step background + real grant check — `GroupTrackingScreen.kt`
Current `requestShare` (lines 82–90) bundles `ACCESS_BACKGROUND_LOCATION` with foreground — Android 11+ silently denies it — and starts sharing on `result.values.any { it }`, so granting *only* notifications launches a dead service. Fix: request foreground+notifications first; only after fine/coarse is granted launch a **separate** background request; only start sharing when a location permission is actually granted.

```kotlin
val bgLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()) { vm.startSharing() }
val permLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()) { result ->
    val gotLoc = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                 result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    if (!gotLoc) { vm.setStatus("Location permission is needed to share.") ; return@rememberLauncherForActivityResult }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    else vm.startSharing()
}
fun requestShare() {
    val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        perms += Manifest.permission.POST_NOTIFICATIONS
    permLauncher.launch(perms.toTypedArray())
}
```
(Add `fun setStatus(msg: String) { _status.value = msg }` to the VM.) **Effort: M.**

### 5.5 Battery-optimization exemption (one-time)
Persistent FGS is throttled by Doze on aggressive OEMs. After sharing is first enabled, optionally prompt once with `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, gated on `isIgnoringBatteryOptimizations()` and a one-shot pref. **Effort: S.**

```kotlin
val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
```

### 5.6 Presence / offline peers — `TrackingViewModel.kt` + screen
`_livePeers` never expires and presence is dropped, so an offline peer stays a solid live dot forever. Track presence and grey offline peers the same way ghost peers are greyed.

```kotlin
// VM
private val _presence = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
val presence: StateFlow<Map<Int, Boolean>> = _presence.asStateFlow()
// handleEvent "presence" branch:
"presence" -> {
    val uid = o.optInt("user_id", 0)
    if (uid != 0) _presence.value = _presence.value.toMutableMap()
        .apply { put(uid, o.optBoolean("online")) }
}
// screen peerPoints (line 105): ghost = c.location?.ghost == true || presence[c.peerId] == false
```
**Effort: M.**

### 5.7 `share_precise` toggle — VM + screen
Model + PATCH already support it; only Pause/Ghost are exposed. Add a third `ToggleRow`.

```kotlin
// VM
fun setSharePrecise(on: Boolean) = viewModelScope.launch {
    runCatching { _me.value = TrackingRepository.patchMe(MePatchReq(sharePrecise = on)) }
}
// screen, in "My sharing" me?.let block:
ToggleRow(translate("Share precise location"), u.sharePrecise) { vm.setSharePrecise(it) }
```
**Effort: S.**

### 5.8 Surface add-by-code result — screen
`vm.addByCode(code) { _, _ -> }` (line 246) discards the callback, so a 429/error/success is invisible. Route it to status:

```kotlin
onConfirm = { code -> showAdd = false; vm.addByCode(code) { _, msg -> vm.setStatus(msg) } }
// likewise for joinCircle (line 254)
```
**Effort: S.**

### 5.9 Moshi explicit-null hardening (§804-class) — `TrackingModels.kt`
Moshi applies Kotlin defaults only for **absent** keys, not explicit `null` → non-null fields throw `JsonDataException`. Make plausibly-nullable peer-scoped fields nullable. Highest-risk: a non-owner circle receiving `invite_code: null`.

```kotlin
@Json(name = "invite_code") val inviteCode: String? = null,   // CircleDto (line 104)
```
Consider the same for `CircleDto.name`, `PlaceDto.name`, `SosDto.kind`, `ConnectionDto.status`, `UserDto.roverId` if the serializer could ever emit null. **Effort: S.**

### 5.10 Map polish (lower priority)
- **Re-fit camera when peers arrive** — `TrackingMapView.applyData` fits once on "me" only (`didFit` one-shot); track last-fitted count instead so peers streaming in later get framed, or add a recenter FAB.
- **Heading + distance on map** — `PeerPoint` drops heading; add `heading: Double?` and a rotated arrow `SymbolLayer`, optionally distance text.
- **Place radius picker** — `createPlace` is hard-coded to 150m (line 262); add a stepper/slider.
- **Circle member management** — `CircleRow` shows count but no member list; `removeMember` exists — add a detail sheet with remove for owners.

**Effort: M–L, deferrable.**

---

## 6. NEW — on-device location history (founder directive, 2026-07-02)

**Founder requirements (verbatim intent):** location history lives **only on the user's phone —
the server stores nothing beyond the single last-known point** (`earlyrover_locations` is a
1-row-per-user upsert; verified server-side). On the phone: keep a **rolling 7-day** trail, older
days collapse to **a few key points per day** (space-bounded, "proper history nahi"), and the user
can **delete it — single point, selected points, or all**. Never uploaded, cleared on sign-out.
The §810 web client implements the identical model (`stores/earlyroverHistory.js`) — keep the
constants in sync.

### 6.1 Store — new file `tracking/HistoryStore.kt`

File-backed (Moshi JSON in `filesDir`), thread-safe, atomic writes (temp + rename). No Room —
the app has no DB and this stays tiny by construction.

```kotlin
package com.example.alarm.tracking

import android.content.Context
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File

@JsonClass(generateAdapter = true)
data class HistoryPoint(
    val t: Long,            // epoch ms — also the row's identity for deletes
    val lat: Double,
    val lon: Double,
    val acc: Float? = null,
)

object HistoryStore {
    private const val FILE_NAME = "early_rover_history.json"
    // Record thinning: a new point only when moved >= MIN_MOVE_M OR MIN_GAP_MS elapsed.
    private const val MIN_GAP_MS = 5 * 60_000L
    private const val MIN_MOVE_M = 30.0
    // Retention: full (thinned) trail for 7 days; older days -> KEY_POINTS_PER_DAY
    // (first + last + evenly spaced); absolute cap evicts oldest days first.
    private const val FULL_WINDOW_MS = 7 * 86_400_000L
    private const val KEY_POINTS_PER_DAY = 8
    private const val HARD_CAP = 4000

    private val lock = Any()
    private var cache: MutableList<HistoryPoint>? = null
    private val adapter by lazy {
        Moshi.Builder().build()
            .adapter<List<HistoryPoint>>(Types.newParameterizedType(List::class.java, HistoryPoint::class.java))
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    private fun load(ctx: Context): MutableList<HistoryPoint> =
        cache ?: runCatching { adapter.fromJson(file(ctx).readText()) }
            .getOrNull().orEmpty().toMutableList().also { cache = it }

    private fun save(ctx: Context, list: List<HistoryPoint>) {
        cache = list.toMutableList()
        runCatching {
            val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(adapter.toJson(list))
            tmp.renameTo(file(ctx))
        }
    }

    fun all(ctx: Context): List<HistoryPoint> = synchronized(lock) { load(ctx).toList() }

    fun record(ctx: Context, lat: Double, lon: Double, acc: Float?, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val list = load(ctx)
            val last = list.lastOrNull()
            if (last != null && now - last.t < MIN_GAP_MS) {
                val moved = FloatArray(1).also {
                    android.location.Location.distanceBetween(last.lat, last.lon, lat, lon, it)
                }[0]
                if (moved < MIN_MOVE_M) return
            }
            list.add(HistoryPoint(now, lat, lon, acc))
            compact(list, now)
            save(ctx, list)
        }
    }

    fun delete(ctx: Context, ts: Set<Long>) = synchronized(lock) {
        save(ctx, load(ctx).filterNot { it.t in ts })
    }

    fun clear(ctx: Context) = synchronized(lock) { save(ctx, emptyList()) }

    /** 7-day full window; older days collapse to KEY_POINTS_PER_DAY; HARD_CAP evicts oldest. */
    private fun compact(list: MutableList<HistoryPoint>, now: Long) {
        val cutoff = now - FULL_WINDOW_MS
        val old = list.filter { it.t < cutoff }
        if (old.isNotEmpty()) {
            val kept = old.groupBy { it.t / 86_400_000L }.values.flatMap { day ->
                if (day.size <= KEY_POINTS_PER_DAY) day
                else {
                    val step = (day.size - 1).toDouble() / (KEY_POINTS_PER_DAY - 1)
                    (0 until KEY_POINTS_PER_DAY).map { day[Math.round(it * step).toInt()] }.distinct()
                }
            }
            list.removeAll { it.t < cutoff }
            list.addAll(0, kept.sortedBy { it.t })
        }
        while (list.size > HARD_CAP) list.removeAt(0)
    }
}
```

### 6.2 Wiring

- **Record:** in `EarlyRoverShareService`'s location callback (the point where a fix is relayed),
  add `HistoryStore.record(applicationContext, loc.latitude, loc.longitude, loc.accuracy)` —
  records even when the relay is muted (paused/ghost still moves the *user's own* device history;
  it is local-only). If product prefers "history == what I shared", move the call after the mute check.
- **Sign-out / identity reset:** wherever `TrackingPrefs` clears the token, call `HistoryStore.clear(ctx)`.
- **Never uploaded:** grep-guard — `HistoryStore` must have no reference in `TrackingApi`/`TrackingSocket`.

### 6.3 UI — history sheet in `GroupTrackingScreen.kt`

A "History" entry (Settings section or top-bar icon `Icons.Default.History`) opening a bottom sheet:
- Header: point count + "Only on this phone — never uploaded" caption + `Select` / `Clear all` actions.
- `LazyColumn` grouped by day (sticky day headers), each row: time (`HH:mm`), lat/lon (5 dp),
  accuracy chip; tap → center the map on that point; trailing delete icon (single delete).
- Select mode: leading checkboxes + bottom "Delete (n)" button (`HistoryStore.delete(ctx, selected)`).
- "Clear all" behind an `AlertDialog` confirm.
- Optional polish: a `LineLayer` trail of the visible day on `TrackingMapView` (matches the web's
  "Show trail" toggle).

**Effort: M.** Checklist rows H-1…H-3 below.

---

## 7. Prioritized checklist

| # | Recommendation | Sev | Effort | File(s) |
|---|---|---|---|---|
| P0-1 | `/pair/start` endpoint + `PairDto`/`PairStartResp` | high | S | `TrackingApi.kt`, `TrackingModels.kt` |
| P0-2 | `pairStart()` repo + `startPairing()`/`pairCode` VM | high | S | `TrackingRepository.kt`, `TrackingViewModel.kt` |
| P0-3 | "Link web" button + `PairDialog` (code + TTL countdown + URL) | high | M | `GroupTrackingScreen.kt` |
| P1-1 | Socket: `permanentStop` + `blocked` flow on `location_stopped{blocked}`; stop hot-loop | critical | L | `TrackingSocket.kt` |
| P1-2 | Narrow `ensureRegistered` to 401/404; no identity churn/bypass | high | S | `TrackingRepository.kt` |
| P1-3 | VM terminal blocked state + `enterBlocked()`/`onApiError`; observe socket flag | critical | M | `TrackingViewModel.kt` |
| P1-4 | `TrackingBus.forceClose()` | high | S | `TrackingBus.kt` |
| P1-5 | Full-screen "account unavailable" UI | high | S | `GroupTrackingScreen.kt` |
| P1-6 | 429 `TOO_MANY_REQUESTS` + 403 friendly copy in `apiError` | low | S | `TrackingViewModel.kt` |
| P2-1 | App-level `{type:ping}` keepalive (35s) vs 120s idle | med | S | `TrackingSocket.kt` |
| P2-2 | `?token=` WS query fallback (keep header) | low | S | `TrackingSocket.kt` |
| P2-3 | `relay()` short-circuit on blocked (teardown) + paused/ghost (`selfMuted`) | med | M | `EarlyRoverShareService.kt`, `TrackingSocket.kt`, `TrackingViewModel.kt` |
| P3-1 | Display/copy `roverCode ?: roverId` | high | S | `GroupTrackingScreen.kt` |
| P3-2 | SOS confirm dialog + resolve banner + peer SOS; `resolvedAt`/`resolvedBy` | high | M | `GroupTrackingScreen.kt`, `TrackingModels.kt` |
| P3-3 | Two-step bg-location perms + real grant check | high | M | `GroupTrackingScreen.kt`, `TrackingViewModel.kt` |
| P3-4 | Presence tracking → grey offline peers | high | M | `TrackingViewModel.kt`, `GroupTrackingScreen.kt` |
| P3-5 | `share_precise` toggle | med | S | `TrackingViewModel.kt`, `GroupTrackingScreen.kt` |
| P3-6 | Surface `addByCode`/`joinCircle` result to status | med | S | `GroupTrackingScreen.kt`, `TrackingViewModel.kt` |
| P3-7 | Rover-ID share sheet + first-run onboarding card | med | M | `GroupTrackingScreen.kt` |
| P3-8 | Moshi explicit-null hardening (`inviteCode` etc.) | low | S | `TrackingModels.kt` |
| P3-9 | Battery-optimization exemption prompt (one-time) | low | S | `GroupTrackingScreen.kt`/`EarlyRoverShareService.kt` |
| P3-10 | Map: re-fit on peers, heading arrow, radius picker, circle members | med | L | `TrackingMapView.kt`, `GroupTrackingScreen.kt` |
| H-1 | `HistoryStore.kt` — on-device history (7-day + key points + cap, atomic file) | high | M | `tracking/HistoryStore.kt` (new) |
| H-2 | Record hook in FGS + clear-on-sign-out + never-uploaded guard | high | S | `EarlyRoverShareService.kt`, `TrackingPrefs.kt` |
| H-3 | History sheet UI: day groups, delete single/select/all, map jump | high | M | `GroupTrackingScreen.kt` |

**Suggested build order:** P1-1→P1-6 first (they close an active battery/moderation hole and are prerequisites the P0 dialog's error path already leans on), then P0 (`Link Web`, the flagged #1 gap), then H-1→H-3 (founder directive: on-device history + delete controls), then P2, then P3.

**Verification per change (Android — compile-review only, no CI):** confirm imports resolve (the file already wildcard-imports `material.icons.filled.*` and `runtime.*`), Moshi `@JsonClass` codegen fields have defaults, and no `retrofit2.HttpException` reference is added without the fully-qualified name used above. No Gradle build runs in this repo — the founder builds the APK.

Relevant files (all absolute):
- `F:\Project\OdioBook\Android-app\Early-Rover\app\src\main\java\com\example\alarm\tracking\TrackingApi.kt`
- `...\tracking\TrackingModels.kt`
- `...\tracking\TrackingRepository.kt`
- `...\tracking\TrackingSocket.kt`
- `...\tracking\TrackingBus.kt`
- `...\tracking\TrackingViewModel.kt`
- `...\tracking\EarlyRoverShareService.kt`
- `...\tracking\GroupTrackingScreen.kt`
- `...\tracking\TrackingMapView.kt`