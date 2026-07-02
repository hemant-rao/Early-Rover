package com.example.alarm.tracking

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.alarm.maps.GeoAppConfigDto
import com.example.alarm.maps.GeoPoint

private val PEER_PALETTE = listOf(
    "#6366F1", "#EC4899", "#10B981", "#F59E0B", "#3B82F6", "#8B5CF6", "#EF4444", "#14B8A6"
)

private fun colorFor(id: Int, provided: String?): String =
    provided?.ifBlank { null } ?: PEER_PALETTE[if (id >= 0) id % PEER_PALETTE.size else 0]

private fun parseColor(hex: String): Color = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Color(0xFF6366F1) }

/**
 * §806 — the "Rover" tab: a live group/family location map (Life360/Zenly-style)
 * with the Rover-ID share flow, add-by-ID + approve, circles, saved-place geofence
 * alerts, SOS, and per-person / global sharing controls. Pure Compose over
 * [TrackingViewModel]; reuses the geo gateway's tile key for the map.
 *
 * @param geoConfig resolved geo config (for the map tile key/base) — from AlarmViewModel.
 * @param deviceLat/deviceLon the device's current position (from AlarmViewModel) — used
 *        to draw "me" + as the origin when saving a place.
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
    val me by vm.me.collectAsStateWithLifecycle()
    val connections by vm.connections.collectAsStateWithLifecycle()
    val circles by vm.circles.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val livePeers by vm.livePeers.collectAsStateWithLifecycle()
    val sharing by vm.isSharing.collectAsStateWithLifecycle()
    val connected by vm.socketConnected.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    // Screen lifecycle → open/close the shared socket.
    DisposableEffect(Unit) {
        vm.onScreenStart()
        onDispose { vm.onScreenStop() }
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

    var showAdd by remember { mutableStateOf(false) }
    var showCreateCircle by remember { mutableStateOf(false) }
    var showJoinCircle by remember { mutableStateOf(false) }
    var showAddPlace by remember { mutableStateOf(false) }
    var showName by remember { mutableStateOf(false) }

    val tileKey = geoConfig?.tileKey?.ifBlank { null }
    val tileBase = geoConfig?.baseUrl?.ifBlank { null }
    val mePoint = if (deviceLat != null && deviceLon != null) GeoPoint(deviceLat, deviceLon) else null

    // Merge REST last-known with the fresher socket positions → the map markers.
    val accepted = connections.filter { it.status == "accepted" }
    val incoming = connections.filter { it.incoming }
    val peerPoints = accepted.mapNotNull { c ->
        val live = livePeers[c.peerId]
        val loc = live ?: c.location
        if (loc == null) null
        else PeerPoint(c.peerId, GeoPoint(loc.lat, loc.lon),
            colorFor(c.peerId, c.peer?.color), ghost = c.location?.ghost == true)
    }

    Column(Modifier.fillMaxSize()) {
        // ── map ──────────────────────────────────────────────────────────────
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            if (tileKey != null) {
                TrackingMapView(
                    tileKey = tileKey,
                    tileBaseUrl = tileBase ?: com.example.alarm.maps.OlaMapsRepository.DEFAULT_TILE_BASE,
                    peers = peerPoints,
                    me = mePoint,
                    places = places.map { GeoPoint(it.lat, it.lon) },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(translate("Map unavailable"), color = Color.Gray)
                }
            }
            // Rover-ID chip (top-left) — tap to copy.
            me?.let { RoverIdChip(it.roverId, Modifier.align(Alignment.TopStart).padding(12.dp)) }
            // SOS (top-right).
            FloatingActionButton(
                onClick = { vm.raiseSos(deviceLat, deviceLon) },
                containerColor = Color(0xFFEF4444),
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(48.dp)
            ) { Icon(Icons.Default.Warning, "SOS", tint = Color.White) }
            // Share toggle (bottom-center).
            ExtendedFloatingActionButton(
                onClick = { if (sharing) vm.stopSharing() else requestShare() },
                containerColor = if (sharing) Color(0xFF009688) else Color(0xFF374151),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
            ) {
                Icon(if (sharing) Icons.Default.LocationOn else Icons.Default.LocationOff, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(translate(if (sharing) "Sharing ON" else "Start sharing"), color = Color.White)
            }
        }

        // Live/socket status strip.
        status?.let {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), fontSize = 13.sp)
                    TextButton(onClick = { vm.clearStatus() }) { Text(translate("Dismiss")) }
                }
            }
        }

        // ── list ───────────────────────────────────────────────────────────
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(translate("People"), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        modifier = Modifier.weight(1f))
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

            if (accepted.isEmpty()) {
                item { EmptyHint(translate("Share your Rover ID and add people to see them here.")) }
            }
            items(accepted, key = { "conn_${it.id}" }) { c ->
                PersonRow(
                    c, live = livePeers[c.peerId],
                    onPause = { vm.pauseConnection(c.id, !c.pausedByMe) },
                    onRemove = { vm.removeConnection(c.id) })
            }

            // Circles.
            item {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(translate("Circles"), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { showJoinCircle = true }) { Text(translate("Join")) }
                    TextButton(onClick = { showCreateCircle = true }) { Text(translate("New")) }
                }
            }
            items(circles, key = { "circle_${it.id}" }) { circle ->
                CircleRow(circle, onLeave = { vm.leaveCircle(circle.id) })
            }

            // Places.
            item {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(translate("Places"), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { showAddPlace = true },
                        enabled = mePoint != null) { Text(translate("Add here")) }
                }
            }
            items(places, key = { "place_${it.id}" }) { p ->
                PlaceRow(p, onDelete = { vm.deletePlace(p.id) })
            }

            // Sharing controls.
            item {
                Text(translate("My sharing"), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                me?.let { u ->
                    ToggleRow(translate("Pause sharing (I still see others)"), u.paused) { vm.setPaused(it) }
                    ToggleRow(translate("Ghost mode (freeze me at last point)"), u.ghost) { vm.setGhost(it) }
                    OutlinedButton(onClick = { showName = true }, Modifier.padding(top = 8.dp)) {
                        Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text(translate("Set my name"))
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ── dialogs ────────────────────────────────────────────────────────────
    if (showAdd) InputDialog(
        title = translate("Add by Rover ID"),
        label = translate("ROVER-XXXXXXX"),
        confirm = translate("Send request"),
        onDismiss = { showAdd = false },
        onConfirm = { code -> showAdd = false; vm.addByCode(code) { _, _ -> } })
    if (showCreateCircle) InputDialog(
        title = translate("New circle"), label = translate("Name (e.g. Family)"),
        confirm = translate("Create"), onDismiss = { showCreateCircle = false },
        onConfirm = { showCreateCircle = false; vm.createCircle(it) })
    if (showJoinCircle) InputDialog(
        title = translate("Join circle"), label = translate("Invite code"),
        confirm = translate("Join"), onDismiss = { showJoinCircle = false },
        onConfirm = { showJoinCircle = false; vm.joinCircle(it) { _, _ -> } })
    if (showName) InputDialog(
        title = translate("Set my name"), label = translate("Display name"),
        initial = me?.displayName ?: "", confirm = translate("Save"),
        onDismiss = { showName = false }, onConfirm = { showName = false; vm.setDisplayName(it) })
    if (showAddPlace && mePoint != null) InputDialog(
        title = translate("Save this place"), label = translate("Name (e.g. Home)"),
        confirm = translate("Save"), onDismiss = { showAddPlace = false },
        onConfirm = { showAddPlace = false; vm.createPlace(it, mePoint.latitude, mePoint.longitude, 150) })
}

// ── components ────────────────────────────────────────────────────────────────
@Composable
private fun RoverIdChip(roverId: String, modifier: Modifier) {
    val clip = LocalClipboardManager.current
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shadowElevation = 2.dp) {
        Row(Modifier.clickable { clip.setText(AnnotatedString(roverId)) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Share, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(roverId, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

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
    Text(text, fontSize = 13.sp, color = Color.Gray,
        modifier = Modifier.padding(vertical = 16.dp))
}

@Composable
private fun RequestRow(c: ConnectionDto, onAccept: () -> Unit, onDecline: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(c.peerId, c.peer?.color, c.peer?.displayName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.peer?.displayName ?: c.peer?.roverId ?: "Someone", fontWeight = FontWeight.Bold)
                Text("wants to connect", fontSize = 12.sp, color = Color.Gray)
            }
            IconButton(onClick = onAccept) { Icon(Icons.Default.Check, "Accept", tint = Color(0xFF10B981)) }
            IconButton(onClick = onDecline) { Icon(Icons.Default.Close, "Decline", tint = Color(0xFFEF4444)) }
        }
    }
}

@Composable
private fun PersonRow(c: ConnectionDto, live: LocationDto?, onPause: () -> Unit, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(c.peerId, c.peer?.color, c.peer?.displayName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.peer?.displayName ?: c.peer?.roverId ?: "Rover", fontWeight = FontWeight.Bold)
                val hasLoc = (live ?: c.location) != null
                val dist = c.distanceM
                val sub = when {
                    c.pausedByPeer -> "Paused sharing with you"
                    !hasLoc -> "Location unavailable"
                    dist != null -> distanceText(dist)
                    else -> "Sharing location"
                }
                Text(sub, fontSize = 12.sp, color = Color.Gray)
            }
            val battery = (live ?: c.location)?.battery
            if (battery != null) {
                Icon(Icons.Default.BatteryFull, null, Modifier.size(16.dp),
                    tint = if (battery <= 15) Color(0xFFEF4444) else Color.Gray)
                Text("$battery%", fontSize = 11.sp, color = Color.Gray)
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onPause) {
                Icon(if (c.pausedByMe) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    "Pause", tint = if (c.pausedByMe) Color(0xFFF59E0B) else Color.Gray)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Default.PersonRemove, "Remove", tint = Color.Gray) }
        }
    }
}

@Composable
private fun CircleRow(circle: CircleDto, onLeave: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Group, null, tint = Color(0xFF6366F1))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(circle.name, fontWeight = FontWeight.Bold)
                Text("${circle.memberCount} members · code ${circle.inviteCode}",
                    fontSize = 12.sp, color = Color.Gray)
            }
            TextButton(onClick = onLeave) { Text(if (circle.isOwner) "Disband" else "Leave") }
        }
    }
}

@Composable
private fun PlaceRow(p: PlaceDto, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Place, null, tint = Color(0xFFF59E0B))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.Bold)
                Text("${p.radiusM}m radius" + if (p.inside) " · you're here" else "",
                    fontSize = 12.sp, color = Color.Gray)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = Color.Gray) }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
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

private fun distanceText(m: Double): String =
    if (m < 1000) "${m.toInt()} m away" else String.format("%.1f km away", m / 1000.0)

@Composable
private fun InputDialog(
    title: String, label: String, confirm: String,
    initial: String = "",
    onDismiss: () -> Unit, onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it },
                label = { Text(label) }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
