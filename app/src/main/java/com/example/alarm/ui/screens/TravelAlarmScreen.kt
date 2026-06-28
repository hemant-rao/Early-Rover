package com.example.alarm.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.layout
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.alarm.data.TravelAlarm
import com.example.alarm.location.CityInfo
import com.example.alarm.viewmodel.AlarmViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

// Shared radius range so the create/edit dialog slider and the per-card slider
// always span the same values. Using different ranges previously caused a
// long-radius (e.g. 50 km) geofence to be visually pinned and then silently
// shrunk the moment the user touched the card slider.
private val TRAVEL_RADIUS_MIN = 0.5f
private val TRAVEL_RADIUS_MAX = 50.0f
private val TRAVEL_RADIUS_RANGE = TRAVEL_RADIUS_MIN..TRAVEL_RADIUS_MAX
private const val TRAVEL_RADIUS_STEPS = 99

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TravelAlarmScreen(
    viewModel: AlarmViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Database state
    val travelAlarms by viewModel.allTravelAlarms.collectAsStateWithLifecycle()

    // Service tracking parameters
    val isTracking by viewModel.isTravelTrackingActive.collectAsStateWithLifecycle()
    val currentLocation by viewModel.travelCurrentLocation.collectAsStateWithLifecycle()
    val startLocation by viewModel.travelStartLocation.collectAsStateWithLifecycle()
    val totalTripDistance by viewModel.travelTotalTripDistance.collectAsStateWithLifecycle()
    val rawNearestAlarm by viewModel.travelNearestAlarm.collectAsStateWithLifecycle()
    val nearestAlarm = remember(rawNearestAlarm, travelAlarms) {
        rawNearestAlarm?.let { na ->
            if (travelAlarms.any { it.id == na.id }) na else null
        }
    }
    val distanceToNearest by viewModel.travelDistanceToNearest.collectAsStateWithLifecycle()
    val trackingStatusMsg by viewModel.travelStatusMsg.collectAsStateWithLifecycle()
    val currentSpeed by viewModel.travelCurrentSpeed.collectAsStateWithLifecycle()

    // Local Compose State
    var showAddDialog by remember { mutableStateOf(false) }
    var editingAlarmId by remember { mutableStateOf<Int?>(null) }
    // Preserve the original active state of the alarm being edited so that saving
    // an edit does not silently re-enable an alarm the user had toggled OFF.
    var editingActive by remember { mutableStateOf(true) }
    val isHindi = viewModel.currentLanguage.collectAsStateWithLifecycle().value == "hi"

    // §689 — geo gateway remote config (from the OdioBook backend). Maps render +
    // Ola search/route only when the admin has maps ON; the restricted tile key +
    // tiles base come from app-config. When maps are off we transparently fall back
    // to the existing OSM city search. No Ola key lives in the app anymore.
    val geoConfig by viewModel.geoConfig.collectAsStateWithLifecycle()
    val mapsOn = geoConfig?.mapsEnabled == true
    val tileKey = geoConfig?.tileKey ?: ""
    val tileBaseUrl = geoConfig?.baseUrl ?: com.example.alarm.maps.OlaMapsRepository.DEFAULT_TILE_BASE
    val mapReady = mapsOn && tileKey.isNotBlank()
    fun geoFeat(key: String): Boolean = geoConfig?.features?.get(key) ?: true

    // Decoded route (FROM -> TO) for the map, fetched via the geo gateway.
    var routePoints by remember { mutableStateOf<List<com.example.alarm.maps.GeoPoint>?>(null) }

    // Waypoint properties (TO)
    var waypointLabel by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("STATION") }
    var waypointLat by remember { mutableStateOf("") }
    var waypointLng by remember { mutableStateOf("") }
    var waypointRadius by remember { mutableFloatStateOf(2.0f) }
    var waypointTts by remember { mutableStateOf(true) }
    var waypointVibration by remember { mutableStateOf(true) }
    var waypointFlash by remember { mutableStateOf(false) }

    // Start Location properties (FROM)
    var startLabel by remember { mutableStateOf("Starting Point") }
    var startLat by remember { mutableStateOf("") }
    var startLng by remember { mutableStateOf("") }

    // GPS detection state for the "use my location" button in the start field. The permission/
    // detection is async; awaitingGpsForStart drives the icon spinner until the dedicated one-shot
    // travel-start fix delivers a result via its callback (see startGpsPermissionLauncher below).
    // We deliberately do NOT read viewModel.latitude/longitude/locationName here: those are the
    // GLOBAL active-location StateFlows, and the FROM field must never hijack the dashboard's active
    // city. The result is delivered ONLY through triggerTravelStartLocation's callback.
    var awaitingGpsForStart by remember { mutableStateOf(false) }
    val isDetectingLocation by viewModel.isDetectingLocation.collectAsStateWithLifecycle()
    
    // Deletion confirmation
    var alarmToDelete by remember { mutableStateOf<com.example.alarm.data.TravelAlarm?>(null) }

    // Remote lookup parameters
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<CityInfo>>(emptyList()) }
    var isSearchingNominatim by remember { mutableStateOf(false) }

    // Start location lookup parameters
    var searchQueryStart by remember { mutableStateOf("") }
    var searchResultsStart by remember { mutableStateOf<List<CityInfo>>(emptyList()) }
    var isSearchingNominatimStart by remember { mutableStateOf(false) }

    // Launcher for location permissions
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            viewModel.startTravelTracking()
        }
    }

    // Dedicated launcher for the inline "My Location" button in the start (FROM) field. It requests
    // a FRESH one-shot GPS fix, fills ONLY the start fields, and must NOT start travel tracking nor
    // mutate the dashboard's active city / auto-detect mode. triggerTravelStartLocation delivers the
    // fix exclusively via its callback; we ignore the global active-location StateFlows entirely.
    val startGpsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            viewModel.triggerTravelStartLocation { lat, lng, name ->
                startLabel = if (name.isNotEmpty()) name else "My Location"
                startLat = lat.toString()
                startLng = lng.toString()
                awaitingGpsForStart = false
            }
        } else {
            awaitingGpsForStart = false
        }
    }

    fun t(en: String, hi: String): String = if (isHindi) hi else en

    var searchJobDest by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun runAddressLookup(query: String) {
        if (query.trim().length < 3) return
        isSearchingNominatim = true
        searchJobDest?.cancel()
        searchJobDest = coroutineScope.launch {
            try {
                kotlinx.coroutines.delay(500) // debounce
                val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val bias = currentLocation?.let { com.example.alarm.maps.GeoPoint(it.latitude, it.longitude) }
                    val ola = if (mapsOn && geoFeat("autocomplete"))
                        com.example.alarm.maps.OlaMapsRepository.searchPlaces(query, bias)
                    else emptyList()
                    if (ola.isNotEmpty()) ola
                    else com.example.alarm.location.LocationHelper(context).searchCity(query)
                }
                searchResults = results
                isSearchingNominatim = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                // ignore
            } catch (e: Exception) {
                isSearchingNominatim = false
            }
        }
    }

    var searchJobStart by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun runStartAddressLookup(query: String) {
        if (query.trim().length < 3) return
        isSearchingNominatimStart = true
        searchJobStart?.cancel()
        searchJobStart = coroutineScope.launch {
            try {
                kotlinx.coroutines.delay(500) // debounce
                val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val bias = currentLocation?.let { com.example.alarm.maps.GeoPoint(it.latitude, it.longitude) }
                    val ola = if (mapsOn && geoFeat("autocomplete"))
                        com.example.alarm.maps.OlaMapsRepository.searchPlaces(query, bias)
                    else emptyList()
                    if (ola.isNotEmpty()) ola
                    else com.example.alarm.location.LocationHelper(context).searchCity(query)
                }
                searchResultsStart = results
                isSearchingNominatimStart = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                // ignore
            } catch (e: Exception) {
                isSearchingNominatimStart = false
            }
        }
    }

    // Map points derived from live tracking + saved destinations.
    val mapCurrent = currentLocation?.let { com.example.alarm.maps.GeoPoint(it.latitude, it.longitude) }
    val mapFrom = startLocation?.let { com.example.alarm.maps.GeoPoint(it.latitude, it.longitude) }
    val mapTo = remember(nearestAlarm, travelAlarms) {
        (nearestAlarm ?: travelAlarms.firstOrNull { it.active })?.let {
            com.example.alarm.maps.GeoPoint(it.latitude, it.longitude)
        }
    }
    // Route origin: the start fix when tracking, else the device's current position.
    val routeFrom = mapFrom ?: mapCurrent
    LaunchedEffect(routeFrom, mapTo, mapsOn) {
        val f = routeFrom; val t = mapTo
        routePoints = if (f != null && t != null && mapsOn && geoFeat("directions")) {
            com.example.alarm.maps.OlaMapsRepository.route(f, t)?.points
        } else null
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp)
        ) {
            // Header Info Card
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        text = t("LOCATION ALERTS", "लोकेशन अलर्ट्स"),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            color = SleekPrimary,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = t(
                            "Smart GPS alerts with alarms and voice notifications near your stop.",
                            "स्टॉप पास आते ही अलार्म और आवाज़ द्वारा गतिशील सुरक्षा अलर्ट।"
                        ),
                        fontSize = 11.sp,
                        color = SleekMutedText,
                        lineHeight = 15.sp
                    )
                }
            }

            // Real-time tracking progress card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = SleekCardBg.copy(alpha = 0.85f)),
                    border = BorderStroke(0.5.dp, SleekBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(
                                            if (isTracking) Color(0xFF10B981) else SleekMutedText,
                                            CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isTracking) t("ACTIVE JOURNEY TRACKER RUNNING", "लाइव यात्रा ट्रैकर चालू है") else t("JOURNEY TRACKER INACTIVE", "यात्रा ट्रैकर निष्क्रिय है"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isTracking) Color(0xFF10B981) else SleekMutedText
                                )
                            }
                            Text(
                                text = if (currentLocation != null) "GPS: ON" else "GPS: OFF",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (currentLocation != null) SleekSecondary else SleekMutedText
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = t("Current speed", "वर्तमान गति"), fontSize = 11.sp, color = SleekMutedText)
                                Text(
                                    text = String.format(Locale.US, "%.1f km/h", currentSpeed),
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Black,
                                    color = SleekActiveText
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(text = t("Nearest Waypoint", "निकटतम गंतव्य"), fontSize = 11.sp, color = SleekMutedText)
                                Text(
                                    text = nearestAlarm?.label ?: t("None set", "कोई नहीं"),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (nearestAlarm != null) SleekPrimary else SleekMutedText
                                )
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(SleekBorder.copy(alpha = 0.5f)))
 
                        // LIVE ROUTE TRANSIT LOOKUP (FROM ➔ TO)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SleekBackground.copy(alpha = 0.5f)),
                            border = BorderStroke(0.5.dp, SleekBorder),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = t("LIVE JOURNEY TRACKER (FROM ➔ TO)", "लाइव यात्रा ट्रैकर (प्रस्थान ➔ गंतव्य)"),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    color = SleekSecondary,
                                    letterSpacing = 1.sp
                                )
 
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // From column
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(if (isTracking && startLocation != null) Color(0xFF10B981) else SleekMutedText, CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = t("FROM (Starting Point)", "प्रस्थान स्थान (सफ़र की शुरुआत)"),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = SleekMutedText
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = if (isTracking && startLocation != null) {
                                                String.format(Locale.US, "Lat: %.4f, Lng: %.4f", startLocation!!.latitude, startLocation!!.longitude)
                                            } else {
                                                t("Waiting for Tracker start...", "ट्रैकर शुरू होने का इंतज़ार...")
                                            },
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = SleekActiveText
                                        )
                                    }
 
                                    // Waypoint connector
                                    Icon(
                                        imageVector = Icons.Default.ArrowForward,
                                        contentDescription = null,
                                        tint = if (isTracking) SleekPrimary else SleekMutedText,
                                        modifier = Modifier.padding(horizontal = 8.dp).size(20.dp)
                                    )
 
                                    // To column
                                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = t("TO (Nearest Destination)", "गंतव्य स्थान (निकटतम)"),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = SleekMutedText
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(if (nearestAlarm != null) SleekPrimary else SleekMutedText, CircleShape)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = nearestAlarm?.label ?: t("None set", "कोई नहीं"),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (nearestAlarm != null) SleekPrimary else SleekActiveText
                                        )
                                        if (nearestAlarm != null) {
                                            Text(
                                                text = String.format(Locale.US, "Lat: %.3f, Lng: %.3f", nearestAlarm!!.latitude, nearestAlarm!!.longitude),
                                                fontSize = 9.sp,
                                                color = SleekMutedText
                                            )
                                        }
                                        
                                        val activeCount = travelAlarms.count { it.active }
                                        if (activeCount > 1) {
                                            Text(
                                                text = "+ ${activeCount - 1} more active alerts",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = SleekSecondary,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }
                                }
 
                                if (isTracking && distanceToNearest > 0) {
                                    // Live distance badge
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(SleekPrimary.copy(alpha = 0.12f))
                                            .padding(vertical = 8.dp, horizontal = 12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(
                                                    text = t("LIVE DISTANCE REMAINING:", "बची हुई लाइव दूरी:"),
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = SleekPrimary
                                                )
                                                Text(
                                                    text = t("⏳ Updates dynamically on motion", "⏳ गति के अनुसार जीपीएस द्वारा स्वतः अपडेट"),
                                                    fontSize = 8.sp,
                                                    color = SleekMutedText
                                                )
                                            }
                                            Text(
                                                text = String.format(Locale.US, "%.3f km", distanceToNearest),
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Black,
                                                color = SleekPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
 
                        if (isTracking && distanceToNearest > 0) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val targetRadius = nearestAlarm?.radiusKm ?: 2.0
                                val progress = if (totalTripDistance > 0.1) {
                                    (1.0f - (distanceToNearest / totalTripDistance).toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
 
                                LinearProgressIndicator(
                                    progress = progress,
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = SleekPrimary,
                                    trackColor = SleekBorder.copy(alpha = 0.5f)
                                )
 
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = t(
                                            "Total Trip Distance: ${String.format(Locale.US, "%.1f", totalTripDistance)} km",
                                            "कुल सफ़र की दूरी: ${String.format(Locale.US, "%.1f", totalTripDistance)} किमी"
                                        ),
                                        fontSize = 10.sp,
                                        color = SleekMutedText
                                    )
                                    Text(
                                        text = "${String.format(Locale.US, "%.1f", targetRadius)} km before arrival",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekSecondary
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = if (isTracking) t("Waiting for satellite coordinates...", "जीपीएस स्थिति मिलने का इंतज़ार...") else t("Start Tracking to safeguard your route (FROM ➔ TO).", "लाइव यात्रा सुरक्षा शुरू करने के लिए ट्रैकिंग चालू करें।"),
                                fontSize = 11.sp,
                                color = SleekMutedText,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            )
                        }

                        if (isTracking) {
                            Text(
                                text = trackingStatusMsg,
                                fontSize = 11.sp,
                                color = SleekSecondary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        }

                        Button(
                            onClick = {
                                val activeAlarms = travelAlarms.filter { it.active }
                                if (isTracking) {
                                    viewModel.stopTravelTracking()
                                } else if (travelAlarms.isEmpty()) {
                                    android.widget.Toast.makeText(
                                        context,
                                        t(
                                            "Add a travel alarm first, then start the sentry.",
                                            "पहले एक सफ़र अलार्म जोड़ें, फिर सुरक्षा चालू करें।"
                                        ),
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                } else if (activeAlarms.isEmpty()) {
                                    android.widget.Toast.makeText(
                                        context,
                                        t(
                                            "Enable at least one travel alarm before starting seamless sentry!",
                                            "सुरक्षा ट्रैकिंग चालू करने से पहले कृपया कम से कम एक सफ़र अलार्म चालू (Active) करें!"
                                        ),
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("toggle_watchdog_btn"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isTracking) Color(0xFFDC2626) else SleekPrimary,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = if (isTracking) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isTracking) t("STOP TRACKING", "ट्रैकिंग बंद करें") else t("START TRACKING", "ट्रैकिंग शुरू करें"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }

            // Live interactive Ola map (current + FROM/TO markers + route, all together).
            item {
                if (mapReady) {
                    Card(
                        modifier = Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(24.dp)),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = SleekCardBg.copy(alpha = 0.55f)),
                        border = BorderStroke(0.5.dp, SleekBorder)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.Map, contentDescription = null, tint = SleekPrimary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = t("LIVE MAP", "लाइव मैप"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = SleekSecondary,
                                    letterSpacing = 1.sp
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(300.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            ) {
                                com.example.alarm.maps.OlaMapView(
                                    tileKey = tileKey,
                                    tileBaseUrl = tileBaseUrl,
                                    modifier = Modifier.fillMaxSize(),
                                    current = mapCurrent,
                                    from = mapFrom,
                                    to = mapTo,
                                    route = routePoints,
                                    followCurrent = isTracking
                                )
                            }
                            Text(
                                text = t(
                                    "🟢 Start   🔵 You (live)   🟣 Destination",
                                    "🟢 प्रस्थान   🔵 आप (लाइव)   🟣 गंतव्य"
                                ),
                                fontSize = 9.sp,
                                color = SleekMutedText,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = SleekCardBg.copy(alpha = 0.4f)),
                        border = BorderStroke(0.5.dp, SleekBorder)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.Default.Map, contentDescription = null, tint = SleekMutedText, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = t(
                                    "Live map is currently unavailable. Place search still works; the map turns on automatically once enabled.",
                                    "लाइव मैप अभी उपलब्ध नहीं है। जगह खोज काम करती रहेगी; सक्षम होते ही मैप अपने-आप चालू हो जाएगा।"
                                ),
                                fontSize = 11.sp,
                                color = SleekMutedText,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            // Presets and Saved stops
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = t("SAVED DESTINATIONS", "सेव की गई जगहें"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = SleekSecondary,
                        letterSpacing = 1.sp
                    )

                    IconButton(
                        onClick = {
                            // Reset ALL dialog-scoped state so a new entry never inherits the
                            // previous waypoint's category / alert toggles.
                            waypointLabel = ""
                            waypointLat = ""
                            waypointLng = ""
                            waypointRadius = 2.0f
                            selectedCategory = "STATION"
                            waypointTts = true
                            waypointVibration = true
                            waypointFlash = false
                            searchResults = emptyList()
                            searchQuery = ""
                            startLabel = "Starting Point"
                            startLat = currentLocation?.latitude?.toString() ?: ""
                            startLng = currentLocation?.longitude?.toString() ?: ""
                            searchResultsStart = emptyList()
                            searchQueryStart = ""
                            editingActive = true
                            editingAlarmId = null
                            showAddDialog = true
                        },
                        modifier = Modifier
                            .size(28.dp)
                            .background(SleekCardBg, CircleShape)
                            .border(BorderStroke(0.5.dp, SleekBorder), CircleShape)
                            .testTag("add_destination_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Destination",
                            tint = SleekPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (travelAlarms.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = SleekCardBg.copy(alpha = 0.4f)),
                        border = BorderStroke(0.5.dp, SleekBorder)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tram,
                                contentDescription = null,
                                tint = SleekMutedText,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = t("No waypoints saved yet.", "अभी तक कोई अलार्म स्टॉप नहीं है।"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekActiveText
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = t(
                                    "Tap the '+' icon to save your Home, Office, Bus Stop, or Railway Station. Tracker alarms will go off before arrival!",
                                    "घर, ऑफिस, बस स्टॉप या रेलवे स्टेशन जोड़ने के लिए '+' दबाएं। आपके स्टॉप पर पहुंचने से पहले ट्रैकर बज उठेगा!"
                                ),
                                fontSize = 11.sp,
                                color = SleekMutedText,
                                textAlign = TextAlign.Center,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            } else {
                items(travelAlarms) { alarm ->
                    TravelAlarmCard(
                        alarm = alarm,
                        onToggleActive = {
                            if (isTracking && alarm.active) {
                                android.widget.Toast.makeText(
                                    context,
                                    t(
                                        "Journey Tracker is active! Please stop Tracker before turning off travel alarms.",
                                        "यात्रा ट्रैकिंग चालू है! ट्रेवल अलार्म को बंद करने से पहले कृपया ट्रैकिंग बंद करें।"
                                    ),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            } else {
                                viewModel.toggleTravelAlarmActive(alarm)
                            }
                        },
                        onDelete = {
                            if (isTracking) {
                                android.widget.Toast.makeText(
                                    context,
                                    t(
                                        "Journey tracking is active! Please stop Tracker before deleting a travel alarm.",
                                        "यात्रा ट्रैकिंग चालू है! कृपया ट्रेवल अलार्म को हटाने से पहले ट्रैकिंग बंद करें।"
                                    ),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            } else {
                                alarmToDelete = alarm
                            }
                        },
                        onEdit = {
                            if (isTracking) {
                                android.widget.Toast.makeText(
                                    context,
                                    t(
                                        "Journey Tracker is active! Please stop Tracker before editing travel alarms.",
                                        "यात्रा ट्रैकिंग चालू है! कृपया ट्रेवल अलार्म बदलने से पहले ट्रैकिंग बंद करें।"
                                    ),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            } else {
                                // Prefill the dialog state from this saved alarm, then open it in edit mode.
                                waypointLabel = alarm.label
                                selectedCategory = alarm.category
                                waypointLat = alarm.latitude.toString()
                                waypointLng = alarm.longitude.toString()
                                waypointRadius = alarm.radiusKm.toFloat()
                                waypointTts = alarm.ttsEnabled
                                waypointVibration = alarm.vibrationEnabled
                                waypointFlash = alarm.flashEnabled
                                searchResults = emptyList()
                                searchQuery = ""
                                startLabel = alarm.startLabel
                                startLat = alarm.startLatitude?.toString() ?: ""
                                startLng = alarm.startLongitude?.toString() ?: ""
                                searchResultsStart = emptyList()
                                searchQueryStart = ""
                                editingActive = alarm.active
                                editingAlarmId = alarm.id
                                showAddDialog = true
                            }
                        },
                        onUpdateRadius = { radius ->
                            if (isTracking) {
                                android.widget.Toast.makeText(
                                    context,
                                    t(
                                        "Journey Tracker is active! Please stop Tracker before changing alert distance.",
                                        "यात्रा ट्रैकिंग चालू है! अलार्म दूरी बदलने से पहले कृपया ट्रैकिंग बंद करें।"
                                    ),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            } else {
                                viewModel.updateTravelAlarm(alarm.copy(radiusKm = radius))
                            }
                        },
                        speedKmh = currentSpeed,
                        currentLocation = currentLocation,
                        isHindi = isHindi,
                        isTrackingActive = isTracking,
                        t = ::t
                    )
                }
            }
        }

        // Delete Confirmation Dialog
        if (alarmToDelete != null) {
            AlertDialog(
                onDismissRequest = { alarmToDelete = null },
                title = { Text(t("Delete Alarm", "अलार्म हटाएं")) },
                text = { Text(t("Are you sure you want to delete this travel alarm?", "क्या आप इस सफ़र अलार्म को हटाना चाहते हैं?")) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteTravelAlarm(alarmToDelete!!)
                            alarmToDelete = null
                        }
                    ) {
                        Text(t("Delete", "हटाएं"), color = Color.Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { alarmToDelete = null }) {
                        Text(t("Cancel", "रद्द करें"))
                    }
                }
            )
        }

        // Add Dialog placed SAFELY at top composable level (outside the LazyColumn structural context!)
        if (showAddDialog) {
            Dialog(
                onDismissRequest = { showAddDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .border(BorderStroke(1.dp, SleekBorder), shape = RoundedCornerShape(24.dp))
                        .shadow(24.dp, RoundedCornerShape(24.dp)),
                    color = SleekCardBg,
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (editingAlarmId != null) t("Edit Travel Alarm", "सफ़र अलार्म बदलें") else t("Create Travel Alarm", "नया सफ़र अलार्म जोड़ें"),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekActiveText
                            )
                            IconButton(onClick = { showAddDialog = false; editingAlarmId = null }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = SleekMutedText,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                             // --- SECTION 1: START LOCATION (FROM) ---
                             Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                 Text(
                                     text = t("1. Starting Point / Current Location (FROM)", "1. प्रस्थान स्थान / सफ़र की शुरुआत (FROM)"),
                                     fontSize = 11.sp,
                                     fontWeight = FontWeight.Bold,
                                     color = SleekSecondary
                                 )
                                 
                                 val showStartCoords = startLat.isNotEmpty() && startLng.isNotEmpty()
                                 Text(
                                     text = if (showStartCoords) {
                                         t("📍 Start GPS Coordinates: $startLat, $startLng", "📍 प्रस्थान निर्देशांक: $startLat, $startLng")
                                     } else {
                                         t("📍 No Starting Point coordinates loaded (Use list search/GPS above)", "📍 कोई निर्देशांक लोड नहीं है (ऊपर खोज या जीपीएस का उपयोग करें)")
                                     },
                                     fontSize = 10.sp,
                                     color = if (showStartCoords) SleekSecondary else SleekMutedText,
                                     fontWeight = FontWeight.Bold,
                                     modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                                 )
 
                                 Box(modifier = Modifier.fillMaxWidth()) {
                                     Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .background(SleekBackground, RoundedCornerShape(12.dp))
                                    .border(BorderStroke(0.5.dp, SleekBorder), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = SleekMutedText,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                BasicTextField(
                                    value = startLabel,
                                    onValueChange = { q ->
                                        startLabel = q
                                        val parts = q.replace("📍", "").split(",")
                                            .map { it.trim() }
                                            .filter { it.isNotEmpty() }
                                        if (parts.size == 2) {
                                            val latVal = parts[0].toDoubleOrNull()
                                            val lngVal = parts[1].toDoubleOrNull()
                                            if (latVal != null && lngVal != null && latVal in -90.0..90.0 && lngVal in -180.0..180.0) {
                                                startLat = latVal.toString()
                                                startLng = lngVal.toString()
                                            } else {
                                                // Not valid coords -> clear any previously parsed
                                                // coords so the saved value matches the visible label.
                                                startLat = ""
                                                startLng = ""
                                                runStartAddressLookup(q)
                                            }
                                        } else {
                                            startLat = ""
                                            startLng = ""
                                            runStartAddressLookup(q)
                                        }
                                    },
                                    modifier = Modifier.weight(1f).testTag("start_search_input"),
                                    textStyle = TextStyle(color = SleekActiveText, fontSize = 13.sp),
                                    singleLine = true,
                                    cursorBrush = SolidColor(SleekPrimary),
                                    decorationBox = { inner ->
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (startLabel.isEmpty()) {
                                                Text(
                                                    text = t("Type starting point or search city...", "प्रस्थान स्थान का नाम या शहर खोजें..."),
                                                    color = SleekMutedText,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            inner()
                                        }
                                    }
                                )
                                if (isSearchingNominatimStart) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 1.5.dp,
                                        color = SleekPrimary
                                    )
                                }
                                // Compact inline GPS icon button
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .width(1.dp)
                                        .height(16.dp)
                                        .background(SleekBorder)
                                )
                                IconButton(
                                    onClick = {
                                        // Trigger my location retrieval. Detection is async, so we set
                                        // a flag and let the LaunchedEffect fill the fields once the
                                        // GPS coordinates actually arrive (rather than reading the
                                        // still-stale values synchronously here).
                                        awaitingGpsForStart = true
                                        // Detection is kicked off in the launcher callback once
                                        // permission state is known, so it always runs against a
                                        // granted permission (no stale read, no tracking start).
                                        startGpsPermissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                                Manifest.permission.ACCESS_COARSE_LOCATION
                                            )
                                        )
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (awaitingGpsForStart && isDetectingLocation)
                                            Icons.Default.GpsNotFixed else Icons.Default.GpsFixed,
                                        contentDescription = "My Location",
                                        tint = SleekPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            // Start Location dropdown overlay
                            if (searchResultsStart.isNotEmpty() && startLabel.length >= 3) {
                                DropdownMenu(
                                    expanded = true,
                                    onDismissRequest = { searchResultsStart = emptyList() },
                                    properties = androidx.compose.ui.window.PopupProperties(focusable = false),
                                    modifier = Modifier
                                        .fillMaxWidth(0.9f)
                                        .heightIn(max = 250.dp)
                                        .background(SleekCardBg)
                                        .border(BorderStroke(1.dp, SleekBorder), RoundedCornerShape(12.dp))
                                ) {
                                    searchResultsStart.forEach { city ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Default.LocationCity,
                                                        contentDescription = null,
                                                        tint = SleekMutedText,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(16.dp))
                                                    Column {
                                                        Text(
                                                            text = "${city.name}, ${city.country}",
                                                            fontWeight = FontWeight.SemiBold,
                                                            fontSize = 14.sp,
                                                            color = SleekActiveText
                                                        )
                                                        Text(
                                                            text = "Lat: ${String.format(Locale.US, "%.2f", city.latitude)} | Lng: ${String.format(Locale.US, "%.2f", city.longitude)}",
                                                            fontSize = 11.sp,
                                                            color = SleekMutedText
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                startLabel = city.name
                                                startLat = city.latitude.toString()
                                                startLng = city.longitude.toString()
                                                searchResultsStart = emptyList()
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SleekBorder.copy(alpha = 0.5f)))

                         // --- SECTION 2: DESTINATION LOCATION (TO) ---
                         Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                             Text(
                                 text = t("2. Destination Point / Stop Location (TO)", "2. गंतव्य स्थान / सफ़र की समाप्ति (TO)"),
                                 fontSize = 11.sp,
                                 fontWeight = FontWeight.Bold,
                                 color = SleekSecondary
                             )
                             
                             val showWaypointCoords = waypointLat.isNotEmpty() && waypointLng.isNotEmpty()
                             Text(
                                 text = if (showWaypointCoords) {
                                     t("📍 Destination GPS Coordinates: $waypointLat, $waypointLng", "📍 गंतव्य निर्देशांक: $waypointLat, $waypointLng")
                                 } else {
                                     t("📍 No Destination Point coordinates loaded (Use list search/GPS above)", "📍 कोई निर्देशांक लोड नहीं है (ऊपर खोज या जीपीएस का उपयोग करें)")
                                 },
                                 fontSize = 10.sp,
                                 color = if (showWaypointCoords) SleekSecondary else SleekMutedText,
                                 fontWeight = FontWeight.Bold,
                                 modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                             )
 
                             Box(modifier = Modifier.fillMaxWidth()) {
                                 Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .background(SleekBackground, RoundedCornerShape(12.dp))
                                    .border(BorderStroke(0.5.dp, SleekBorder), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = SleekMutedText,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                BasicTextField(
                                    value = waypointLabel,
                                    onValueChange = { q ->
                                        waypointLabel = q
                                        val parts = q.replace("📍", "").split(",")
                                            .map { it.trim() }
                                            .filter { it.isNotEmpty() }
                                        if (parts.size == 2) {
                                            val latVal = parts[0].toDoubleOrNull()
                                            val lngVal = parts[1].toDoubleOrNull()
                                            if (latVal != null && lngVal != null && latVal in -90.0..90.0 && lngVal in -180.0..180.0) {
                                                waypointLat = latVal.toString()
                                                waypointLng = lngVal.toString()
                                            } else {
                                                // Not valid coords -> clear any previously parsed
                                                // coords so the saved value matches the visible label.
                                                waypointLat = ""
                                                waypointLng = ""
                                                runAddressLookup(q)
                                            }
                                        } else {
                                            waypointLat = ""
                                            waypointLng = ""
                                            runAddressLookup(q)
                                        }
                                    },
                                    modifier = Modifier.weight(1f).testTag("destination_search_input"),
                                    textStyle = TextStyle(color = SleekActiveText, fontSize = 13.sp),
                                    singleLine = true,
                                    cursorBrush = SolidColor(SleekPrimary),
                                    decorationBox = { inner ->
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (waypointLabel.isEmpty()) {
                                                Text(
                                                    text = t("Type destination or search city/station...", "गंतव्य स्थान का नाम या शहर/स्टेशन खोजें..."),
                                                    color = SleekMutedText,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            inner()
                                        }
                                    }
                                )
                                if (isSearchingNominatim) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 1.5.dp,
                                        color = SleekPrimary
                                    )
                                }
                            }

                            // Destination overlay popup suggestions
                            if (searchResults.isNotEmpty() && waypointLabel.length >= 3) {
                                DropdownMenu(
                                    expanded = true,
                                    onDismissRequest = { searchResults = emptyList() },
                                    properties = androidx.compose.ui.window.PopupProperties(focusable = false),
                                    modifier = Modifier
                                        .fillMaxWidth(0.9f)
                                        .heightIn(max = 250.dp)
                                        .background(SleekCardBg)
                                        .border(BorderStroke(1.dp, SleekBorder), RoundedCornerShape(12.dp))
                                ) {
                                    searchResults.forEach { city ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Default.LocationCity,
                                                        contentDescription = null,
                                                        tint = SleekMutedText,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(16.dp))
                                                    Column {
                                                        Text(
                                                            text = "${city.name}, ${city.country}",
                                                            fontWeight = FontWeight.SemiBold,
                                                            fontSize = 14.sp,
                                                            color = SleekActiveText
                                                        )
                                                        Text(
                                                            text = "Lat: ${String.format(Locale.US, "%.2f", city.latitude)} | Lng: ${String.format(Locale.US, "%.2f", city.longitude)}",
                                                            fontSize = 11.sp,
                                                            color = SleekMutedText
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                waypointLabel = city.name
                                                waypointLat = city.latitude.toString()
                                                waypointLng = city.longitude.toString()
                                                searchResults = emptyList()
                                            }
                                        )
                                    }
                                }
                            }

                        }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = t("3. Select Category Profile", "3. कैटेगरी प्रोफाइल चुनें"),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekSecondary
                            )

                            Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val categories = listOf("STATION", "BUS_STOP", "HOME", "OFFICE", "CUSTOM")
                            categories.forEach { cat ->
                                val isSelected = selectedCategory == cat
                                val itemLabel = when (cat) {
                                    "STATION" -> "🚉 STN"
                                    "BUS_STOP" -> "🚌 BUS"
                                    "HOME" -> "🏡 HOME"
                                    "OFFICE" -> "🏢 OFF"
                                    else -> "📍 OTHER"
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) SleekPrimary else SleekBackground)
                                        .border(BorderStroke(0.5.dp, SleekBorder), RoundedCornerShape(8.dp))
                                        .clickable { selectedCategory = cat }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = itemLabel,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else SleekMutedText
                                    )
                                }
                            }
                        }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = t("4. Trigger Alarm Distance", "4. अलार्म कितनी दूरी पहले चाहिए"),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekSecondary
                                    )
                                    Text(
                                        text = t(
                                            "Set how many kilometers before your target station/stop you want to wake up.",
                                            "चुनें कि आपके लक्षित स्टेशन/स्टॉप से कितने किलोमीटर पहले आप उठना चाहते हैं।"
                                        ),
                                        fontSize = 9.sp,
                                        color = SleekMutedText,
                                        lineHeight = 12.sp,
                                        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp, end = 8.dp)
                                    )
                                }
                                Text(
                                    text = "${String.format(Locale.US, "%.1f", waypointRadius)} km",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekPrimary
                                )
                            }

                            Slider(
                                value = waypointRadius,
                                onValueChange = { waypointRadius = it },
                                valueRange = TRAVEL_RADIUS_RANGE,
                                steps = TRAVEL_RADIUS_STEPS,
                                colors = SliderDefaults.colors(
                                    thumbColor = SleekPrimary,
                                    activeTrackColor = SleekPrimary,
                                    inactiveTrackColor = SleekBorder
                                ),
                                modifier = Modifier.height(24.dp)
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = t("5. Alert Methods", "5. अलार्म के प्रकार"),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekSecondary
                            )

                            Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Card(
                                modifier = Modifier.weight(1f).clickable { waypointTts = !waypointTts }.testTag("toggle_tts"),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(0.5.dp, SleekBorder),
                                colors = CardDefaults.cardColors(containerColor = if (waypointTts) SleekPrimary.copy(alpha = 0.16f) else SleekBackground)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(imageVector = Icons.Default.VolumeUp, contentDescription = null, tint = if (waypointTts) SleekPrimary else SleekMutedText, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("TTS", fontSize = 10.sp, color = if (waypointTts) SleekPrimary else SleekMutedText, fontWeight = FontWeight.Bold)
                                }
                            }

                            Card(
                                modifier = Modifier.weight(1f).clickable { waypointVibration = !waypointVibration }.testTag("toggle_vibe"),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(0.5.dp, SleekBorder),
                                colors = CardDefaults.cardColors(containerColor = if (waypointVibration) SleekPrimary.copy(alpha = 0.16f) else SleekBackground)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(imageVector = Icons.Default.Vibration, contentDescription = null, tint = if (waypointVibration) SleekPrimary else SleekMutedText, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("VIBE", fontSize = 10.sp, color = if (waypointVibration) SleekPrimary else SleekMutedText, fontWeight = FontWeight.Bold)
                                }
                            }

                            Card(
                                modifier = Modifier.weight(1f).clickable { waypointFlash = !waypointFlash }.testTag("toggle_flash"),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(0.5.dp, SleekBorder),
                                colors = CardDefaults.cardColors(containerColor = if (waypointFlash) SleekPrimary.copy(alpha = 0.16f) else SleekBackground)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(imageVector = Icons.Default.FlashlightOn, contentDescription = null, tint = if (waypointFlash) SleekPrimary else SleekMutedText, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("FLASH", fontSize = 10.sp, color = if (waypointFlash) SleekPrimary else SleekMutedText, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        }
                        
                        } // End Scrollable Column

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showAddDialog = false; editingAlarmId = null },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, SleekBorder),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = SleekActiveText)
                            ) {
                                Text(text = t("Cancel", "रद्द करें"), fontSize = 12.sp)
                            }

                            Button(
                                onClick = {
                                    val latDouble = waypointLat.toDoubleOrNull()
                                    val lngDouble = waypointLng.toDoubleOrNull()
                                    val labelText = waypointLabel.trim()
                                    val startLabelText = startLabel.trim()
                                    val startLatDouble = startLat.toDoubleOrNull()
                                    val startLngDouble = startLng.toDoubleOrNull()

                                    val isIdentical = latDouble != null && lngDouble != null &&
                                        startLatDouble != null && startLngDouble != null &&
                                        (Math.abs(latDouble - startLatDouble) < 0.0001 && Math.abs(lngDouble - startLngDouble) < 0.0001)

                                    val valid = labelText.isNotEmpty() &&
                                        latDouble != null && lngDouble != null &&
                                        latDouble in -90.0..90.0 && lngDouble in -180.0..180.0

                                    if (isIdentical) {
                                        android.widget.Toast.makeText(
                                            context,
                                            t(
                                                "Starting point (FROM) and target destination (TO) cannot be physical copies or identical coordinates.",
                                                "प्रस्थान (FROM) और गंतव्य स्थान (TO) एक ही या एकसमान नहीं हो सकते।"
                                            ),
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    } else if (valid) {
                                        if (editingAlarmId != null) {
                                            val alarm = TravelAlarm(
                                                id = editingAlarmId!!,
                                                label = labelText,
                                                category = selectedCategory,
                                                latitude = latDouble!!,
                                                longitude = lngDouble!!,
                                                radiusKm = waypointRadius
                                                    .coerceIn(TRAVEL_RADIUS_MIN, TRAVEL_RADIUS_MAX)
                                                    .toDouble(),
                                                active = editingActive,
                                                ttsEnabled = waypointTts,
                                                flashEnabled = waypointFlash,
                                                vibrationEnabled = waypointVibration,
                                                startLabel = startLabelText,
                                                startLatitude = startLatDouble,
                                                startLongitude = startLngDouble
                                            )
                                            viewModel.updateTravelAlarm(alarm)
                                        } else {
                                            val alarm = TravelAlarm(
                                                label = labelText,
                                                category = selectedCategory,
                                                latitude = latDouble!!,
                                                longitude = lngDouble!!,
                                                radiusKm = waypointRadius
                                                    .coerceIn(TRAVEL_RADIUS_MIN, TRAVEL_RADIUS_MAX)
                                                    .toDouble(),
                                                active = true,
                                                ttsEnabled = waypointTts,
                                                flashEnabled = waypointFlash,
                                                vibrationEnabled = waypointVibration,
                                                startLabel = startLabelText,
                                                startLatitude = startLatDouble,
                                                startLongitude = startLngDouble
                                            )
                                            viewModel.insertTravelAlarm(alarm)
                                        }
                                        showAddDialog = false
                                        editingAlarmId = null
                                    } else {
                                        android.widget.Toast.makeText(
                                            context,
                                            t(
                                                "Enter a name and valid coordinates (lat -90..90, lng -180..180).",
                                                "नाम और सही निर्देशांक भरें (अक्षांश -90..90, देशांतर -180..180)।"
                                            ),
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                modifier = Modifier.weight(1f).testTag("save_destination_btn"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
                            ) {
                                Text(text = t("Save Alarm", "अलार्म सेव करें"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
}

fun getEstimatedTimeBeforeArrivalText(radiusKm: Double, speedKmh: Double, isHindi: Boolean): String {
    val t = { en: String, hi: String -> if (isHindi) hi else en }
    if (speedKmh <= 5.0) {
        val staticMins = (radiusKm / 35.0) * 60.0 // At average city traffic speeds (35 km/h)
        return t(
            String.format(Locale.US, "Triggers approx. %.1f mins before arrival (est. at typical city speed of 35 km/h)", staticMins),
            String.format(Locale.US, "आगमन से लगभग %.1f मिनट पहले बजेगा (35 किमी/घंटा की सामान्य गति के अनुमान पर)", staticMins)
        )
    }
    val mins = (radiusKm / speedKmh) * 60.0
    return t(
        String.format(Locale.US, "Triggers approx. %.1f mins before arrival (at current speed of %.1f km/h)", mins, speedKmh),
        String.format(Locale.US, "आगमन से लगभग %.1f मिनट पहले बजेगा (आपकी वर्तमान लाइव गति %.1f किमी/घंटा पर)", mins, speedKmh)
    )
}

@Composable
fun TravelAlarmCard(
    alarm: TravelAlarm,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onUpdateRadius: (Double) -> Unit,
    speedKmh: Double = 0.0,
    currentLocation: android.location.Location? = null,
    isHindi: Boolean,
    isTrackingActive: Boolean,
    t: (String, String) -> String
) {
    Card(
        modifier = Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SleekCardBg.copy(alpha = 0.85f)),
        border = BorderStroke(0.5.dp, SleekBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val catIcon = when (alarm.category) {
                    "STATION" -> Icons.Default.Train
                    "BUS_STOP" -> Icons.Default.DirectionsBus
                    "HOME" -> Icons.Default.Home
                    "OFFICE" -> Icons.Default.Work
                    else -> Icons.Default.Place
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(SleekBackground, CircleShape)
                        .border(BorderStroke(0.5.dp, SleekBorder), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = catIcon, contentDescription = null, tint = SleekPrimary, modifier = Modifier.size(18.dp))
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(text = alarm.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SleekActiveText)
                    Text(
                        text = "Lat: ${String.format(Locale.US, "%.3f", alarm.latitude)} | Lng: ${String.format(Locale.US, "%.3f", alarm.longitude)}",
                        fontSize = 10.sp,
                        color = SleekMutedText
                    )
                }

                Switch(
                    checked = alarm.active,
                    onCheckedChange = { onToggleActive() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = SleekPrimary,
                        uncheckedThumbColor = SleekMutedText,
                        uncheckedTrackColor = SleekBorder
                    ),
                    modifier = Modifier.scale(0.8f)
                )

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(onClick = onEdit, modifier = Modifier.size(24.dp), enabled = !isTrackingActive) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Waypoint",
                        tint = if (isTrackingActive) SleekMutedText else SleekPrimary.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp), enabled = !isTrackingActive) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Waypoint",
                        tint = if (isTrackingActive) SleekMutedText else Color(0xFFDC2626).copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = t("Trigger alarm before destination:", "कितनी दूरी पहले अलार्म बजे:"),
                    fontSize = 11.sp,
                    color = SleekMutedText
                )
                Text(
                    text = "${String.format(Locale.US, "%.1f", alarm.radiusKm)} km before",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = SleekSecondary
                )
            }

            Slider(
                value = alarm.radiusKm.toFloat().coerceIn(TRAVEL_RADIUS_MIN, TRAVEL_RADIUS_MAX),
                onValueChange = { onUpdateRadius(it.toDouble()) },
                valueRange = TRAVEL_RADIUS_RANGE,
                steps = TRAVEL_RADIUS_STEPS,
                colors = SliderDefaults.colors(
                    thumbColor = SleekSecondary,
                    activeTrackColor = SleekSecondary,
                    inactiveTrackColor = SleekBorder
                ),
                modifier = Modifier.height(20.dp)
            )

            // Dynamic timing prediction text
            val estText = getEstimatedTimeBeforeArrivalText(alarm.radiusKm, speedKmh, isHindi)
            Text(
                text = "⏱️ $estText",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = SleekPrimary,
                modifier = Modifier.padding(bottom = 2.dp)
            )

            // Live distance from the user's CURRENT location to THIS waypoint. Updates on every
            // GPS fix while the journey tracker is running, so the user always sees how far each
            // saved destination still is (and whether they're already inside its trigger radius).
            val liveDistanceKm: Double? = remember(currentLocation, alarm.latitude, alarm.longitude) {
                currentLocation?.let { loc ->
                    val res = FloatArray(1)
                    android.location.Location.distanceBetween(
                        loc.latitude, loc.longitude, alarm.latitude, alarm.longitude, res
                    )
                    res[0] / 1000.0
                }
            }
            if (isTrackingActive && alarm.active && liveDistanceKm != null) {
                val arrived = liveDistanceKm <= alarm.radiusKm
                val accentColor = if (arrived) Color(0xFF10B981) else SleekPrimary
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.12f))
                        .padding(vertical = 6.dp, horizontal = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.MyLocation,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (arrived) {
                                    t("Inside alert zone — arriving", "अलर्ट ज़ोन के अंदर — पहुँच रहे हैं")
                                } else {
                                    t("Live distance remaining", "बची हुई लाइव दूरी")
                                },
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor
                            )
                        }
                        Text(
                            text = if (liveDistanceKm < 1.0) {
                                String.format(Locale.US, "%.0f m", liveDistanceKm * 1000.0)
                            } else {
                                String.format(Locale.US, "%.2f km", liveDistanceKm)
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            color = accentColor
                        )
                    }
                }
            }

            Row(

                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (alarm.ttsEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeMute,
                        contentDescription = null,
                        tint = if (alarm.active) SleekPrimary else SleekMutedText,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = t("Voice Alert (TTS)", "वॉयस अलर्ट"),
                        fontSize = 9.sp,
                        color = SleekMutedText,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Vibration,
                        contentDescription = null,
                        tint = if (alarm.active) SleekPrimary else SleekMutedText,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = t("Vibrate Alert", "कंपن अलर्ट"),
                        fontSize = 9.sp,
                        color = SleekMutedText,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (alarm.flashEnabled) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                        contentDescription = null,
                        tint = if (alarm.active && alarm.flashEnabled) SleekPrimary else SleekMutedText,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = t("Flash Alert", "फ़्लैश अलर्ट"),
                        fontSize = 9.sp,
                        color = SleekMutedText,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

fun Modifier.scale(scale: Float): Modifier = this.then(Modifier.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.placeWithLayer(0, 0) {
            scaleX = scale
            scaleY = scale
        }
    }
})