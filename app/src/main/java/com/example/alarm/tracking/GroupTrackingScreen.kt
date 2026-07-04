package com.example.alarm.tracking

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.alarm.location.CityInfo
import com.example.alarm.maps.GeoAppConfigDto
import com.example.alarm.maps.GeoPoint
import com.example.alarm.maps.OlaMapsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

private val PEER_PALETTE = listOf(
    "#6366F1", "#EC4899", "#10B981", "#F59E0B", "#3B82F6", "#8B5CF6", "#EF4444", "#14B8A6"
)

private fun colorFor(id: Int, provided: String?): String =
    provided?.ifBlank { null } ?: PEER_PALETTE[if (id >= 0) id % PEER_PALETTE.size else 0]

private fun parseColor(hex: String): Color = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Color(0xFF6366F1) }

private val SOS_RED = Color(0xFFEF4444)
private val SHARE_TEAL = Color(0xFF009688)
private val WARN_AMBER = Color(0xFFF59E0B)
private val GHOST_VIOLET = Color(0xFF8B5CF6)
private val LIVE_GREEN = Color(0xFF10B981)

/** Pending destructive action → confirmation dialog (§817 — no more instant deletes). */
private data class ConfirmReq(val title: String, val message: String, val confirmLabel: String, val action: () -> Unit)

private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)))
}

private fun distanceText(m: Double): String =
    if (m < 1000) "${m.toInt()} m" else String.format("%.1f km", m / 1000.0)

/** "just now" / "5m ago" / "2h ago" — null when the timestamp is absent/bad. */
private fun agoText(iso: String?, nowMs: Long): String? {
    if (iso.isNullOrBlank()) return null
    val t = try { Instant.parse(iso).toEpochMilli() } catch (_: Exception) { return null }
    val s = ((nowMs - t) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

/**
 * §806 → §817 overhaul → §820 FULL-SCREEN redesign — the "Rover" tab.
 *
 * §820 (founder, verbatim asks): the map owns the WHOLE page ("full page
 * location"); every section — Your Rover ID / People / Circles / Places / My
 * Sharing / Policy — and every action button lives inside a modal opened from a
 * TOP-RIGHT button; pending requests are cancellable; feedback auto-hides like a
 * toast; consent-graph changes sync to the other side live (WS §820 events).
 *
 * Layout grammar (mirrors the founder-approved §818 web design):
 *  - full-bleed [TrackingMapView] under everything
 *  - top tier (flows, never overlaps): peer-SOS banner → retry banner →
 *    identity pill (left) + panel button w/ badge + pending dot (right) →
 *    share-state pill (center) → auto-hiding notice toast
 *  - bottom-left SOS FAB (morphs to "SOS active — Resolve"), bottom-right
 *    recenter FAB, bottom-center: focused-member card / avatar rail / empty card
 *  - top-right button opens a [ModalBottomSheet] with 4 tabs
 *    (People · Circles · Places · Sharing) + a pinned consent footer; ALL text
 *    input stays in AlertDialogs (own windows → no sheet+IME issues)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupTrackingScreen(
    geoConfig: GeoAppConfigDto?,
    deviceLat: Double?,
    deviceLon: Double?,
    translate: (String) -> String = { it },
    vm: TrackingViewModel = viewModel()
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val me by vm.me.collectAsStateWithLifecycle()
    val connections by vm.connections.collectAsStateWithLifecycle()
    val circles by vm.circles.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val livePeers by vm.livePeers.collectAsStateWithLifecycle()
    val meLocation by vm.meLocation.collectAsStateWithLifecycle()
    val sharing by vm.isSharing.collectAsStateWithLifecycle()
    val connected by vm.socketConnected.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val sosList by vm.sos.collectAsStateWithLifecycle()
    val mySos by vm.mySos.collectAsStateWithLifecycle()
    val onlineIds by vm.onlineIds.collectAsStateWithLifecycle()
    val loadedOnce by vm.loadedOnce.collectAsStateWithLifecycle()
    val loadFailed by vm.loadFailed.collectAsStateWithLifecycle()

    // Screen lifecycle → open/close the shared socket.
    DisposableEffect(Unit) {
        vm.onScreenStart()
        onDispose { vm.onScreenStop() }
    }
    // §817 — periodic REST resync (edge-triggered WS events can be missed; requests
    // and accepts land within 30s even if the socket is down).
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            vm.refreshAll()
        }
    }

    // Permissions for sharing: fine location (+ background on 10+) + notifications (13+).
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) vm.startSharing()
    }
    fun requestShare() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            perms += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    fun copyText(text: String) {
        clip.setText(AnnotatedString(text))
        vm.postStatus(translate("Copied") + ": $text")
    }
    fun shareText(text: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(send, translate("Share via")))
        } catch (_: Exception) { copyText(text) }
    }

    // Dialogs (each is its own window → always above the sheet; text input lives here).
    var showAdd by remember { mutableStateOf(false) }
    var showCreateCircle by remember { mutableStateOf(false) }
    var showJoinCircle by remember { mutableStateOf(false) }
    var showAddPlace by remember { mutableStateOf(false) }
    var showName by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmReq?>(null) }
    var createdCircle by remember { mutableStateOf<CircleDto?>(null) }
    var expandedCircle by remember { mutableStateOf<Int?>(null) }
    var sosConfirm by remember { mutableStateOf(false) }
    var sosFailed by remember { mutableStateOf(false) }
    var showDisclosure by remember { mutableStateOf(false) }

    // §820 — the top-right modal. rememberSaveable: rotation must NOT silently
    // close a sheet the user is working in (auto-open is once per VM session).
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var sheetTab by rememberSaveable { mutableStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    fun openSheet(tab: Int? = null) {
        if (tab != null) sheetTab = tab
        showSheet = true
    }
    /** Any panel-initiated map focus closes the sheet FIRST (else the target
     *  centers behind it), then acts. Focus itself is preserved across open/close. */
    fun closeSheetThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) showSheet = false
            action()
        }
    }

    // Map focus (tap a person → fly + highlight). seq bumps per tap.
    var focus by remember { mutableStateOf<MapFocus?>(null) }
    var focusSeq by remember { mutableStateOf(0L) }

    // Best "me" position: live share > server last-known > device (the dashboard's
    // lat/lng can be a manually-pinned CITY, so it's last).
    val mePoint = when {
        meLocation != null -> GeoPoint(meLocation!!.lat, meLocation!!.lon)
        deviceLat != null && deviceLon != null -> GeoPoint(deviceLat, deviceLon)
        else -> null
    }

    val accepted = connections.filter { it.status == "accepted" }
    val incoming = connections.filter { it.incoming }
    val outgoing = connections.filter { it.outgoing }

    // Badge/dot must not recompose per live-location tick — derive them.
    val badgeCount by remember { derivedStateOf { connections.count { it.status == "accepted" } } }
    val hasIncoming by remember { derivedStateOf { connections.any { it.incoming } } }

    // §817 — map roster = accepted connections ∪ every circle member (deduped, self
    // excluded): a family circle shows on everyone's map without pairwise adds.
    val peerPoints = remember(accepted, circles, livePeers, me) {
        val out = LinkedHashMap<Int, PeerPoint>()
        for (c in accepted) {
            val loc = livePeers[c.peerId] ?: c.location ?: continue
            out[c.peerId] = PeerPoint(c.peerId, GeoPoint(loc.lat, loc.lon),
                colorFor(c.peerId, c.peer?.color), ghost = c.location?.ghost == true)
        }
        for (circle in circles) {
            for (m in circle.members) {
                if (m.id == me?.id || out.containsKey(m.id)) continue
                val loc = livePeers[m.id] ?: m.location ?: continue
                out[m.id] = PeerPoint(m.id, GeoPoint(loc.lat, loc.lon),
                    colorFor(m.id, m.color), ghost = m.location?.ghost == true)
            }
        }
        out.values.toList()
    }

    fun nameFor(userId: Int): String {
        connections.firstOrNull { it.peerId == userId }?.peer?.let { return it.displayName ?: it.roverId ?: "Rover" }
        for (c in circles) c.members.firstOrNull { it.id == userId }?.let { return it.displayName ?: it.roverId ?: "Rover" }
        return "Rover"
    }
    fun positionFor(userId: Int): GeoPoint? {
        livePeers[userId]?.let { return GeoPoint(it.lat, it.lon) }
        connections.firstOrNull { it.peerId == userId }?.location?.let { return GeoPoint(it.lat, it.lon) }
        for (c in circles) c.members.firstOrNull { it.id == userId }?.location?.let { return GeoPoint(it.lat, it.lon) }
        return null
    }
    // Best-known location DTO for the member card — live frame, then connection
    // last-known, then circle-member last-known (a circle-only member has no conn).
    fun locFor(userId: Int): LocationDto? {
        livePeers[userId]?.let { return it }
        connections.firstOrNull { it.peerId == userId }?.location?.let { return it }
        for (c in circles) c.members.firstOrNull { it.id == userId }?.location?.let { return it }
        return null
    }
    fun doFocus(userId: Int) {
        val p = positionFor(userId)
        if (p == null) {
            vm.postStatus("${nameFor(userId)} — " + translate("no location yet. They need to start sharing."))
            return
        }
        focusSeq += 1
        focus = MapFocus(p, focusSeq, peerId = userId)
    }
    fun focusPoint(p: GeoPoint) {
        focusSeq += 1
        focus = MapFocus(p, focusSeq)
    }
    fun locateSos(s: SosDto) {
        val uid = s.user?.id
        if (uid != null && positionFor(uid) != null) doFocus(uid)
        else if (s.lat != null && s.lon != null) focusPoint(GeoPoint(s.lat, s.lon))
    }

    // Peer SOS events (server already excludes self; filter defensively).
    val peerSos = sosList.filter { it.active && it.user?.id != me?.id }

    // §820 — one-shot camera fallback so first run never shows the open Atlantic:
    // no me, no peers → device point (city zoom) or India centroid (country zoom).
    LaunchedEffect(loadedOnce) {
        if (loadedOnce && mePoint == null && peerPoints.isEmpty() && focus == null) {
            focusSeq += 1
            focus = MapFocus(
                GeoPoint(deviceLat ?: 20.5937, deviceLon ?: 78.9629), focusSeq,
                zoom = if (deviceLat != null) 11.0 else 4.2, ring = false)
        }
    }

    // §820 — if the focused person leaves my consent graph (they removed me, a
    // circle dissolved — pushed live via the §820 WS events), the card, ring and
    // dashed line must not linger. Works via WS or the 30s poll (keyed on state).
    LaunchedEffect(connections, circles) {
        val pid = focus?.peerId ?: return@LaunchedEffect
        val visible = connections.any { it.status == "accepted" && it.peerId == pid } ||
            circles.any { c -> c.members.any { it.id == pid } }
        if (!visible) focus = null
    }

    // §820 — auto-open the People sheet once per VM session: empty roster
    // (onboarding) or a pending request must never hide behind a 10dp dot.
    // Gated on loadedOnce (never fire on the pre-fetch empty list) and on no
    // dialog being up (an onboarding sheet must not bury an SOS confirm).
    LaunchedEffect(loadedOnce, connections) {
        if (!loadedOnce || vm.autoOpenedOnce) return@LaunchedEffect
        val dialogUp = confirm != null || sosConfirm || sosFailed || showAdd ||
            showCreateCircle || showJoinCircle || showAddPlace || showName || showDisclosure
        if (dialogUp) return@LaunchedEffect
        vm.autoOpenedOnce = true
        val acc = connections.count { it.status == "accepted" }
        val inc = connections.count { it.incoming }
        if (acc == 0 || inc > 0) openSheet(0)
    }

    // Re-enable request-row buttons once a respond round-trip reflects in state.
    var respondingId by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(connections) { respondingId = null }

    Box(Modifier.fillMaxSize()) {
        // ── Tier 0: the map owns the page (R1) ──────────────────────────────
        TrackingMapView(
            styleUrl = OlaMapsRepository.resolveStyleUrl(geoConfig, isSystemInDarkTheme()),
            peers = peerPoints,
            me = mePoint,
            places = places.map { GeoPoint(it.lat, it.lon) },
            focus = focus,
            modifier = Modifier.fillMaxSize()
        )

        // ── Top tier (flows as a column — banners, pills, toast never overlap) ──
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
            // Peer SOS — an emergency NEVER auto-hides.
            if (peerSos.isNotEmpty()) {
                val s = peerSos.first()
                Surface(color = SOS_RED) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, Modifier.size(18.dp), tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text((s.user?.displayName ?: s.user?.roverId ?: "Someone") + " " +
                            translate("raised an SOS"),
                            Modifier.weight(1f), color = Color.White,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { locateSos(s) }) {
                            Text(translate("Locate"), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            // Bootstrap failure — sticky until a refresh succeeds.
            if (loadFailed) {
                Surface(color = WARN_AMBER.copy(alpha = 0.95f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(translate("Couldn't connect. Check your internet."),
                            Modifier.weight(1f), color = Color.Black.copy(alpha = 0.85f), fontSize = 12.sp)
                        TextButton(onClick = { vm.refreshAll(showSpinner = true) }) {
                            Text(translate("Retry"), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            // Row 1 — identity pill (left) + panel trigger (top-RIGHT, R2).
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(20.dp), color = cs.surface, shadowElevation = 3.dp) {
                    Row(Modifier
                        .clickable {
                            val u = me
                            if (u != null) copyText(u.roverId) else vm.refreshAll(showSpinner = true)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ContentCopy, translate("Copy"), Modifier.size(14.dp), tint = cs.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(me?.roverId ?: translate("Connecting…"),
                            fontWeight = FontWeight.Bold, fontSize = 13.sp, color = cs.onSurface)
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(8.dp).clip(CircleShape)
                            .background(if (connected) LIVE_GREEN else WARN_AMBER))
                    }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    FilledTonalIconButton(onClick = { openSheet() }, modifier = Modifier.size(48.dp)) {
                        BadgedBox(badge = {
                            if (badgeCount > 0) Badge { Text("$badgeCount") }
                        }) {
                            Icon(Icons.Default.Groups, translate("People, circles, places & sharing"))
                        }
                    }
                    if (hasIncoming) {
                        Box(Modifier.align(Alignment.TopStart).padding(2.dp)
                            .size(10.dp).clip(CircleShape).background(cs.error))
                    }
                }
            }
            // Row 2 — share/state pill (center). One state at a time (truth ladder).
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SharePill(
                    me = me, sharing = sharing, connected = connected, translate = translate,
                    onStart = {
                        if (!TrackingPrefs.sharingDisclosureShown()) showDisclosure = true
                        else requestShare()
                    },
                    onStop = { vm.stopSharing(); vm.postStatus(translate("Location sharing stopped.")) },
                    onOpenSharing = { openSheet(3) }
                )
            }
            // Auto-hiding toast (R6) — same Notice renders inside the sheet too.
            NoticeToast(notice, vm, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
        }

        // ── Bottom-center slot (one occupant), lifted above the FAB row ────────
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp, start = 16.dp, end = 16.dp)) {
            val focusedId = focus?.peerId
            if (focusedId != null) {
                MemberCard(
                    name = nameFor(focusedId),
                    conn = accepted.firstOrNull { it.peerId == focusedId },
                    loc = locFor(focusedId),
                    online = connected && onlineIds.contains(focusedId),
                    mePoint = mePoint,
                    translate = translate,
                    onClose = { focus = null }
                )
            } else if (peerPoints.isNotEmpty()) {
                // Avatar rail — the zero-modal way to focus a person.
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(peerPoints, key = { it.id }) { p ->
                        val name = nameFor(p.id).trim()
                        Surface(shape = RoundedCornerShape(22.dp), color = cs.surface,
                            shadowElevation = 3.dp,
                            modifier = Modifier.clickable { doFocus(p.id) }) {
                            Row(Modifier.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(28.dp).clip(CircleShape).background(parseColor(p.color)),
                                    contentAlignment = Alignment.Center) {
                                    Text(name.firstOrNull()?.uppercase() ?: "?",
                                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(name.split(Regex("\\s+")).first(), fontSize = 12.sp,
                                    color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            } else if (loadedOnce && accepted.isEmpty() && incoming.isEmpty() && circles.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = cs.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)) {
                    Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(translate("No one on your map yet"), fontWeight = FontWeight.Bold,
                            fontSize = 14.sp, color = cs.onSurface)
                        Text(translate("Invite family with your Rover ID — you approve who sees you."),
                            fontSize = 12.sp, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { openSheet(0) }) {
                            Icon(Icons.Default.PersonAdd, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text(translate("Invite"))
                        }
                    }
                }
            }
        }

        // ── SOS FAB (bottom-left) ───────────────────────────────────────────
        val activeMySos = mySos
        if (activeMySos != null) {
            ExtendedFloatingActionButton(
                onClick = { vm.resolveSos(activeMySos.id) },
                containerColor = SOS_RED, contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
            ) {
                Icon(Icons.Default.Warning, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(translate("SOS active — Resolve"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        } else {
            FloatingActionButton(
                onClick = { sosConfirm = true },
                containerColor = SOS_RED, contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).size(56.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Warning, null, Modifier.size(20.dp))
                    Text("SOS", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── Recenter (bottom-right). Never touches sharing state. ───────────
        SmallFloatingActionButton(
            onClick = {
                val p = mePoint
                if (p != null) {
                    focusSeq += 1
                    focus = MapFocus(p, focusSeq, ring = false)
                } else vm.postStatus(translate("Your location isn't available yet."))
            },
            containerColor = cs.surface, contentColor = cs.onSurface,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Default.MyLocation, translate("Center on my location")) }

        // ── The top-right modal (R2/R3): ALL sections + action buttons ──────
        if (showSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState = sheetState
            ) {
                Box(Modifier.fillMaxHeight()) {
                    Column(Modifier.fillMaxSize()) {
                        // Header: title · refresh · SOS (never more than 1 tap away) · close
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(translate("Early Rover"), fontWeight = FontWeight.Bold,
                                fontSize = 16.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.refreshAll(showSpinner = true) }) {
                                if (refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Refresh, translate("Refresh"), tint = cs.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                val mine = mySos
                                if (mine != null) vm.resolveSos(mine.id) else sosConfirm = true
                            }) {
                                Icon(Icons.Default.Warning, "SOS",
                                    tint = if (mySos != null) SOS_RED else SOS_RED.copy(alpha = 0.7f))
                            }
                            IconButton(onClick = { showSheet = false }) {
                                Icon(Icons.Default.Close, translate("Close"), tint = cs.onSurfaceVariant)
                            }
                        }
                        // Emergency banner must be visible INSIDE the sheet too.
                        if (peerSos.isNotEmpty()) {
                            val s = peerSos.first()
                            Surface(color = SOS_RED) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = Color.White)
                                    Spacer(Modifier.width(8.dp))
                                    Text((s.user?.displayName ?: s.user?.roverId ?: "Someone") + " " +
                                        translate("raised an SOS"),
                                        Modifier.weight(1f), color = Color.White, fontSize = 12.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    TextButton(onClick = { closeSheetThen { locateSos(s) } }) {
                                        Text(translate("Locate"), color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        TabRow(selectedTabIndex = sheetTab) {
                            listOf(
                                Triple(0, translate("People"), Icons.Default.Person),
                                Triple(1, translate("Circles"), Icons.Default.Group),
                                Triple(2, translate("Places"), Icons.Default.Place),
                                Triple(3, translate("Sharing"), Icons.Default.Settings),
                            ).forEach { (i, label, icon) ->
                                Tab(selected = sheetTab == i, onClick = { sheetTab = i },
                                    text = { Text(label, fontSize = 11.sp, maxLines = 1) },
                                    icon = { Icon(icon, null, Modifier.size(16.dp)) })
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            when (sheetTab) {
                                0 -> PeopleTab(
                                    me = me, incoming = incoming, accepted = accepted,
                                    outgoing = outgoing, livePeers = livePeers, mePoint = mePoint,
                                    respondingId = respondingId, translate = translate,
                                    onCopyId = { copyText(it) },
                                    onInvite = {
                                        shareText(translate("Add me on Early Rover!") + " " +
                                            translate("My Rover ID:") + " $it")
                                    },
                                    onAdd = { showAdd = true },
                                    onAccept = { c -> respondingId = c.id; vm.respond(c.id, true) },
                                    onDecline = { c -> respondingId = c.id; vm.respond(c.id, false) },
                                    onLocate = { pid -> closeSheetThen { doFocus(pid) } },
                                    onPause = { c -> vm.pauseConnection(c.id, !c.pausedByMe) },
                                    onRemove = { c ->
                                        val who = c.peer?.displayName ?: c.peer?.roverId ?: translate("them")
                                        confirm = ConfirmReq(
                                            translate("Remove") + " $who?",
                                            translate("You'll both stop seeing each other on the map.") + " " +
                                                who + " " + translate("won't get an alert, but your marker will disappear from their app. Adding back needs a new request."),
                                            translate("Remove")
                                        ) { vm.removeConnection(c.id) }
                                    },
                                    onCancelRequest = { c ->
                                        vm.cancelRequest(c.id, c.peer?.displayName ?: c.peer?.roverId ?: translate("They"))
                                    }
                                )
                                1 -> CirclesTab(
                                    circles = circles, meId = me?.id, mePoint = mePoint,
                                    livePeers = livePeers, expandedCircle = expandedCircle,
                                    translate = translate,
                                    onToggle = { id -> expandedCircle = if (expandedCircle == id) null else id },
                                    onJoin = { showJoinCircle = true },
                                    onCreate = { showCreateCircle = true },
                                    onCopyCode = { copyText(it) },
                                    onShareCode = { c ->
                                        shareText(translate("Join my Early Rover circle") + " \"${c.name}\" — " +
                                            translate("invite code:") + " ${c.inviteCode}")
                                    },
                                    onLocateMember = { pid -> closeSheetThen { doFocus(pid) } },
                                    onRemoveMember = { c, m ->
                                        confirm = ConfirmReq(
                                            translate("Remove member?"),
                                            (m.displayName ?: m.roverId ?: translate("This member")) + " " +
                                                translate("will be removed from") + " \"${c.name}\".",
                                            translate("Remove")
                                        ) { vm.removeMember(c.id, m.id) }
                                    },
                                    onLeave = { c ->
                                        confirm = if (c.isOwner) ConfirmReq(
                                            translate("Disband circle?"),
                                            "\"${c.name}\" " + translate("will be deleted for all") + " ${c.memberCount} " + translate("members."),
                                            translate("Disband")
                                        ) { vm.leaveCircle(c.id) }
                                        else ConfirmReq(
                                            translate("Leave circle?"),
                                            translate("You'll stop seeing members of") + " \"${c.name}\" " + translate("and they'll stop seeing you."),
                                            translate("Leave")
                                        ) { vm.leaveCircle(c.id) }
                                    }
                                )
                                2 -> PlacesTab(
                                    places = places, translate = translate,
                                    onAdd = { showAddPlace = true },
                                    onFocus = { p -> closeSheetThen { focusPoint(GeoPoint(p.lat, p.lon)) } },
                                    onDelete = { p ->
                                        confirm = ConfirmReq(
                                            translate("Delete place?"),
                                            "\"${p.name}\" " + translate("will be deleted — arrive/leave alerts for it stop."),
                                            translate("Delete")
                                        ) { vm.deletePlace(p.id) }
                                    }
                                )
                                3 -> SharingTab(
                                    me = me, translate = translate,
                                    onEditName = { showName = true },
                                    onPaused = { vm.setPaused(it) },
                                    onGhost = { vm.setGhost(it) },
                                    onPrecise = { vm.setSharePrecise(it) }
                                )
                            }
                        }
                        // Policy — pinned, non-dismissible, on every tab (R3 "Policy").
                        Surface(color = cs.surfaceVariant.copy(alpha = 0.6f)) {
                            Row(Modifier
                                .fillMaxWidth()
                                .clickable { sheetTab = 3 }
                                .navigationBarsPadding()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.VerifiedUser, null, Modifier.size(14.dp), tint = cs.primary)
                                Spacer(Modifier.width(6.dp))
                                Text(translate("Location is visible only to people you approve."),
                                    fontSize = 11.sp, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                    // The same auto-hiding toast, visible in THIS window too.
                    NoticeToast(notice, vm, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
                }
            }
        }
    }

    // ── dialogs (own windows — always above the sheet) ───────────────────────
    if (showAdd) InputDialog(
        title = translate("Add by Rover ID"),
        label = translate("ROVER-XXXXXXX"),
        confirm = translate("Send request"),
        capitalize = true,
        onDismiss = { showAdd = false },
        onConfirm = { code ->
            showAdd = false
            vm.addByCode(code.trim().uppercase()) { _, msg -> vm.postStatus(msg) }
        })
    if (showCreateCircle) InputDialog(
        title = translate("New circle"), label = translate("Name (e.g. Family)"),
        confirm = translate("Create"), onDismiss = { showCreateCircle = false },
        onConfirm = { name ->
            showCreateCircle = false
            vm.createCircle(name) { circle, err ->
                if (circle != null) { createdCircle = circle; expandedCircle = circle.id }
                else vm.postStatus(err ?: "Couldn't create the circle.")
            }
        })
    if (showJoinCircle) InputDialog(
        title = translate("Join circle"), label = translate("Invite code"),
        confirm = translate("Join"), capitalize = true,
        onDismiss = { showJoinCircle = false },
        onConfirm = { code ->
            showJoinCircle = false
            vm.joinCircle(code.trim().uppercase()) { ok, msg ->
                vm.postStatus(if (ok) translate("Joined! Circle members now appear on your map.") else msg)
            }
        })
    if (showName) InputDialog(
        title = translate("Edit my name"), label = translate("Display name"),
        initial = me?.displayName ?: "", confirm = translate("Save"),
        onDismiss = { showName = false }, onConfirm = { showName = false; vm.setDisplayName(it) })

    // §820 — success closes the dialog; failure keeps it open (typed name/search
    // survive an offline blip) and shows the real error.
    if (showAddPlace) AddPlaceDialog(
        mePoint = mePoint,
        translate = translate,
        onDismiss = { showAddPlace = false },
        onSave = { name, lat, lon, radius ->
            vm.createPlace(name, lat, lon, radius) { ok, msg ->
                if (ok) showAddPlace = false
                vm.postStatus(if (ok) translate("Place saved") + ": $name" else msg)
            }
        })

    // Created-circle celebration → invite code big + copy/share right away
    // (create-and-invite is ONE flow, like Life360).
    createdCircle?.let { circle ->
        AlertDialog(
            onDismissRequest = { createdCircle = null },
            title = { Text(translate("Circle created!")) },
            text = {
                Column {
                    Text(translate("Share this invite code so family can join:"),
                        fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(circle.inviteCode, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 2.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    shareText(translate("Join my Early Rover circle") + " \"${circle.name}\" — " +
                        translate("invite code:") + " ${circle.inviteCode}")
                    createdCircle = null
                }) { Text(translate("Share")) }
            },
            dismissButton = {
                TextButton(onClick = { copyText(circle.inviteCode); createdCircle = null }) {
                    Text(translate("Copy code"))
                }
            }
        )
    }

    // SOS confirm — success is CONFIRMED (§820); failure opens the retry dialog.
    if (sosConfirm) {
        AlertDialog(
            onDismissRequest = { sosConfirm = false },
            icon = { Icon(Icons.Default.Warning, null, tint = SOS_RED) },
            title = { Text(translate("Send an SOS?")) },
            text = { Text(translate("Everyone in your circle will be alerted with your current location.")) },
            confirmButton = {
                TextButton(onClick = {
                    sosConfirm = false
                    vm.raiseSos(mePoint?.latitude ?: deviceLat, mePoint?.longitude ?: deviceLon) { ok ->
                        if (!ok) sosFailed = true
                    }
                }) { Text(translate("Send SOS"), color = SOS_RED, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { sosConfirm = false }) { Text(translate("Cancel")) } }
        )
    }
    if (sosFailed) {
        AlertDialog(
            onDismissRequest = { sosFailed = false },
            icon = { Icon(Icons.Default.Warning, null, tint = SOS_RED) },
            title = { Text(translate("SOS didn't send")) },
            text = { Text(translate("Check your connection and try again.")) },
            confirmButton = {
                TextButton(onClick = {
                    sosFailed = false
                    vm.raiseSos(mePoint?.latitude ?: deviceLat, mePoint?.longitude ?: deviceLon) { ok ->
                        if (!ok) sosFailed = true
                    }
                }) { Text(translate("Retry"), color = SOS_RED, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { sosFailed = false }) { Text(translate("Close")) } }
        )
    }

    // §820 — one-time pre-permission disclosure (Play policy: prominent, before
    // the runtime prompt, states background use).
    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            icon = { Icon(Icons.Default.LocationOn, null, tint = cs.primary) },
            title = { Text(translate("Location sharing")) },
            text = {
                Text(translate("Early Rover shares your live location with people you approve — even while the app is closed or not in use. You can stop, pause, or go ghost anytime."))
            },
            confirmButton = {
                TextButton(onClick = {
                    TrackingPrefs.setSharingDisclosureShown()
                    showDisclosure = false
                    requestShare()
                }) { Text(translate("Continue"), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showDisclosure = false }) { Text(translate("Not now")) } }
        )
    }

    // Generic destructive-action confirmation.
    confirm?.let { req ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(req.title) },
            text = { Text(req.message) },
            confirmButton = {
                TextButton(onClick = { req.action(); confirm = null }) {
                    Text(req.confirmLabel, color = SOS_RED, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(translate("Cancel")) } }
        )
    }
}

// ── floating pieces ───────────────────────────────────────────────────────────

/** §820 — the auto-hiding feedback toast (R6). Rendered on the map layer AND
 *  inside the sheet (separate windows). The clear is id-guarded in the VM, so the
 *  duplicate timers can't kill a newer notice. Tap dismisses early. */
@Composable
private fun NoticeToast(notice: TrackingViewModel.Notice?, vm: TrackingViewModel, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier
    ) {
        val n = notice ?: return@AnimatedVisibility
        LaunchedEffect(n.id) {
            delay(if (n.error) 8000 else 6000)
            vm.clearNotice(n.id)
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp,
            border = if (n.error) androidx.compose.foundation.BorderStroke(1.dp, SOS_RED.copy(alpha = 0.5f)) else null,
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 360.dp)
                .clickable { vm.clearNotice(n.id) }
        ) {
            Text(n.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                fontSize = 13.sp,
                color = if (n.error) SOS_RED else MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** §820 — the share/state pill (top-center). Exactly ONE state shows, by the
 *  truth ladder: reconnecting > paused > ghost > sharing > idle. "Share" is
 *  reserved for the location stream ("Invite" = Rover-ID actions). */
@Composable
private fun SharePill(
    me: UserDto?, sharing: Boolean, connected: Boolean,
    translate: (String) -> String,
    onStart: () -> Unit, onStop: () -> Unit, onOpenSharing: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val paused = me?.paused == true
    val ghost = me?.ghost == true
    Surface(shape = RoundedCornerShape(22.dp), shadowElevation = 3.dp,
        color = when {
            paused -> WARN_AMBER
            ghost -> GHOST_VIOLET
            sharing && !connected -> WARN_AMBER
            sharing -> SHARE_TEAL
            else -> cs.surface
        }) {
        when {
            paused || ghost -> Row(
                Modifier.clickable { onOpenSharing() }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(if (paused) Icons.Default.PauseCircle else Icons.Default.VisibilityOff,
                    null, Modifier.size(16.dp), tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(translate(if (paused) "Paused — others can't see you" else "Ghost — frozen at your last point"),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            sharing -> Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape)
                        .background(if (connected) Color.White else Color.White.copy(alpha = 0.6f)))
                    Spacer(Modifier.width(6.dp))
                    Text(translate(if (connected) "Sharing live" else "Reconnecting…"),
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.width(1.dp).height(20.dp).background(Color.White.copy(alpha = 0.35f)))
                Text(translate("Stop"),
                    Modifier.clickable { onStop() }.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            else -> Row(
                Modifier.clickable { onStart() }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOff, null, Modifier.size(16.dp), tint = cs.primary)
                Spacer(Modifier.width(6.dp))
                Text(translate("Start sharing my location"), color = cs.onSurface,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** §820 — floating card for the focused person (bottom-center, above the FABs).
 *  Honest status ladder: paused-toward-me > Live now (presence + socket) >
 *  "Updated Xm ago" > no location. The 30s clock lives INSIDE this composable —
 *  no screen-scope tickers over a GL surface. */
@Composable
private fun MemberCard(
    name: String,
    conn: ConnectionDto?,
    loc: LocationDto?,
    online: Boolean,
    mePoint: GeoPoint?,
    translate: (String) -> String,
    onClose: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(30_000); nowMs = System.currentTimeMillis() }
    }
    val dist = if (loc != null && mePoint != null)
        haversineM(mePoint.latitude, mePoint.longitude, loc.lat, loc.lon)
    else conn?.distanceM
    val battery = loc?.battery
    val status = when {
        conn?.pausedByPeer == true -> translate("Paused sharing with you")
        loc == null -> translate("No location yet — they need to start sharing")
        online -> translate("Live now")
        else -> agoText(loc.updatedAt, nowMs)?.let { translate("Updated") + " $it" }
            ?: translate("Last known position")
    }
    Card(colors = CardDefaults.cardColors(containerColor = cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape)
                .background(if (online) LIVE_GREEN else Color(0xFF9CA3AF)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val parts = buildList {
                    if (dist != null) add(distanceText(dist) + " " + translate("from you"))
                    add(status)
                    if (battery != null) add("🔋$battery%")
                    when (conn?.addedByMe) {
                        true -> add(translate("You added them"))
                        false -> add(translate("They added you"))
                        null -> {}
                    }
                }
                Text(parts.joinToString(" · "), fontSize = 11.sp, color = cs.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, translate("Close"), Modifier.size(16.dp), tint = cs.onSurfaceVariant)
            }
        }
    }
}

// ── sheet tabs (all founder sections live here — R3) ─────────────────────────

@Composable
private fun PeopleTab(
    me: UserDto?,
    incoming: List<ConnectionDto>,
    accepted: List<ConnectionDto>,
    outgoing: List<ConnectionDto>,
    livePeers: Map<Int, LocationDto>,
    mePoint: GeoPoint?,
    respondingId: Int?,
    translate: (String) -> String,
    onCopyId: (String) -> Unit,
    onInvite: (String) -> Unit,
    onAdd: () -> Unit,
    onAccept: (ConnectionDto) -> Unit,
    onDecline: (ConnectionDto) -> Unit,
    onLocate: (Int) -> Unit,
    onPause: (ConnectionDto) -> Unit,
    onRemove: (ConnectionDto) -> Unit,
    onCancelRequest: (ConnectionDto) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 16.dp)) {
        // "Your Rover ID" — the #1 onboarding action, pinned on top.
        item {
            Card(Modifier.fillMaxWidth().padding(top = 10.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant.copy(alpha = 0.45f))) {
                Column(Modifier.padding(14.dp)) {
                    Text(translate("Your Rover ID"), fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            me?.roverId ?: "…",
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            fontSize = 20.sp, letterSpacing = 1.sp, color = cs.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = me != null) { me?.let { onCopyId(it.roverId) } }
                        )
                        me?.let { u ->
                            IconButton(onClick = { onCopyId(u.roverId) }) {
                                Icon(Icons.Default.ContentCopy, translate("Copy"), tint = cs.primary)
                            }
                            IconButton(onClick = { onInvite(u.roverId) }) {
                                Icon(Icons.Default.Share, translate("Invite"), tint = cs.primary)
                            }
                        }
                    }
                    Text(translate("Share this ID — you approve every request."),
                        fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(translate("People") + if (accepted.isNotEmpty()) " (${accepted.size})" else "",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = cs.onSurface, modifier = Modifier.weight(1f))
                Button(onClick = onAdd, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text(translate("Add"))
                }
            }
        }

        if (incoming.isNotEmpty()) {
            item { SectionLabel(translate("Requests")) }
            items(incoming, key = { "req_${it.id}" }) { c ->
                RequestRow(c, busy = respondingId == c.id,
                    onAccept = { onAccept(c) }, onDecline = { onDecline(c) })
            }
        }

        if (accepted.isEmpty() && outgoing.isEmpty()) {
            item { EmptyHint(translate("Share your Rover ID and add people to see them here.")) }
        }
        items(accepted, key = { "conn_${it.id}" }) { c ->
            PersonRow(
                c, live = livePeers[c.peerId],
                meP = mePoint,
                onLocate = { onLocate(c.peerId) },
                onPause = { onPause(c) },
                onRemove = { onRemove(c) })
        }
        // Outgoing pending — §820: cancellable while it waits (R5).
        items(outgoing, key = { "out_${it.id}" }) { c ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.HourglassEmpty, null, tint = cs.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(translate("Waiting for") + " " +
                        (c.peer?.displayName ?: c.peer?.roverId ?: "…") + " " +
                        translate("to approve…"),
                        Modifier.weight(1f), fontSize = 13.sp, color = cs.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { onCancelRequest(c) }) {
                        Text(translate("Cancel"), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CirclesTab(
    circles: List<CircleDto>,
    meId: Int?,
    mePoint: GeoPoint?,
    livePeers: Map<Int, LocationDto>,
    expandedCircle: Int?,
    translate: (String) -> String,
    onToggle: (Int) -> Unit,
    onJoin: () -> Unit,
    onCreate: () -> Unit,
    onCopyCode: (String) -> Unit,
    onShareCode: (CircleDto) -> Unit,
    onLocateMember: (Int) -> Unit,
    onRemoveMember: (CircleDto, CircleMember) -> Unit,
    onLeave: (CircleDto) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(translate("Circles"), fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = cs.onSurface, modifier = Modifier.weight(1f))
                TextButton(onClick = onJoin) { Text(translate("Join")) }
                Button(onClick = onCreate, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text(translate("New"))
                }
            }
        }
        if (circles.isEmpty()) {
            item { EmptyHint(translate("Create a circle for your family — everyone in it sees everyone on the map.")) }
        }
        items(circles, key = { "circle_${it.id}" }) { circle ->
            CircleCard(
                circle = circle,
                expanded = expandedCircle == circle.id,
                meId = meId,
                meP = mePoint,
                livePeers = livePeers,
                onToggle = { onToggle(circle.id) },
                onCopyCode = { onCopyCode(circle.inviteCode) },
                onShareCode = { onShareCode(circle) },
                onLocateMember = onLocateMember,
                onRemoveMember = { m -> onRemoveMember(circle, m) },
                onLeave = { onLeave(circle) },
                translate = translate
            )
        }
    }
}

@Composable
private fun PlacesTab(
    places: List<PlaceDto>,
    translate: (String) -> String,
    onAdd: () -> Unit,
    onFocus: (PlaceDto) -> Unit,
    onDelete: (PlaceDto) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(translate("Places"), fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = cs.onSurface, modifier = Modifier.weight(1f))
                Button(onClick = onAdd, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Default.AddLocationAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text(translate("Add place"))
                }
            }
        }
        if (places.isEmpty()) {
            item { EmptyHint(translate("Save Home / School / Work — your circle gets arrive & leave alerts.")) }
        }
        items(places, key = { "place_${it.id}" }) { p ->
            PlaceRow(p, onFocus = { onFocus(p) }, onDelete = { onDelete(p) })
        }
    }
}

@Composable
private fun SharingTab(
    me: UserDto?,
    translate: (String) -> String,
    onEditName: () -> Unit,
    onPaused: (Boolean) -> Unit,
    onGhost: (Boolean) -> Unit,
    onPrecise: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Text(translate("My sharing"), fontWeight = FontWeight.Bold, fontSize = 16.sp,
                color = cs.onSurface, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            me?.let { u ->
                Card(Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        // Name — shows the CURRENT name with an edit pencil.
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Avatar(u.id, u.color, u.displayName)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(u.displayName?.ifBlank { null } ?: translate("No name set"),
                                    fontWeight = FontWeight.Bold, color = cs.onSurface)
                                Text(translate("This is how others see you"),
                                    fontSize = 11.sp, color = cs.onSurfaceVariant)
                            }
                            IconButton(onClick = onEditName) {
                                Icon(Icons.Default.Edit, translate("Edit name"), tint = cs.primary)
                            }
                        }
                        HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
                        ToggleRow(translate("Pause sharing"),
                            translate("I still see others — they'll see you as paused"),
                            u.paused) { onPaused(it) }
                        ToggleRow(translate("Ghost mode"),
                            translate("Others see your last point, marked not live"),
                            u.ghost) { onGhost(it) }
                        ToggleRow(translate("Precise location"),
                            translate("off = approximate only"), u.sharePrecise) { onPrecise(it) }
                    }
                }
            } ?: EmptyHint(translate("Connecting…"))
        }
        // "Policy" — the full consent & legal notice (always here, never dismissible).
        item {
            Card(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VerifiedUser, null, Modifier.size(16.dp), tint = cs.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(translate("Consent & privacy"), fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        translate("By using Early Rover you confirm you installed this app yourself and share your location by your own consent. Location is visible ONLY to people YOU approve or circles YOU join — both sides must agree. You can pause, go ghost, or remove anyone anytime. Tracking anyone without their knowledge and consent is prohibited and may be illegal."),
                        fontSize = 11.sp, lineHeight = 15.sp, color = cs.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── components ────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp))
}

@Composable
private fun RequestRow(c: ConnectionDto, busy: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(c.peerId, c.peer?.color, c.peer?.displayName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.peer?.displayName ?: c.peer?.roverId ?: "Someone",
                    fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text("wants to connect", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onAccept) { Icon(Icons.Default.Check, "Accept", tint = LIVE_GREEN) }
                IconButton(onClick = onDecline) { Icon(Icons.Default.Close, "Decline", tint = SOS_RED) }
            }
        }
    }
}

@Composable
private fun PersonRow(
    c: ConnectionDto,
    live: LocationDto?,
    meP: GeoPoint?,
    onLocate: () -> Unit,
    onPause: () -> Unit,
    onRemove: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val loc = live ?: c.location
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onLocate() },
        colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(c.peerId, c.peer?.color, c.peer?.displayName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.peer?.displayName ?: c.peer?.roverId ?: "Rover",
                    fontWeight = FontWeight.Bold, color = cs.onSurface)
                val dist = c.distanceM ?: if (loc != null && meP != null)
                    haversineM(meP.latitude, meP.longitude, loc.lat, loc.lon) else null
                val sub = buildList {
                    when {
                        c.pausedByPeer -> add("Paused sharing with you")
                        loc == null -> add("No location yet")
                        dist != null -> add(distanceText(dist) + " away")
                        else -> add("Sharing location")
                    }
                    when (c.addedByMe) {
                        true -> add("You added")
                        false -> add("Added you")
                        null -> {}
                    }
                }
                Text(sub.joinToString(" · "), fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            val battery = loc?.battery
            if (battery != null) {
                Icon(Icons.Default.BatteryFull, null, Modifier.size(16.dp),
                    tint = if (battery <= 15) SOS_RED else cs.onSurfaceVariant)
                Text("$battery%", fontSize = 11.sp, color = cs.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
            }
            if (loc != null) {
                IconButton(onClick = onLocate) {
                    Icon(Icons.Default.MyLocation, "Locate", tint = cs.primary)
                }
            }
            IconButton(onClick = onPause) {
                Icon(if (c.pausedByMe) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    "Pause", tint = if (c.pausedByMe) WARN_AMBER else cs.onSurfaceVariant)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Default.PersonRemove, "Remove", tint = cs.onSurfaceVariant) }
        }
    }
}

@Composable
private fun CircleCard(
    circle: CircleDto,
    expanded: Boolean,
    meId: Int?,
    meP: GeoPoint?,
    livePeers: Map<Int, LocationDto>,
    onToggle: () -> Unit,
    onCopyCode: () -> Unit,
    onShareCode: () -> Unit,
    onLocateMember: (Int) -> Unit,
    onRemoveMember: (CircleMember) -> Unit,
    onLeave: () -> Unit,
    translate: (String) -> String
) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { onToggle() }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Group, null, tint = cs.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(circle.name, fontWeight = FontWeight.Bold, color = cs.onSurface)
                    Text("${circle.memberCount} " + translate("members") +
                        (if (circle.isOwner) " · " + translate("you own this") else ""),
                        fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, tint = cs.onSurfaceVariant)
            }
            if (expanded) {
                HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
                Column(Modifier.padding(12.dp)) {
                    // Invite code — copy + share.
                    Surface(shape = RoundedCornerShape(10.dp), color = cs.surfaceVariant.copy(alpha = 0.6f)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(translate("Invite code"), fontSize = 11.sp, color = cs.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(circle.inviteCode, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, fontSize = 15.sp, color = cs.onSurface,
                                modifier = Modifier.weight(1f).clickable { onCopyCode() })
                            IconButton(onClick = onCopyCode, Modifier.size(32.dp)) {
                                Icon(Icons.Default.ContentCopy, translate("Copy"), Modifier.size(16.dp), tint = cs.primary)
                            }
                            IconButton(onClick = onShareCode, Modifier.size(32.dp)) {
                                Icon(Icons.Default.Share, translate("Share"), Modifier.size(16.dp), tint = cs.primary)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    circle.members.forEach { m ->
                        val isMe = m.id == meId
                        val loc = livePeers[m.id] ?: m.location
                        Row(Modifier.fillMaxWidth()
                            .clickable(enabled = !isMe) { onLocateMember(m.id) }
                            .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).clip(CircleShape)
                                .background(parseColor(colorFor(m.id, m.color))),
                                contentAlignment = Alignment.Center) {
                                Text((m.displayName?.trim()?.firstOrNull()?.uppercase() ?: "?"),
                                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (isMe) translate("You") else (m.displayName ?: m.roverId ?: "Rover"),
                                    fontSize = 14.sp, color = cs.onSurface)
                                val dist = if (!isMe && loc != null && meP != null)
                                    haversineM(meP.latitude, meP.longitude, loc.lat, loc.lon) else null
                                Text(
                                    when {
                                        isMe -> ""
                                        dist != null -> distanceText(dist) + " away"
                                        loc == null -> translate("No location yet")
                                        else -> ""
                                    },
                                    fontSize = 11.sp, color = cs.onSurfaceVariant)
                            }
                            if (m.role == "owner") {
                                Text(translate("owner"), fontSize = 10.sp, color = cs.onSurfaceVariant,
                                    modifier = Modifier
                                        .background(cs.surfaceVariant, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                            if (!isMe && loc != null) {
                                IconButton(onClick = { onLocateMember(m.id) }, Modifier.size(32.dp)) {
                                    Icon(Icons.Default.MyLocation, translate("Locate"), Modifier.size(16.dp), tint = cs.primary)
                                }
                            }
                            if (circle.isOwner && m.role != "owner" && !isMe) {
                                IconButton(onClick = { onRemoveMember(m) }, Modifier.size(32.dp)) {
                                    Icon(Icons.Default.PersonRemove, translate("Remove"), Modifier.size(16.dp), tint = cs.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    TextButton(onClick = onLeave) {
                        Icon(Icons.Default.Logout, null, Modifier.size(16.dp), tint = SOS_RED)
                        Spacer(Modifier.width(6.dp))
                        Text(translate(if (circle.isOwner) "Disband circle" else "Leave circle"),
                            color = SOS_RED)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(p: PlaceDto, onFocus: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onFocus() },
        colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Place, null, tint = WARN_AMBER)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text("${p.radiusM}m radius" + if (p.inside) " · you're here" else "",
                    fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            IconButton(onClick = onFocus) { Icon(Icons.Default.MyLocation, "Show on map", tint = cs.primary) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = cs.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ToggleRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = cs.onSurface)
            Text(hint, fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = cs.primary,
                uncheckedThumbColor = cs.onSurfaceVariant,
                uncheckedTrackColor = cs.surfaceVariant,
                uncheckedBorderColor = cs.outline
            )
        )
    }
}

@Composable
private fun Avatar(id: Int, color: String?, name: String?) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(parseColor(colorFor(id, color))),
        contentAlignment = Alignment.Center) {
        Text((name?.trim()?.firstOrNull()?.uppercase() ?: "?"),
            color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InputDialog(
    title: String, label: String, confirm: String,
    initial: String = "",
    capitalize: Boolean = false,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it },
                label = { Text(label) }, singleLine = true,
                keyboardOptions = if (capitalize)
                    KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                else KeyboardOptions.Default)
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * §817 — Add-place dialog: name + LOCATION SEARCH (geo-gateway autocomplete, the
 * same India-aware search the Travel tab uses) or "use my location", plus a
 * radius slider. Different places, different searched locations — as it should be.
 */
@Composable
private fun AddPlaceDialog(
    mePoint: GeoPoint?,
    translate: (String) -> String,
    onDismiss: () -> Unit,
    onSave: (name: String, lat: Double, lon: Double, radiusM: Int) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(150f) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CityInfo>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Pair<GeoPoint, String>?>(null) }   // coord + label
    var error by remember { mutableStateOf<String?>(null) }

    // Debounced autocomplete (350ms, min 3 chars) — polite to the shared gateway.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 3) { results = emptyList(); searching = false; return@LaunchedEffect }
        delay(350)
        searching = true
        results = try { OlaMapsRepository.searchPlaces(q, mePoint) } catch (_: Exception) { emptyList() }
        searching = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(translate("Add a place")) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text(translate("Name (e.g. Home, School)")) }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = query,
                    onValueChange = { query = it; picked = null },
                    label = { Text(translate("Search address / area")) }, singleLine = true,
                    trailingIcon = {
                        if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Search, null)
                    })
                // Search results (top 5).
                if (picked == null && results.isNotEmpty()) {
                    Column(Modifier.padding(top = 4.dp)) {
                        results.take(5).forEach { r ->
                            Row(Modifier.fillMaxWidth()
                                .clickable {
                                    picked = GeoPoint(r.latitude, r.longitude) to r.name
                                    query = r.name
                                    if (name.isBlank()) name = r.name
                                    results = emptyList()
                                }
                                .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Place, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(r.name, fontSize = 13.sp, color = cs.onSurface)
                                    if (r.country.isNotBlank())
                                        Text(r.country, fontSize = 11.sp, color = cs.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                TextButton(
                    onClick = {
                        if (mePoint != null) {
                            picked = mePoint to translate("My current location")
                            query = translate("My current location")
                        } else error = translate("Your location isn't available yet.")
                    }
                ) {
                    Icon(Icons.Default.MyLocation, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text(translate("Use my current location"))
                }
                picked?.let {
                    Text("✓ " + it.second, fontSize = 12.sp, color = LIVE_GREEN)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(translate("Radius"), fontSize = 12.sp, color = cs.onSurfaceVariant)
                    Slider(value = radius, onValueChange = { radius = it },
                        valueRange = 50f..2000f, steps = 38, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Text("${radius.toInt()} m", fontSize = 12.sp, color = cs.onSurface)
                }
                error?.let { Text(it, fontSize = 12.sp, color = SOS_RED) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = picked
                when {
                    name.isBlank() -> error = translate("Give the place a name.")
                    p == null -> error = translate("Search a location or use your current one.")
                    else -> onSave(name.trim(), p.first.latitude, p.first.longitude, radius.toInt())
                }
            }) { Text(translate("Save place")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(translate("Cancel")) } }
    )
}
