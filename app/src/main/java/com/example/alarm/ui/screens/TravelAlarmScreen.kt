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
    val nearestAlarm by viewModel.travelNearestAlarm.collectAsStateWithLifecycle()
    val distanceToNearest by viewModel.travelDistanceToNearest.collectAsStateWithLifecycle()
    val trackingStatusMsg by viewModel.travelStatusMsg.collectAsStateWithLifecycle()
    val currentSpeed by viewModel.travelCurrentSpeed.collectAsStateWithLifecycle()

    // Local Compose State
    var showAddDialog by remember { mutableStateOf(false) }
    val isHindi = viewModel.currentLanguage.collectAsStateWithLifecycle().value == "hi"

    // Waypoint properties
    var waypointLabel by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("STATION") }
    var waypointLat by remember { mutableStateOf("") }
    var waypointLng by remember { mutableStateOf("") }
    var waypointRadius by remember { mutableFloatStateOf(2.0f) }
    var waypointTts by remember { mutableStateOf(true) }
    var waypointVibration by remember { mutableStateOf(true) }
    var waypointFlash by remember { mutableStateOf(false) }

    // Remote lookup parameters
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<CityInfo>>(emptyList()) }
    var isSearchingNominatim by remember { mutableStateOf(false) }

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

    fun t(en: String, hi: String): String = if (isHindi) hi else en

    fun runAddressLookup(query: String) {
        if (query.trim().length < 3) return
        isSearchingNominatim = true
        coroutineScope.launch {
            try {
                kotlin.concurrent.thread {
                    val locator = com.example.alarm.location.LocationHelper(context)
                    val r = locator.searchCity(query)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        searchResults = r
                        isSearchingNominatim = false
                    }
                }
            } catch (e: Exception) {
                isSearchingNominatim = false
            }
        }
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
                        text = t("INTELLIGENT ARRIVAL WATCHDOG", "सफ़र (यात्रा) अलार्म"),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            color = SleekPrimary,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = t(
                            "Track transit in background! Ring loud alarms, shake and announce your station using Voice TTS so you never miss your stop while sleeping.",
                            "बैकग्राउंड में यात्रा ट्रैक करें! भारी अलार्म बजाएं, फोन हिलाएं और टीटीएस वॉयस द्वारा स्टेशन की घोषणा करें ताकि सोते समय आपका स्टॉप कभी न छूटे।"
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
                                    text = if (isTracking) t("ACTIVE WATCHDOG RUNNING", "लाइव सुरक्षा चालू है") else t("WATCHDOG INACTIVE", "सुरक्षा निष्क्रिय है"),
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

                        if (isTracking && distanceToNearest > 0) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = t("Distance remaining", "शेष दूरी"), fontSize = 11.sp, color = SleekMutedText)
                                    Text(
                                        text = String.format(Locale.US, "%.2f km", distanceToNearest),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekPrimary
                                    )
                                }

                                val targetRadius = nearestAlarm?.radiusKm ?: 2.0
                                val totalSpan = maxOf(targetRadius * 3, distanceToNearest)
                                val progress = (1.0f - (distanceToNearest / totalSpan).toFloat()).coerceIn(0f, 1f)

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
                                    Text(text = t("Target Alarm Radius:", "अलार्म ट्रिगर दायरा:"), fontSize = 10.sp, color = SleekMutedText)
                                    Text(
                                        text = "${String.format(Locale.US, "%.1f", targetRadius)} km",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekSecondary
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = if (isTracking) t("Waiting for coordinates...", "स्थान मिलने का इंतज़ार...") else t("Start Tracking to safeguard your route.", "मार्ग सुरक्षित करने के लिए ट्रैकिंग चालू करें।"),
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
                                if (isTracking) {
                                    viewModel.stopTravelTracking()
                                } else if (travelAlarms.isEmpty()) {
                                    // Nothing to track yet — guide the user to add a waypoint first
                                    // instead of starting an empty (and pointless) sentry.
                                    android.widget.Toast.makeText(
                                        context,
                                        t(
                                            "Add a travel alarm first, then start the sentry.",
                                            "पहले एक सफ़र अलार्म जोड़ें, फिर सुरक्षा चालू करें।"
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
                                text = if (isTracking) t("STOP ARRIVAL SENTRY", "सुरक्षा ट्रैकिंग बंद करें") else t("START SEAMLESS SENTRY", "मार्ग सुरक्षा चालू करें"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold
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
                        text = t("PRESETS & TRANSIT ALARMS", "निर्धारित सफ़र अलार्म्स"),
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
                                    "Tap the '+' icon to save your Home, Office, Bus Stop, or Railway Station. Sentry alarms will go off before arrival!",
                                    "घर, ऑफिस, बस स्टॉप या रेलवे स्टेशन जोड़ने के लिए '+' दबाएं। आपके स्टॉप पर पहुंचने से पहले सचेतक बज उठेगा!"
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
                        onToggleActive = { viewModel.toggleTravelAlarmActive(alarm) },
                        onDelete = { viewModel.deleteTravelAlarm(alarm) },
                        onUpdateRadius = { radius -> viewModel.updateTravelAlarm(alarm.copy(radiusKm = radius)) },
                        isHindi = isHindi,
                        t = ::t
                    )
                }
            }
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
                        modifier = Modifier
                            .padding(18.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = t("Create Travel Alarm", "नया सफ़र अलार्म जोड़ें"),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekActiveText
                            )
                            IconButton(onClick = { showAddDialog = false }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = SleekMutedText,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Text(
                            text = t("1. Quick Finder (Online Station Lookup)", "1. ऑनलाइन स्टेशन/स्टॉप खोजें"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekSecondary
                        )

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
                                value = searchQuery,
                                onValueChange = { q ->
                                    searchQuery = q
                                    runAddressLookup(q)
                                },
                                modifier = Modifier.weight(1f),
                                textStyle = TextStyle(color = SleekActiveText, fontSize = 13.sp),
                                singleLine = true,
                                cursorBrush = SolidColor(SleekPrimary),
                                decorationBox = { inner ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = t("Type station name... (e.g. New Delhi Station)", "स्टेशन या शहर का नाम लिखें..."),
                                            color = SleekMutedText,
                                            fontSize = 12.sp
                                        )
                                    }
                                    inner()
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

                        if (searchResults.isNotEmpty() && searchQuery.isNotEmpty()) {
                            Box(modifier = Modifier.heightIn(max = 130.dp)) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    searchResults.forEach { city ->
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    waypointLabel = city.name
                                                    waypointLat = city.latitude.toString()
                                                    waypointLng = city.longitude.toString()
                                                    searchResults = emptyList()
                                                    searchQuery = ""
                                                }
                                                .border(BorderStroke(0.5.dp, SleekBorder), RoundedCornerShape(10.dp)),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = CardDefaults.cardColors(containerColor = SleekBackground)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.LocationCity,
                                                    contentDescription = null,
                                                    tint = SleekSecondary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(
                                                        text = "${city.name}, ${city.country}",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 11.sp,
                                                        color = SleekActiveText
                                                    )
                                                    Text(
                                                        text = "Lat: ${String.format(Locale.US, "%.4f", city.latitude)} | Lng: ${String.format(Locale.US, "%.4f", city.longitude)}",
                                                        fontSize = 9.sp,
                                                        color = SleekMutedText
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SleekBorder.copy(alpha = 0.5f)))

                        Text(
                            text = t("2. Waypoint Details (Edit Details)", "2. लोकेशन जानकारी (विवरण बदलें)"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekSecondary
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .background(SleekBackground, RoundedCornerShape(12.dp))
                                .border(BorderStroke(0.5.dp, SleekBorder), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.Default.Label, contentDescription = null, tint = SleekMutedText, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            BasicTextField(
                                value = waypointLabel,
                                onValueChange = { waypointLabel = it },
                                modifier = Modifier.weight(1f).testTag("waypoint_label_input"),
                                textStyle = TextStyle(color = SleekActiveText, fontSize = 13.sp),
                                singleLine = true,
                                cursorBrush = SolidColor(SleekPrimary),
                                decorationBox = { inner ->
                                    if (waypointLabel.isEmpty()) {
                                        Text(text = t("Give name (e.g. My Home, Station)", "स्टॉप का नाम (जैसे: मेरा घर, दिल्ली जंक्शन)"), color = SleekMutedText, fontSize = 12.sp)
                                    }
                                    inner()
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .background(SleekBackground, RoundedCornerShape(12.dp))
                                    .border(BorderStroke(0.5.dp, SleekBorder), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "LAT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SleekSecondary)
                                Spacer(modifier = Modifier.width(6.dp))
                                BasicTextField(
                                    value = waypointLat,
                                    onValueChange = { waypointLat = it },
                                    modifier = Modifier.weight(1f).testTag("latitude_input"),
                                    textStyle = TextStyle(color = SleekActiveText, fontSize = 13.sp),
                                    singleLine = true,
                                    cursorBrush = SolidColor(SleekPrimary),
                                    decorationBox = { inner ->
                                        if (waypointLat.isEmpty()) {
                                            Text(text = "Ex: 28.61", color = SleekMutedText, fontSize = 12.sp)
                                        }
                                        inner()
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .background(SleekBackground, RoundedCornerShape(12.dp))
                                    .border(BorderStroke(0.5.dp, SleekBorder), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "LNG", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SleekSecondary)
                                Spacer(modifier = Modifier.width(6.dp))
                                BasicTextField(
                                    value = waypointLng,
                                    onValueChange = { waypointLng = it },
                                    modifier = Modifier.weight(1f).testTag("longitude_input"),
                                    textStyle = TextStyle(color = SleekActiveText, fontSize = 13.sp),
                                    singleLine = true,
                                    cursorBrush = SolidColor(SleekPrimary),
                                    decorationBox = { inner ->
                                        if (waypointLng.isEmpty()) {
                                            Text(text = "Ex: 77.20", color = SleekMutedText, fontSize = 12.sp)
                                        }
                                        inner()
                                    }
                                )
                            }
                        }

                        Button(
                            onClick = {
                                val defaultLoc = viewModel.latitude.value
                                val defaultLng = viewModel.longitude.value
                                waypointLat = defaultLoc.toString()
                                waypointLng = defaultLng.toString()
                                if (waypointLabel.isEmpty()) {
                                    waypointLabel = viewModel.locationName.value.ifEmpty { "My Location" }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SleekBackground),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .border(BorderStroke(0.5.dp, SleekBorder), RoundedCornerShape(10.dp)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(imageVector = Icons.Default.MyLocation, contentDescription = null, tint = SleekPrimary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = t("Set to My Current GPS Location", "अपने वर्तमान जीपीएस स्थान का उपयोग करें"),
                                fontSize = 11.sp,
                                color = SleekActiveText,
                                fontWeight = FontWeight.Bold
                            )
                        }

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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = t("4. Proximity Radius (Warn me before)", "4. अलार्म दायरा (मुझसे पहले सचेत करें)"),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekSecondary
                            )
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
                            valueRange = 0.5f..10.0f,
                            steps = 19,
                            colors = SliderDefaults.colors(
                                thumbColor = SleekPrimary,
                                activeTrackColor = SleekPrimary,
                                inactiveTrackColor = SleekBorder
                            ),
                            modifier = Modifier.height(24.dp)
                        )

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

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showAddDialog = false },
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

                                    val valid = labelText.isNotEmpty() &&
                                        latDouble != null && lngDouble != null &&
                                        latDouble in -90.0..90.0 && lngDouble in -180.0..180.0

                                    if (valid) {
                                        val alarm = TravelAlarm(
                                            label = labelText,
                                            category = selectedCategory,
                                            latitude = latDouble!!,
                                            longitude = lngDouble!!,
                                            radiusKm = waypointRadius.toDouble(),
                                            active = true,
                                            ttsEnabled = waypointTts,
                                            flashEnabled = waypointFlash,
                                            vibrationEnabled = waypointVibration
                                        )
                                        viewModel.insertTravelAlarm(alarm)
                                        showAddDialog = false
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

@Composable
fun TravelAlarmCard(
    alarm: TravelAlarm,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
    onUpdateRadius: (Double) -> Unit,
    isHindi: Boolean,
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

                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Waypoint",
                        tint = Color(0xFFDC2626).copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = t("Alert trigger distance:", "सचेत करने का फासला:"), fontSize = 11.sp, color = SleekMutedText)
                Text(
                    text = "${String.format(Locale.US, "%.1f", alarm.radiusKm)} km",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = SleekSecondary
                )
            }

            Slider(
                value = alarm.radiusKm.toFloat(),
                onValueChange = { onUpdateRadius(it.toDouble()) },
                valueRange = 0.5f..10.0f,
                steps = 19,
                colors = SliderDefaults.colors(
                    thumbColor = SleekSecondary,
                    activeTrackColor = SleekSecondary,
                    inactiveTrackColor = SleekBorder
                ),
                modifier = Modifier.height(20.dp)
            )

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
