package com.example.alarm.tracking

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.alarm.location.CityInfo
import com.example.alarm.maps.GeoAppConfigDto
import com.example.alarm.maps.GeoPoint
import com.example.alarm.maps.OlaMapsRepository
import kotlinx.coroutines.delay

private val PEER_PALETTE = listOf(
    "#6366F1", "#EC4899", "#10B981", "#F59E0B", "#3B82F6", "#8B5CF6", "#EF4444", "#14B8A6"
)

private fun colorFor(id: Int, provided: String?): String =
    provided?.ifBlank { null } ?: PEER_PALETTE[if (id >= 0) id % PEER_PALETTE.size else 0]

private fun parseColor(hex: String): Color = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Color(0xFF6366F1) }

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
    if (m < 1000) "${m.toInt()} m away" else String.format("%.1f km away", m / 1000.0)

/**
 * §806 → §817 overhaul — the "Rover" tab: live group/family location map
 * (Life360-style) with Rover-ID share, add-by-ID + approve, circles, geofence
 * places, SOS and sharing controls.
 *
 * §817 fixes (founder feedback):
 *  - map ALWAYS renders (key-less §692 style URL; the old tile_key gate showed
 *    "Map unavailable" forever on §692+ servers)
 *  - every text uses explicit theme colours → readable in dark AND light (the tab
 *    sits on the transparent weather-sky scaffold where default text fell back to
 *    black-on-black in dark mode)
 *  - prominent Rover-ID card with Copy + Share; circle invite codes copyable too
 *  - tap a person (roster or circle member) → camera flies to them, highlight ring
 *    + dashed me→them line, floating chip with live distance
 *  - circle MEMBERS are plotted on the map (before: only direct connections)
 *  - places can be created by ADDRESS SEARCH (geo autocomplete) or my location,
 *    with a radius slider
 *  - create-circle immediately surfaces the invite code to share (join flow gives
 *    explicit success/error feedback)
 *  - name shows inline with an Edit pencil (no more permanent "Set my name")
 *  - destructive actions (remove person/member, leave/disband, delete place, SOS)
 *    all confirm first
 *  - pull-to-refresh + a 30s background resync while the tab is open
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

    val me by vm.me.collectAsStateWithLifecycle()
    val connections by vm.connections.collectAsStateWithLifecycle()
    val circles by vm.circles.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val livePeers by vm.livePeers.collectAsStateWithLifecycle()
    val meLocation by vm.meLocation.collectAsStateWithLifecycle()
    val sharing by vm.isSharing.collectAsStateWithLifecycle()
    val connected by vm.socketConnected.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()

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

    fun copyText(text: String, what: String) {
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
        } catch (_: Exception) { copyText(text, "share") }
    }

    var showAdd by remember { mutableStateOf(false) }
    var showCreateCircle by remember { mutableStateOf(false) }
    var showJoinCircle by remember { mutableStateOf(false) }
    var showAddPlace by remember { mutableStateOf(false) }
    var showName by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmReq?>(null) }
    var createdCircle by remember { mutableStateOf<CircleDto?>(null) }
    var expandedCircle by remember { mutableStateOf<Int?>(null) }
    var sosConfirm by remember { mutableStateOf(false) }

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
    fun focusOn(userId: Int) {
        val p = positionFor(userId)
        if (p == null) {
            vm.postStatus("${nameFor(userId)} — " + translate("no location yet. They need to start sharing."))
            return
        }
        focusSeq += 1
        focus = MapFocus(p, focusSeq, peerId = userId)
    }

    // Live distance chip for the focused person.
    val focusedId = focus?.peerId
    val focusedDistance = if (focusedId != null && mePoint != null) {
        positionFor(focusedId)?.let { haversineM(mePoint.latitude, mePoint.longitude, it.latitude, it.longitude) }
    } else null

    Column(Modifier.fillMaxSize()) {
        // ── map ──────────────────────────────────────────────────────────────
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            TrackingMapView(
                styleUrl = OlaMapsRepository.resolveStyleUrl(geoConfig, isSystemInDarkTheme()),
                peers = peerPoints,
                me = mePoint,
                places = places.map { GeoPoint(it.lat, it.lon) },
                focus = focus,
                modifier = Modifier.fillMaxSize()
            )
            // Rover-ID chip (top-left) — tap to copy.
            me?.let { u ->
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = cs.surface, shadowElevation = 3.dp
                ) {
                    Row(Modifier.clickable { copyText(u.roverId, "id") }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ContentCopy, translate("Copy"), Modifier.size(14.dp), tint = cs.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(u.roverId, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = cs.onSurface)
                    }
                }
            }
            // Focused-person chip (top-center) — who + live distance + clear.
            if (focusedId != null) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = cs.surface, shadowElevation = 3.dp
                ) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            nameFor(focusedId) + (focusedDistance?.let { " · " + distanceText(it) } ?: ""),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface
                        )
                        IconButton(onClick = { focus = null }, Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, translate("Clear"), Modifier.size(16.dp), tint = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            // SOS (top-right) — confirms first (§817).
            FloatingActionButton(
                onClick = { sosConfirm = true },
                containerColor = Color(0xFFEF4444),
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(48.dp)
            ) { Icon(Icons.Default.Warning, "SOS", tint = Color.White) }
            // Share toggle (bottom-center).
            ExtendedFloatingActionButton(
                onClick = { if (sharing) vm.stopSharing() else requestShare() },
                containerColor = if (sharing) Color(0xFF009688) else cs.surfaceVariant,
                contentColor = if (sharing) Color.White else cs.onSurface,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
            ) {
                Icon(if (sharing) Icons.Default.LocationOn else Icons.Default.LocationOff, null)
                Spacer(Modifier.width(8.dp))
                Text(translate(if (sharing) "Sharing ON" else "Start sharing"))
            }
        }

        // Live/socket status strip.
        status?.let {
            Surface(color = cs.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), fontSize = 13.sp, color = cs.onSecondaryContainer)
                    TextButton(onClick = { vm.clearStatus() }) { Text(translate("Dismiss")) }
                }
            }
        }

        // ── list (pull-to-refresh) ───────────────────────────────────────────
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.refreshAll(showSpinner = true) },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                // Identity card — big copyable Rover ID + Share (the #1 onboarding action).
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = cs.surface)
                    ) {
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
                                        .clickable(enabled = me != null) { me?.let { copyText(it.roverId, "id") } }
                                )
                                me?.let { u ->
                                    IconButton(onClick = { copyText(u.roverId, "id") }) {
                                        Icon(Icons.Default.ContentCopy, translate("Copy"), tint = cs.primary)
                                    }
                                    IconButton(onClick = {
                                        shareText(translate("Add me on Early Rover!") + " " +
                                            translate("My Rover ID:") + " ${u.roverId}")
                                    }) {
                                        Icon(Icons.Default.Share, translate("Share"), tint = cs.primary)
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
                        Text(translate("People"), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            color = cs.onBackground, modifier = Modifier.weight(1f))
                        DotStatus(connected)
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { showAdd = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp)); Text(translate("Add"))
                        }
                    }
                }

                if (incoming.isNotEmpty()) {
                    item { SectionLabel(translate("Requests")) }
                    items(incoming, key = { "req_${it.id}" }) { c ->
                        RequestRow(c, onAccept = { vm.respond(c.id, true) },
                            onDecline = { vm.respond(c.id, false) })
                    }
                }

                if (accepted.isEmpty() && outgoing.isEmpty()) {
                    item { EmptyHint(translate("Share your Rover ID and add people to see them here.")) }
                }
                items(accepted, key = { "conn_${it.id}" }) { c ->
                    PersonRow(
                        c, live = livePeers[c.peerId],
                        meP = mePoint,
                        onLocate = { focusOn(c.peerId) },
                        onPause = { vm.pauseConnection(c.id, !c.pausedByMe) },
                        onRemove = {
                            val who = c.peer?.displayName ?: c.peer?.roverId ?: "them"
                            confirm = ConfirmReq(
                                translate("Remove person?"),
                                translate("You and") + " $who " + translate("will stop seeing each other's location."),
                                translate("Remove")
                            ) { vm.removeConnection(c.id) }
                        })
                }
                items(outgoing, key = { "out_${it.id}" }) { c ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.HourglassEmpty, null, tint = cs.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text(translate("Waiting for") + " " +
                                (c.peer?.displayName ?: c.peer?.roverId ?: "…") + " " +
                                translate("to approve…"),
                                fontSize = 13.sp, color = cs.onSurfaceVariant)
                        }
                    }
                }

                // Circles.
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(translate("Circles"), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            color = cs.onBackground, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showJoinCircle = true }) { Text(translate("Join")) }
                        TextButton(onClick = { showCreateCircle = true }) { Text(translate("New")) }
                    }
                }
                if (circles.isEmpty()) {
                    item { EmptyHint(translate("Create a circle for your family — everyone in it sees everyone on the map.")) }
                }
                items(circles, key = { "circle_${it.id}" }) { circle ->
                    CircleCard(
                        circle = circle,
                        expanded = expandedCircle == circle.id,
                        meId = me?.id,
                        meP = mePoint,
                        livePeers = livePeers,
                        onToggle = { expandedCircle = if (expandedCircle == circle.id) null else circle.id },
                        onCopyCode = { copyText(circle.inviteCode, "code") },
                        onShareCode = {
                            shareText(translate("Join my Early Rover circle") + " \"${circle.name}\" — " +
                                translate("invite code:") + " ${circle.inviteCode}")
                        },
                        onLocateMember = { focusOn(it) },
                        onRemoveMember = { m ->
                            confirm = ConfirmReq(
                                translate("Remove member?"),
                                (m.displayName ?: m.roverId ?: "This member") + " " +
                                    translate("will be removed from") + " \"${circle.name}\".",
                                translate("Remove")
                            ) { vm.removeMember(circle.id, m.id) }
                        },
                        onLeave = {
                            confirm = if (circle.isOwner) ConfirmReq(
                                translate("Disband circle?"),
                                "\"${circle.name}\" " + translate("will be deleted for all") + " ${circle.memberCount} " + translate("members."),
                                translate("Disband")
                            ) { vm.leaveCircle(circle.id) }
                            else ConfirmReq(
                                translate("Leave circle?"),
                                translate("You'll stop seeing members of") + " \"${circle.name}\" " + translate("and they'll stop seeing you."),
                                translate("Leave")
                            ) { vm.leaveCircle(circle.id) }
                        },
                        translate = translate
                    )
                }

                // Places.
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(translate("Places"), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            color = cs.onBackground, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showAddPlace = true }) {
                            Icon(Icons.Default.AddLocationAlt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp)); Text(translate("Add place"))
                        }
                    }
                }
                if (places.isEmpty()) {
                    item { EmptyHint(translate("Save Home / School / Work — your circle gets arrive & leave alerts.")) }
                }
                items(places, key = { "place_${it.id}" }) { p ->
                    PlaceRow(p,
                        onFocus = {
                            focusSeq += 1
                            focus = MapFocus(GeoPoint(p.lat, p.lon), focusSeq)
                        },
                        onDelete = {
                            confirm = ConfirmReq(
                                translate("Delete place?"),
                                "\"${p.name}\" " + translate("will be deleted — arrive/leave alerts for it stop."),
                                translate("Delete")
                            ) { vm.deletePlace(p.id) }
                        })
                }

                // My profile + sharing controls.
                item {
                    Text(translate("My sharing"), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        color = cs.onBackground,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
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
                                    IconButton(onClick = { showName = true }) {
                                        Icon(Icons.Default.Edit, translate("Edit name"), tint = cs.primary)
                                    }
                                }
                                HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
                                ToggleRow(translate("Pause sharing"), translate("I still see others"), u.paused) { vm.setPaused(it) }
                                ToggleRow(translate("Ghost mode"), translate("freeze me at my last point"), u.ghost) { vm.setGhost(it) }
                                ToggleRow(translate("Precise location"), translate("off = approximate only"), u.sharePrecise) { vm.setSharePrecise(it) }
                            }
                        }
                    }
                    // §817 — consent & legal notice (always visible, not dismissible).
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
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    // ── dialogs ────────────────────────────────────────────────────────────
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

    if (showAddPlace) AddPlaceDialog(
        mePoint = mePoint,
        translate = translate,
        onDismiss = { showAddPlace = false },
        onSave = { name, lat, lon, radius ->
            showAddPlace = false
            vm.createPlace(name, lat, lon, radius)
            vm.postStatus(translate("Place saved") + ": $name")
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
                TextButton(onClick = { copyText(circle.inviteCode, "code"); createdCircle = null }) {
                    Text(translate("Copy code"))
                }
            }
        )
    }

    // SOS confirm.
    if (sosConfirm) {
        AlertDialog(
            onDismissRequest = { sosConfirm = false },
            icon = { Icon(Icons.Default.Warning, null, tint = Color(0xFFEF4444)) },
            title = { Text(translate("Send an SOS?")) },
            text = { Text(translate("Everyone connected to you will be alerted with your current location.")) },
            confirmButton = {
                TextButton(onClick = {
                    sosConfirm = false
                    vm.raiseSos(mePoint?.latitude ?: deviceLat, mePoint?.longitude ?: deviceLon)
                }) { Text(translate("Send SOS"), color = Color(0xFFEF4444), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { sosConfirm = false }) { Text(translate("Cancel")) } }
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
                    Text(req.confirmLabel, color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(translate("Cancel")) } }
        )
    }
}

// ── components ────────────────────────────────────────────────────────────────

@Composable
private fun DotStatus(connected: Boolean) {
    Box(Modifier.size(10.dp).clip(CircleShape)
        .background(if (connected) Color(0xFF10B981) else Color(0xFF9CA3AF)))
}

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
private fun RequestRow(c: ConnectionDto, onAccept: () -> Unit, onDecline: () -> Unit) {
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
            IconButton(onClick = onAccept) { Icon(Icons.Default.Check, "Accept", tint = Color(0xFF10B981)) }
            IconButton(onClick = onDecline) { Icon(Icons.Default.Close, "Decline", tint = Color(0xFFEF4444)) }
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
                val sub = when {
                    c.pausedByPeer -> "Paused sharing with you"
                    loc == null -> "No location yet"
                    dist != null -> distanceText(dist)
                    else -> "Sharing location"
                }
                Text(sub, fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            val battery = loc?.battery
            if (battery != null) {
                Icon(Icons.Default.BatteryFull, null, Modifier.size(16.dp),
                    tint = if (battery <= 15) Color(0xFFEF4444) else cs.onSurfaceVariant)
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
                    "Pause", tint = if (c.pausedByMe) Color(0xFFF59E0B) else cs.onSurfaceVariant)
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
                                        dist != null -> distanceText(dist)
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
                        Icon(Icons.Default.Logout, null, Modifier.size(16.dp), tint = Color(0xFFEF4444))
                        Spacer(Modifier.width(6.dp))
                        Text(translate(if (circle.isOwner) "Disband circle" else "Leave circle"),
                            color = Color(0xFFEF4444))
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
            Icon(Icons.Default.Place, null, tint = Color(0xFFF59E0B))
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
                    Text("✓ " + it.second, fontSize = 12.sp, color = Color(0xFF10B981))
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(translate("Radius"), fontSize = 12.sp, color = cs.onSurfaceVariant)
                    Slider(value = radius, onValueChange = { radius = it },
                        valueRange = 50f..2000f, steps = 38, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Text("${radius.toInt()} m", fontSize = 12.sp, color = cs.onSurface)
                }
                error?.let { Text(it, fontSize = 12.sp, color = Color(0xFFEF4444)) }
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
