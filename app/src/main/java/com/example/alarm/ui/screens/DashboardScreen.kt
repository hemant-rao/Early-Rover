package com.example.alarm.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import com.example.alarm.data.Alarm
import com.example.alarm.opengl.Celestial3DView
import com.example.alarm.ui.weather.WeatherBackground
import com.example.alarm.weather.AirQualityInfo
import com.example.alarm.viewmodel.AlarmViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: AlarmViewModel,
    onNavigateToAddAlarm: (type: String) -> Unit,
    onNavigateToEditAlarm: (id: Int) -> Unit,
    onNavigateToLocation: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToManageCities: () -> Unit,
    requestedTab: Int? = null,
    onTabConsumed: () -> Unit = {}
) {
    val alarms by viewModel.alarmsForCurrentLocation.collectAsStateWithLifecycle()
    val nextAlarm by viewModel.nextUpcomingAlarm.collectAsStateWithLifecycle()
    val nextAlarmFormatted by viewModel.nextUpcomingAlarmTimeFormatted.collectAsStateWithLifecycle()
    
    val sunrise by viewModel.sunriseTime.collectAsStateWithLifecycle()
    val sunset by viewModel.sunsetTime.collectAsStateWithLifecycle()
    val locationName by viewModel.locationName.collectAsStateWithLifecycle()
    val lat by viewModel.latitude.collectAsStateWithLifecycle()
    val lng by viewModel.longitude.collectAsStateWithLifecycle()
    val darkTheme by viewModel.darkThemeEnabled.collectAsStateWithLifecycle()
    val weather by viewModel.weather.collectAsStateWithLifecycle()
    val airQuality by viewModel.airQuality.collectAsStateWithLifecycle()
    val savedCities by viewModel.savedCities.collectAsStateWithLifecycle()
    val tzOffset by viewModel.timezoneOffset.collectAsStateWithLifecycle()
    val isDetectingLocation by viewModel.isDetectingLocation.collectAsStateWithLifecycle()
    val headerScope = rememberCoroutineScope()

    val formattedDate = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM dd", Locale.getDefault()))
    }

    var showQuickAddMenu by remember { mutableStateOf(false) }
    var showLocationSearchDialog by remember { mutableStateOf(false) }
    var selectedPlanet by remember { mutableStateOf<String?>(null) }
    var activeTab by remember { mutableIntStateOf(0) }

    val activeCityIndex = remember(savedCities, locationName) {
        val exactMatch = savedCities.indexOfFirst { it.name.equals(locationName, true) }
        if (exactMatch != -1) exactMatch else {
            savedCities.indexOfFirst { c ->
                locationName.startsWith(c.name, true) ||
                    c.name.startsWith(locationName, true)
            }.coerceAtLeast(0)
        }
    }
    val pagerState = rememberPagerState(initialPage = activeCityIndex) { savedCities.size }
    var totalDragAmount by remember { mutableStateOf(0f) }

    val swipeModifier = if (savedCities.size > 1) {
        Modifier.pointerInput(savedCities, pagerState) {
            detectHorizontalDragGestures(
                onDragStart = { _ ->
                    totalDragAmount = 0f
                },
                onDragEnd = {
                    val threshold = 120f
                    if (totalDragAmount < -threshold) {
                        val nextIdx = (pagerState.currentPage + 1) % savedCities.size
                        headerScope.launch {
                            pagerState.animateScrollToPage(nextIdx)
                        }
                    } else if (totalDragAmount > threshold) {
                        val prevIdx = (pagerState.currentPage - 1 + savedCities.size) % savedCities.size
                        headerScope.launch {
                            pagerState.animateScrollToPage(prevIdx)
                        }
                    }
                },
                onDragCancel = {},
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    totalDragAmount += dragAmount
                }
            )
        }
    } else Modifier

    // Honor a tab requested from a tapped notification, then clear it so manual
    // tab switches aren't overridden on later recompositions.
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            activeTab = requestedTab
            onTabConsumed()
        }
    }

    // The quick-add menu belongs to the dashboard tab only; switching tabs (e.g. via the
    // bottom nav) must dismiss it so it never lingers over another screen.
    LaunchedEffect(activeTab) {
        if (activeTab != 0) showQuickAddMenu = false
    }

    val dashboardPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocation = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineLocation || coarseLocation) {
            viewModel.triggerAutoLocationDetect()
            showLocationSearchDialog = false
        }
    }

    // On open: if the user is following GPS (hasn't pinned a manual city) and location
    // permission is already granted, detect the REAL current location so the weather
    // reflects where the user actually is — not the default fallback city. Otherwise just
    // refresh weather for the saved/manual location.
    val dashContext = LocalContext.current
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            dashContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                dashContext, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        if (granted && viewModel.isAutoLocationEnabled()) {
            viewModel.triggerAutoLocationDetect()
        } else {
            viewModel.refreshWeather()
        }
    }

    val isDayAtLocation = remember(weather, tzOffset) {
        val rawIsDay = weather?.isDay
        if (rawIsDay != null) {
            rawIsDay
        } else {
            try {
                val utcInstant = java.time.Instant.now()
                val offsetSeconds = (tzOffset * 3600).toLong()
                val zoneOffset = java.time.ZoneOffset.ofTotalSeconds(offsetSeconds.toInt())
                val localizedDateTime = java.time.OffsetDateTime.ofInstant(utcInstant, zoneOffset)
                localizedDateTime.hour in 6..17
            } catch (e: Exception) {
                java.time.LocalTime.now().hour in 6..17
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
      // Location-aware animated weather sky behind everything
      WeatherBackground(weather = weather, isDayOverride = isDayAtLocation, modifier = Modifier.fillMaxSize())
      // Readability scrim so cards and text stay legible over the sky
      Box(
          modifier = Modifier
              .fillMaxSize()
              .background(SleekBackground.copy(alpha = 0.42f))
      )

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_screen"),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // Elegant Sleek Bottom Navigation Bar with 4 equal elements
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(SleekCardBg, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("sleek_bottom_nav_bar")
            ) {
                // Layout row for tabs with equal weights
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tabModifier = Modifier
                        .weight(1f)
                        .height(72.dp)
 
                    // 1. DASH Tab
                    Box(
                        modifier = tabModifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { activeTab = 0 }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "Dash",
                                tint = if (activeTab == 0) SleekPrimary else SleekMutedText.copy(alpha = 0.7f),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "DASH",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeTab == 0) SleekPrimary else SleekMutedText.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // 2. TRAVEL Tab
                    Box(
                        modifier = tabModifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { activeTab = 3 }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Explore,
                                contentDescription = "Travel",
                                tint = if (activeTab == 3) SleekPrimary else SleekMutedText.copy(alpha = 0.7f),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "TRAVEL",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeTab == 3) SleekPrimary else SleekMutedText.copy(alpha = 0.7f)
                            )
                        }
                    }
 
                    // 3. WEATHER Tab
                    Box(
                        modifier = tabModifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { activeTab = 1 }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.WbSunny,
                                contentDescription = "Weather",
                                tint = if (activeTab == 1) SleekPrimary else SleekMutedText.copy(alpha = 0.7f),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "WEATHER",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeTab == 1) SleekPrimary else SleekMutedText.copy(alpha = 0.7f)
                            )
                        }
                    }
 
                    // 4. SETTINGS Tab
                    Box(
                        modifier = tabModifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { activeTab = 2 }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = if (activeTab == 2) SleekPrimary else SleekMutedText.copy(alpha = 0.7f),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "SETTINGS",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeTab == 2) SleekPrimary else SleekMutedText.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (activeTab == 0) {
                // Elevated FAB button sitting clearly on top, avoiding any intersection
                FloatingActionButton(
                    onClick = { showQuickAddMenu = !showQuickAddMenu },
                    containerColor = SleekPrimary,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .shadow(12.dp, CircleShape)
                        .testTag("quick_add_fab")
                ) {
                    Icon(
                        imageVector = if (showQuickAddMenu) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = "Add Alarm",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().then(swipeModifier).padding(bottom = innerPadding.calculateBottomPadding())) {
            when (activeTab) {
                0 -> { // DASH
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding()),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 16.dp,
                            bottom = 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // Title header line + logo block representing new brand
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "SOLAR ALARM",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Black,
                                            color = SleekPrimary,
                                            letterSpacing = 1.2.sp
                                        )
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(4.dp))
                                
                                // Interactive location header: active name (swipeable) + saved-location
                                // dots + an add (+) button. Swiping or tapping a dot switches location.
                                LocationHeader(
                                    isDetecting = isDetectingLocation,
                                    savedCities = savedCities,
                                    locationName = locationName,
                                    activeCityIndex = activeCityIndex,
                                    pagerState = pagerState,
                                    headerScope = headerScope,
                                    viewModel = viewModel,
                                    onAddLocationClick = { showLocationSearchDialog = true },
                                    onManageCitiesClick = onNavigateToManageCities
                                )
                            }
                        }

                        // 1. INTERACTIVE NATIVE 3D SOLAR SYSTEM (OpenGL ES) at the TOP!
                        // Full-bleed (edge to edge) — no box/border so the planets float over the sky.
                        item {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Box(
                                    modifier = Modifier
                                        .fullBleed(16.dp)
                                        .height(380.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize(0.95f)
                                            .background(
                                                Brush.radialGradient(
                                                    colors = listOf(SleekPrimary.copy(alpha = 0.12f), Color.Transparent),
                                                    radius = 620f
                                                )
                                            )
                                    )

                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = !showLocationSearchDialog,
                                        enter = fadeIn(),
                                        exit = fadeOut()
                                    ) {
                                        Celestial3DView(
                                            modifier = Modifier.fillMaxSize(),
                                            sunriseTime = sunrise,
                                            sunsetTime = sunset,
                                            activeAlarms = alarms.filter { it.active }.map { Pair(it.hour, it.minute) },
                                            isDark = darkTheme,
                                            animateOrbits = true,
                                            onPlanetSelected = { selectedPlanet = it }
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val sunriseAlarm = alarms.find { it.alarmType == "SUNRISE" }
                                    val sunsetAlarm = alarms.find { it.alarmType == "SUNSET" }

                                    SunriseSunsetAlarmCard(
                                        modifier = Modifier.weight(1f),
                                        title = viewModel.translate("Sunrise"),
                                        time = String.format("%02d:%02d %s", if (sunrise.hour % 12 == 0) 12 else sunrise.hour % 12, sunrise.minute, if (sunrise.hour >= 12) "PM" else "AM"),
                                        icon = Icons.Default.WbSunny,
                                        tint = SleekSolarAccent,
                                        isActive = sunriseAlarm?.active ?: false,
                                        currentOffset = sunriseAlarm?.offsetMinutes ?: 0,
                                        onAlarmToggle = { active ->
                                            if (sunriseAlarm == null) {
                                                if (active) {
                                                    viewModel.createDefaultAlarm("SUNRISE")
                                                }
                                            } else {
                                                if (sunriseAlarm.active != active) {
                                                    viewModel.toggleAlarmActive(sunriseAlarm)
                                                }
                                            }
                                        },
                                        onOffsetSelected = { offset ->
                                            sunriseAlarm?.let {
                                                viewModel.updateAlarmOffset(it, offset)
                                            }
                                        },
                                        onCardClick = { sunriseAlarm?.let { onNavigateToEditAlarm(it.id) } ?: onNavigateToAddAlarm("SUNRISE") }
                                    )
                                    SunriseSunsetAlarmCard(
                                        modifier = Modifier.weight(1f),
                                        title = viewModel.translate("Sunset"),
                                        time = String.format("%02d:%02d %s", if (sunset.hour % 12 == 0) 12 else sunset.hour % 12, sunset.minute, if (sunset.hour >= 12) "PM" else "AM"),
                                        icon = Icons.Default.WbTwilight,
                                        tint = SleekSecondary,
                                        isActive = sunsetAlarm?.active ?: false,
                                        currentOffset = sunsetAlarm?.offsetMinutes ?: 0,
                                        onAlarmToggle = { active ->
                                            if (sunsetAlarm == null) {
                                                if (active) {
                                                    viewModel.createDefaultAlarm("SUNSET")
                                                }
                                            } else {
                                                if (sunsetAlarm.active != active) {
                                                    viewModel.toggleAlarmActive(sunsetAlarm)
                                                }
                                            }
                                        },
                                        onOffsetSelected = { offset ->
                                            sunsetAlarm?.let {
                                                viewModel.updateAlarmOffset(it, offset)
                                            }
                                        },
                                        onCardClick = { sunsetAlarm?.let { onNavigateToEditAlarm(it.id) } ?: onNavigateToAddAlarm("SUNSET") }
                                    )
                                }
                            }
                        }

                        // 2. SLEEK WEATHER SECTION (Moved lower under the Solar scene!)
                        item {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                SleekWeatherSection(
                                    viewModel = viewModel,
                                    onChangeLocationClick = { showLocationSearchDialog = true },
                                    showExtendedData = true
                                )
                            }
                        }

                        // 2b. AIR QUALITY (AQI) CARD — shown when air-quality data is available
                        airQuality?.let { aqi ->
                            item {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    AirQualityCard(aqi = aqi, viewModel = viewModel)
                                }
                            }
                        }

                        // ADD ALARM BUTTON
                        item {
                            Button(
                                onClick = { onNavigateToAddAlarm("CUSTOM") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                            ) {
                                Text(viewModel.translate("Add Standard Alarm"))
                            }
                        }

                        // 3. NEXT ALARM GRADIENT HERO CARD
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (nextAlarm != null) {
                                            onNavigateToEditAlarm(nextAlarm!!.id)
                                        }
                                    },
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(SleekPrimary, SleekSecondary)
                                            )
                                        )
                                        .padding(24.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(100.dp)
                                            .align(Alignment.TopEnd)
                                            .offset(x = 24.dp, y = (-24).dp)
                                            .background(Color.White.copy(alpha = 0.08f), CircleShape)
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = viewModel.translate("Next Alarm").uppercase(),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White.copy(alpha = 0.85f),
                                                letterSpacing = 1.5.sp
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = if (nextAlarmFormatted == "None") viewModel.translate("None") else nextAlarmFormatted,
                                                fontSize = 32.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            
                                            val repeatDesc = if (nextAlarm != null) {
                                                val repeats = nextAlarm!!.getRepeatDaysList()
                                                val typeLabel = when (nextAlarm!!.alarmType) {
                                                    "SUNRISE" -> viewModel.translate("Sunrise Alarm")
                                                    "SUNSET" -> viewModel.translate("Sunset Alarm")
                                                    else -> viewModel.translate("Standard Clock Alarm")
                                                }
                                                if (repeats.isEmpty()) "$typeLabel • Once" else "$typeLabel • ${getDailyRepeaterString(repeats)}"
                                            } else {
                                                viewModel.translate("No Alarms Scheduled")
                                            }
                                            
                                            Text(
                                                text = repeatDesc,
                                                fontSize = 12.sp,
                                                color = Color.White.copy(alpha = 0.9f)
                                            )
                                        }

                                        if (nextAlarm != null) {
                                            Switch(
                                                checked = nextAlarm!!.active,
                                                onCheckedChange = { viewModel.toggleAlarmActive(nextAlarm!!) },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = SleekPrimary,
                                                    checkedTrackColor = Color.White,
                                                    uncheckedThumbColor = Color.LightGray,
                                                    uncheckedTrackColor = Color.White.copy(alpha = 0.3f)
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 4. SOLAR DETAILS / BOTTOM ACCENTS CARD (TWO BLOCKS SIDE-BY-SIDE)
                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                        }

                        // 5. ALARM SCHEDULE TIMELINE LIST HEADER
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = viewModel.translate("Schedule Alarm"),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = SleekActiveText
                                    )
                                )
                                Text(
                                    text = "${alarms.size} Total",
                                    fontSize = 12.sp,
                                    color = SleekMutedText,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        if (alarms.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(20.dp)),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = SleekCardBg)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.NotificationsOff,
                                            contentDescription = null,
                                            tint = SleekMutedText.copy(alpha = 0.4f),
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = viewModel.translate("No Alarms Scheduled"),
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SleekActiveText
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = viewModel.translate("Tap the '+' floating button in the center below to configure custom or sunrise/sunset-aligned alarms."),
                                            fontSize = 11.sp,
                                            color = SleekMutedText,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            items(alarms, key = { it.id }) { alarm ->
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    SleekAlarmItemRow(
                                        alarm = alarm,
                                        onRowClick = { onNavigateToEditAlarm(alarm.id) },
                                        onToggleActive = { viewModel.toggleAlarmActive(alarm) },
                                        onDeleteClick = { viewModel.deleteAlarm(alarm) }
                                    )
                                }
                            }
                        }
                    }
                }

                1 -> { // WEATHER
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding()),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 16.dp,
                            bottom = 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        item {
                            LocationHeader(
                                isDetecting = isDetectingLocation,
                                savedCities = savedCities,
                                locationName = locationName,
                                activeCityIndex = activeCityIndex,
                                pagerState = pagerState,
                                headerScope = headerScope,
                                viewModel = viewModel,
                                onAddLocationClick = { showLocationSearchDialog = true },
                                onManageCitiesClick = onNavigateToManageCities
                            )
                        }

                        item {
                            SleekWeatherSection(
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxWidth(),
                                onChangeLocationClick = { showLocationSearchDialog = true },
                                showExtendedData = true
                            )
                        }
                    }
                }

                2 -> { // SETTINGS
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding())
                    ) {
                        SettingsScreen(viewModel = viewModel, onNavigateBack = { activeTab = 0 })
                    }
                }

                3 -> { // TRAVEL
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding())
                    ) {
                        TravelAlarmScreen(viewModel = viewModel)
                    }
                }
            }

            // Quick Add menu — hosted in a Popup (a separate window) so it always renders
            // ABOVE the OpenGL solar-system surface, which is drawn on top of the main
            // window and would otherwise bleed through and cover the menu.
            if (showQuickAddMenu) {
                val density = LocalDensity.current
                Popup(
                    alignment = Alignment.BottomCenter,
                    offset = with(density) { IntOffset(0, -(96.dp).roundToPx()) },
                    onDismissRequest = { showQuickAddMenu = false },
                    properties = PopupProperties(focusable = true)
                ) {
                    AnimatedVisibility(
                        visible = showQuickAddMenu,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .padding(horizontal = 24.dp)
                                .shadow(16.dp, RoundedCornerShape(20.dp)),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = SleekCardBg),
                            border = BorderStroke(1.dp, SleekBorder)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "SCHEDULE NEW ALARM",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Black,
                                        color = SleekPrimary,
                                        letterSpacing = 1.2.sp
                                    ),
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )

                                SleekQuickAddItem(
                                    text = "Solar Sunrise Awake",
                                    icon = Icons.Default.WbSunny,
                                    tint = SleekSolarAccent,
                                    onClick = {
                                        showQuickAddMenu = false
                                        onNavigateToAddAlarm("SUNRISE")
                                    }
                                )
                                Divider(color = SleekBorder.copy(alpha = 0.5f))
                                SleekQuickAddItem(
                                    text = "Solar Sunset Reflection",
                                    icon = Icons.Default.WbTwilight,
                                    tint = SleekSecondary,
                                    onClick = {
                                        showQuickAddMenu = false
                                        onNavigateToAddAlarm("SUNSET")
                                    }
                                )
                                Divider(color = SleekBorder.copy(alpha = 0.5f))
                                SleekQuickAddItem(
                                    text = "Standard Manual Clock",
                                    icon = Icons.Default.AccessTime,
                                    tint = Color.LightGray,
                                    onClick = {
                                        showQuickAddMenu = false
                                        onNavigateToAddAlarm("CUSTOM")
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLocationSearchDialog) {
        LocationSearchDialog(
            initialQuery = locationName,
            viewModel = viewModel,
            onUseMyLocationClick = {
                dashboardPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            },
            onDismiss = { showLocationSearchDialog = false }
        )
    }

    selectedPlanet?.let { body ->
        PlanetInfoDialog(planetName = body, onDismiss = { selectedPlanet = null })
    }
    }
}

@Composable
fun SleekAlarmItemRow(
    alarm: Alarm,
    onRowClick: () -> Unit,
    onToggleActive: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("alarm_item_${alarm.id}")
            .clickable { onRowClick() }
            .border(
                BorderStroke(
                    width = 0.5.dp,
                    color = if (alarm.active) SleekPrimary.copy(alpha = 0.5f) else SleekBorder
                ),
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (alarm.active) SleekCardBg else SleekCardBg.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon Category Indicator
            val (icon, tint, backgroundCircle) = when (alarm.alarmType) {
                "SUNRISE" -> {
                    val color = if (alarm.active) SleekSolarAccent else SleekMutedText.copy(alpha = 0.6f)
                    Triple(Icons.Default.WbSunny, color, color.copy(alpha = 0.15f))
                }
                "SUNSET" -> {
                    val color = if (alarm.active) SleekSecondary else SleekMutedText.copy(alpha = 0.6f)
                    Triple(Icons.Default.WbTwilight, color, color.copy(alpha = 0.15f))
                }
                else -> {
                    val color = if (alarm.active) SleekPrimary else SleekMutedText.copy(alpha = 0.6f)
                    Triple(Icons.Default.AccessTime, color, color.copy(alpha = 0.15f))
                }
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(backgroundCircle, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = "Trigger Type Selector",
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Time and Labels
            Column(modifier = Modifier.weight(1f)) {
                val hour12 = if (alarm.hour % 12 == 0) 12 else alarm.hour % 12
                val ampm = if (alarm.hour >= 12) "PM" else "AM"
                
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format("%02d:%02d", hour12, alarm.minute),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (alarm.active) SleekActiveText else SleekActiveText.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = ampm,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (alarm.active) SleekActiveText else SleekActiveText.copy(alpha = 0.4f),
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                val titleDefault = if (alarm.title.isNotEmpty()) alarm.title else when (alarm.alarmType) {
                    "SUNRISE" -> "Sunrise Tracker"
                    "SUNSET" -> "Sunset Tracker"
                    else -> "Manual Alarm"
                }

                val trackerDesc = when (alarm.alarmType) {
                    "SUNRISE", "SUNSET" -> {
                        val exactPart = if (alarm.ringAtExactAlso) "exactly + " else ""
                        val offsetPart = if (alarm.offsetMinutes == 0) (if (!alarm.ringAtExactAlso) "exactly based" else "(no offset)") else if (alarm.offsetMinutes < 0) "${-alarm.offsetMinutes}m before" else "${alarm.offsetMinutes}m after"
                        val desc = "$exactPart$offsetPart".trimEnd(' ', '+')
                        desc
                    }
                    else -> if (alarm.title.isEmpty()) "Standard Clock Alarm" else ""
                }

                Text(
                    text = titleDefault + if (trackerDesc.isNotEmpty()) " - $trackerDesc" else "",
                    fontSize = 12.sp,
                    color = if (alarm.active) SleekMutedText else SleekMutedText.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Normal
                )
                
                if (alarm.isRepeating()) {
                    Text(
                        text = getDailyRepeaterString(alarm.getRepeatDaysList()),
                        fontSize = 11.sp,
                        color = if (alarm.active) SleekSecondary else SleekMutedText.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                // Surface the per-alarm snooze so users see it's configurable.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Snooze,
                        contentDescription = null,
                        tint = if (alarm.active) SleekMutedText else SleekMutedText.copy(alpha = 0.5f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (alarm.snoozeEnabled) "Snooze ${alarm.snoozeMinutes}m" else "Snooze off",
                        fontSize = 10.sp,
                        color = if (alarm.active) SleekMutedText else SleekMutedText.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Interactive controls
            Switch(
                checked = alarm.active,
                onCheckedChange = { onToggleActive() },
                modifier = Modifier.testTag("alarm_active_switch_${alarm.id}"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = SleekPrimary,
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = SleekBorder
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier.testTag("alarm_delete_${alarm.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Permanent Schedule",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun SunCaption(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    alignEnd: Boolean = false
) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = tint,
                letterSpacing = 1.sp
            )
        }
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = SleekActiveText
        )
    }
}

@Composable
fun SleekQuickAddItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = SleekActiveText
        )
    }
}

/**
 * Lets a child ignore the parent's horizontal [padding] on each side, so it spans the full
 * available width (edge to edge) even inside a LazyColumn that has horizontal contentPadding.
 */
private fun Modifier.fullBleed(padding: Dp): Modifier = this.layout { measurable, constraints ->
    val extra = (padding * 2).roundToPx()
    val widened = constraints.copy(
        minWidth = constraints.maxWidth + extra,
        maxWidth = constraints.maxWidth + extra
    )
    val placeable = measurable.measure(widened)
    layout(placeable.width, placeable.height) {
        placeable.place(-padding.roundToPx(), 0)
    }
}

private fun getDailyRepeaterString(days: List<Int>): String {
    if (days.size == 7) return "Every day"
    if (days.size == 5 && !days.contains(6) && !days.contains(7)) return "Weekdays"
    if (days.size == 2 && days.contains(6) && days.contains(7)) return "Weekends"
    
    val dayChars = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    return days.sorted().mapNotNull { dayChars.getOrNull(it - 1) }.joinToString(", ")
}

@Composable
fun LocationSearchDialog(
    initialQuery: String,
    viewModel: AlarmViewModel,
    onUseMyLocationClick: () -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf(initialQuery) }
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching = searchQuery.isNotEmpty()
    
    LaunchedEffect(Unit) {
        if (initialQuery.isNotEmpty()) {
            // Optional: call searchLocationQuery when dialog opens so matches show up.
            kotlinx.coroutines.delay(100) // slight delay to allow keyboard controller to mount if needed, or just let user search.
            viewModel.searchLocationQuery(initialQuery)
        }
    }

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val resultsListState = rememberLazyListState()

    // Size the results dropdown to the device: ~42% of the screen height, so it grows on
    // tall phones and stays compact on short ones, and scrolls once it hits that cap.
    val configuration = LocalConfiguration.current
    val maxResultsHeight = (configuration.screenHeightDp * 0.42f).dp

    // Once the user starts scrolling the results, drop the keyboard so the full list is visible.
    // val isDragged by resultsListState.interactionSource.collectIsDraggedAsState()
    // LaunchedEffect(isDragged) {
    //     if (isDragged) {
    //         keyboardController?.hide()
    //         focusManager.clearFocus()
    //     }
    // }

    // Modern focus requester to open keyboard automatically
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        try {
            focusRequester.requestFocus()
        } catch (t: Throwable) {
            // Safe fallback if FocusRequester is not yet attached to the layout node
        }
    }

    // Capture device Back Button presses to close the overlay cleanly
    androidx.activity.compose.BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                onClick = onDismiss,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 56.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
                .clickable(
                    enabled = true,
                    onClick = {}, // Prevent clicks inside the dialog content from propagating and closing it
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                )
                .border(BorderStroke(1.dp, SleekBorder), shape = RoundedCornerShape(20.dp))
                .shadow(16.dp, RoundedCornerShape(20.dp)),
            color = SleekCardBg,
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = translateWeatherText("Select Location", viewModel.currentLanguage.value),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = SleekActiveText
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = SleekMutedText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Compact Search Input bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(SleekBackground, RoundedCornerShape(12.dp))
                        .border(BorderStroke(1.dp, SleekBorder), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = SleekMutedText,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { q ->
                            searchQuery = q
                            viewModel.searchLocationQuery(q)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onFocusChanged { state ->
                                // When focus leaves the field, collapse the keyboard so the
                                // results list has room and can be scrolled freely.
                                if (!state.isFocused) keyboardController?.hide()
                            },
                        textStyle = TextStyle(color = SleekActiveText, fontSize = 13.sp),
                        singleLine = true,
                        cursorBrush = SolidColor(SleekPrimary),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = viewModel.translate("Type city name..."),
                                        color = SleekMutedText,
                                        fontSize = 13.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = ""; viewModel.searchLocationQuery("") },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = SleekMutedText,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Sleek divider before location action icon
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .width(1.dp)
                            .height(20.dp)
                            .background(SleekBorder)
                    )

                    // Compact inline GPS icon button
                    IconButton(
                        onClick = onUseMyLocationClick,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GpsFixed,
                            contentDescription = "My Location",
                            tint = SleekPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Live Search list or helper label
                if (isSearching) {
                    Box(modifier = Modifier.heightIn(max = maxResultsHeight)) {
                        LazyColumn(
                            state = resultsListState,
                            verticalArrangement = Arrangement.Top
                        ) {
                            items(searchResults) { city ->
                                Column {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.addSavedCity(city)
                                                onDismiss()
                                            }
                                            .padding(vertical = 14.dp, horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
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
                                                color = SleekActiveText,
                                                fontSize = 14.sp
                                            )
                                            Text(
                                                text = "Lat: ${String.format(Locale.US,"%.2f", city.latitude)} | Lng: ${String.format(Locale.US,"%.2f", city.longitude)}",
                                                fontSize = 11.sp,
                                                color = SleekMutedText
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = SleekBorder.copy(alpha = 0.5f), modifier = Modifier.padding(start = 48.dp, end = 12.dp))
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = viewModel.translate("Search city (e.g. London, Reykjavik, Tokyo...)"),
                        fontSize = 11.sp,
                        color = SleekMutedText,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SleekWeatherSection(
    viewModel: AlarmViewModel,
    modifier: Modifier = Modifier,
    onChangeLocationClick: () -> Unit,
    showExtendedData: Boolean = false
) {
    val lang = viewModel.currentLanguage.collectAsStateWithLifecycle().value
    val detailedWeather by viewModel.detailedWeather.collectAsStateWithLifecycle()
    val isWeatherLoading by viewModel.isWeatherLoading.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf(0) } // 0 = TODAY, 1 = FUTURE FORECAST, 2 = HISTORIC HISTORY
    var expandedDayIso by remember { mutableStateOf<String?>(null) } // for item accordion expansion

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SleekCardBg.copy(alpha = 0.85f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Section Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = SleekPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = translateWeatherText("WEATHER STATION", lang),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = SleekPrimary,
                        letterSpacing = 1.2.sp
                    )
                }

                if (isWeatherLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = SleekPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (detailedWeather == null) {
                // Loading / default state helper
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = translateWeatherText("Fetching weather data...", lang),
                        fontSize = 13.sp,
                        color = SleekMutedText,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.refreshWeather() },
                        colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
                    ) {
                        Text(translateWeatherText("Refresh Network", lang), color = Color.White, fontSize = 11.sp)
                    }
                }
            } else {
                val weather = detailedWeather!!
                
                Spacer(modifier = Modifier.height(16.dp))

                // TODAY Section
                val tzOffset = viewModel.timezoneOffset.value
                        val localTimeStr = try {
                            val utcInstant = java.time.Instant.now()
                            val offsetSeconds = (tzOffset * 3600).toLong()
                            val zoneOffset = java.time.ZoneOffset.ofTotalSeconds(offsetSeconds.toInt())
                            val localizedDateTime = java.time.OffsetDateTime.ofInstant(utcInstant, zoneOffset)
                            localizedDateTime.format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a"))
                        } catch (e: java.lang.Exception) {
                            ""
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                if (localTimeStr.isNotEmpty()) {
                                    Text(
                                        text = localTimeStr,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekPrimary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${weather.current.temperatureC.toInt()}°",
                                        fontSize = 44.sp,
                                        fontWeight = FontWeight.Black,
                                        color = SleekActiveText
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val (icon, color) = getWeatherIconAndColor(weather.current.condition, weather.current.isDay)
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = color,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                                Text(
                                    text = translateWeatherText(weather.current.description, lang),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekActiveText
                                )
                            }

                            // 2x2 parameter summaries
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(start = 12.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    WeatherParamLabelValue(
                                        label = translateWeatherText("Humidity", lang),
                                        value = "${weather.relativeHumidity}%",
                                        icon = Icons.Default.Info
                                    )
                                    WeatherParamLabelValue(
                                        label = translateWeatherText("Apparent", lang),
                                        value = "${weather.apparentTemperatureC.toInt()}°C",
                                        icon = Icons.Default.WbSunny
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    WeatherParamLabelValue(
                                        label = translateWeatherText("Precip", lang),
                                        value = "${weather.precipitationMm} mm",
                                        icon = Icons.Default.PlayArrow
                                    )
                                    WeatherParamLabelValue(
                                        label = translateWeatherText("Wind", lang),
                                        value = "${weather.windSpeedKmh.toInt()} km/h",
                                        icon = Icons.Default.Refresh
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Divider(color = SleekBorder.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(12.dp))

                        // Hourly next 24 hours title
                        Text(
                            text = translateWeatherText("Hourly Report (Next 24h)", lang),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekSecondary,
                            letterSpacing = 0.5.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Next 24 hours of hourly details (horizontal scroll)
                        val now = java.time.LocalDateTime.now()
                        val currentHourIsoStr = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                        var startIdx = weather.hourlyList.indexOfFirst { it.timeIso >= currentHourIsoStr }
                        if (startIdx < 0) startIdx = 0
                        val next24HoursList = weather.hourlyList.drop(startIdx).take(24)

                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(next24HoursList) { hour ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = SleekBackground.copy(alpha = 0.5f)),
                                    border = BorderStroke(0.5.dp, SleekBorder),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.width(68.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = formatIsoTimeToHour(hour.timeIso),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SleekMutedText,
                                            textAlign = TextAlign.Center
                                        )
                                        val (icon, color) = getWeatherIconAndColor(hour.condition, isIsoTimeDaytime(hour.timeIso))
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = color,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "${hour.temperatureC.toInt()}°",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Black,
                                            color = SleekActiveText
                                        )
                                        Text(
                                            text = "${hour.humidityPercent}%",
                                            fontSize = 8.sp,
                                            color = SleekSecondary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))

                        HorizontalDivider(color = SleekBorder.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 8.dp))

                        if (showExtendedData) {
                            var isForecastExpanded by remember { mutableStateOf(false) }
                            var isHistoryExpanded by remember { mutableStateOf(false) }
                            
                            // Forecast
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { isForecastExpanded = !isForecastExpanded }.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(translateWeatherText("FORECAST", lang), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SleekSecondary)
                                Icon(if (isForecastExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = SleekSecondary)
                            }
                            if (isForecastExpanded) {
                                WeatherDaysList(weather, weather.dailyList.takeLast(10), expandedDayIso, { expandedDayIso = it }, lang)
                            }
                            
                            // History
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { isHistoryExpanded = !isHistoryExpanded }.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(translateWeatherText("HISTORY", lang), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SleekSecondary)
                                Icon(if (isHistoryExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = SleekSecondary)
                            }
                            if (isHistoryExpanded) {
                                WeatherDaysList(weather, weather.dailyList.take(5), expandedDayIso, { expandedDayIso = it }, lang)
                            }
                        }

                    }
                }
            }
        }

@Composable
fun WeatherDaysList(
    weather: com.example.alarm.weather.DetailedWeatherInfo,
    daysList: List<com.example.alarm.weather.DailyDetail>,
    expandedDayIso: String?,
    onExpandDay: (String?) -> Unit,
    lang: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        daysList.forEach { day ->
            val isExpanded = expandedDayIso == day.dateIso
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SleekBackground.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .border(BorderStroke(0.5.dp, SleekBorder), RoundedCornerShape(16.dp))
                    .clickable { 
                        onExpandDay(if (isExpanded) null else day.dateIso)
                    }
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = formatIsoDateToReadable(day.dateIso),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = SleekActiveText
                        )
                        val descriptionText = when (day.condition) {
                            com.example.alarm.weather.WeatherCondition.CLEAR -> "Clear sky"
                            com.example.alarm.weather.WeatherCondition.FEW_CLOUDS -> "Partly cloudy"
                            com.example.alarm.weather.WeatherCondition.CLOUDS -> "Cloudy"
                            com.example.alarm.weather.WeatherCondition.FOG -> "Foggy"
                            com.example.alarm.weather.WeatherCondition.RAIN -> "Rain"
                            com.example.alarm.weather.WeatherCondition.SNOW -> "Snow"
                            com.example.alarm.weather.WeatherCondition.THUNDER -> "Thunderstorm"
                        }
                        Text(
                            text = translateWeatherText(descriptionText, lang),
                            fontSize = 11.sp,
                            color = SleekMutedText
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val (icon, color) = getWeatherIconAndColor(day.condition, true)
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "${day.maxTempC.toInt()}° / ${day.minTempC.toInt()}°",
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp,
                            color = SleekActiveText
                        )
                    }
                }

                // Display direct hourly details on accordion expansion
                if (isExpanded) {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = SleekBorder.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = translateWeatherText("Hourly Details for Day", lang),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = SleekSecondary,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    val dayHourlyRecords = weather.hourlyList.filter { it.timeIso.startsWith(day.dateIso) }

                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(dayHourlyRecords) { hour ->
                            Column(
                                modifier = Modifier
                                    .background(SleekBackground.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                    .padding(vertical = 6.dp, horizontal = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = formatIsoTimeToHour(hour.timeIso).substringBefore(" "), // e.g. "04:00"
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekMutedText
                                )
                                Text(
                                    text = formatIsoTimeToHour(hour.timeIso).substringAfter(" "), // e.g. "PM"
                                    fontSize = 7.sp,
                                    color = SleekMutedText
                                )
                                val (hIcon, hCol) = getWeatherIconAndColor(hour.condition, isIsoTimeDaytime(hour.timeIso))
                                Icon(
                                    imageVector = hIcon,
                                    contentDescription = null,
                                    tint = hCol,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "${hour.temperatureC.toInt()}°",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekActiveText
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Maps a European AQI value to a qualitative label + accent color. */
private fun europeanAqiQuality(aqi: Int): Pair<String, Color> = when {
    aqi <= 20 -> "Good" to Color(0xFF22C55E)
    aqi <= 40 -> "Fair" to Color(0xFF84CC16)
    aqi <= 60 -> "Moderate" to Color(0xFFFBBF24)
    aqi <= 80 -> "Poor" to Color(0xFFF97316)
    aqi <= 100 -> "Very Poor" to Color(0xFFEF4444)
    else -> "Extremely Poor" to Color(0xFF991B1B)
}

@Composable
fun AirQualityCard(
    aqi: AirQualityInfo,
    viewModel: AlarmViewModel
) {
    val lang = viewModel.currentLanguage.collectAsStateWithLifecycle().value
    // Prefer European AQI; fall back to US AQI. -1 means unavailable for both.
    val useEuropean = aqi.europeanAqi >= 0
    val displayValue = if (useEuropean) aqi.europeanAqi else aqi.usAqi
    val (qualityLabel, accent) = if (useEuropean) {
        europeanAqiQuality(aqi.europeanAqi)
    } else {
        // US AQI has its own bands; surface a neutral accent with no European label.
        "" to SleekSecondary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SleekCardBg.copy(alpha = 0.85f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Air,
                    contentDescription = null,
                    tint = SleekPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = translateWeatherText("AIR QUALITY", lang),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = SleekPrimary,
                    letterSpacing = 1.2.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (displayValue < 0) {
                Text(
                    text = translateWeatherText("Air quality data unavailable", lang),
                    fontSize = 13.sp,
                    color = SleekMutedText,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$displayValue",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Black,
                        color = accent
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (useEuropean) translateWeatherText("European AQI", lang)
                                   else translateWeatherText("US AQI", lang),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekMutedText,
                            letterSpacing = 1.sp
                        )
                        if (qualityLabel.isNotEmpty()) {
                            Text(
                                text = translateWeatherText(qualityLabel, lang),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = accent
                            )
                        }
                    }
                }
            }

            // Particulate matter readings — skip any value that is unavailable (NaN).
            val showPm25 = !aqi.pm25.isNaN()
            val showPm10 = !aqi.pm10.isNaN()
            if (showPm25 || showPm10) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (showPm25) {
                        WeatherParamLabelValue(
                            label = translateWeatherText("PM2.5", lang),
                            value = "${aqi.pm25.toInt()} µg/m³",
                            icon = Icons.Default.Air
                        )
                    }
                    if (showPm10) {
                        WeatherParamLabelValue(
                            label = translateWeatherText("PM10", lang),
                            value = "${aqi.pm10.toInt()} µg/m³",
                            icon = Icons.Default.Air
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WeatherParamLabelValue(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SleekSecondary,
            modifier = Modifier.size(14.dp)
        )
        Column {
            Text(
                text = label,
                fontSize = 8.sp,
                color = SleekMutedText,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = value,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = SleekActiveText
            )
        }
    }
}

@Composable
fun getWeatherIconAndColor(condition: com.example.alarm.weather.WeatherCondition, isDay: Boolean): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> {
    return when (condition) {
        com.example.alarm.weather.WeatherCondition.CLEAR -> {
            if (isDay) Icons.Default.WbSunny to Color(0xFFFFB347)
            else Icons.Default.NightsStay to Color(0xFF90CAF9)
        }
        com.example.alarm.weather.WeatherCondition.FEW_CLOUDS -> {
            if (isDay) Icons.Default.Cloud to Color(0xFFE2E8F0)
            else Icons.Default.NightsStay to Color(0xFF90CAF9)
        }
        com.example.alarm.weather.WeatherCondition.CLOUDS -> Icons.Default.Cloud to Color(0xFF94A3B8)
        com.example.alarm.weather.WeatherCondition.FOG -> Icons.Default.Menu to Color(0xFFA5F3FC)
        com.example.alarm.weather.WeatherCondition.RAIN -> Icons.Default.InvertColors to Color(0xFF60A5FA)
        com.example.alarm.weather.WeatherCondition.SNOW -> {
            if (isDay) Icons.Default.Star to Color(0xFFE2E8F0)
            else Icons.Default.NightsStay to Color(0xFF90CAF9)
        }
        com.example.alarm.weather.WeatherCondition.THUNDER -> Icons.Default.FlashOn to Color(0xFFFBBF24)
    }
}

fun formatIsoDateToReadable(isoDate: String): String {
    return try {
        val date = java.time.LocalDate.parse(isoDate)
        date.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM dd"))
    } catch(e: Exception) {
        isoDate
    }
}

fun formatIsoTimeToHour(isoTime: String): String {
    return try {
        val dt = java.time.LocalDateTime.parse(isoTime)
        dt.format(java.time.format.DateTimeFormatter.ofPattern("hh:00 a"))
    } catch(e: Exception) {
        if (isoTime.contains("T")) isoTime.substringAfter("T") else isoTime
    }
}

fun isIsoTimeDaytime(isoTime: String): Boolean {
    return try {
        val dt = java.time.LocalDateTime.parse(isoTime)
        val hour = dt.hour
        hour in 6..17
    } catch(e: Exception) {
        true
    }
}

fun translateWeatherText(text: String, currentLanguage: String): String {
    if (currentLanguage != "hi") return text
    return when (text) {
        "WEATHER STATION" -> "मौसम केंद्र"
        "TODAY" -> "आज का मौसम"
        "FORECAST (10D)" -> "अगले 10 दिन"
        "HISTORY (10D)" -> "पिछले 10 दिन"
        "Humidity" -> "आर्द्रता (नमी)"
        "Apparent" -> "महसूस तापमान"
        "Precip" -> "बारिश"
        "Wind" -> "हवा"
        "Hourly Report (Next 24h)" -> "अगले 24 घंटे की रिपोर्ट"
        "Fetching weather data..." -> "मौसम की जानकारी प्राप्त की जा रही है..."
        "Refresh Network" -> "मौसम पुनः लोड करें"
        "Hourly Details for Day" -> "दिन के लिए प्रति घंटा विवरण"
        "Close" -> "बंद करें"
        "Select Location" -> "स्थान चुनें"
        "Clear sky" -> "साफ़ आसमान"
        "Clear night" -> "साफ़ रात"
        "Partly cloudy" -> "आंशिक रूप से बादल"
        "Cloudy" -> "घने बादल"
        "Foggy" -> "कोहरा"
        "Rain" -> "बारिश"
        "Snow" -> "बर्फबारी"
        "Thunderstorm" -> "आंधी-तूफ़ान"
        else -> text
    }
}

@Composable
fun LocationCarouselSection(
    viewModel: AlarmViewModel,
    onAddLocationClick: () -> Unit
) {
    val savedCities by viewModel.savedCities.collectAsStateWithLifecycle()
    val currentLocationName by viewModel.locationName.collectAsStateWithLifecycle()
    val weatherInfo by viewModel.weather.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = viewModel.translate("ACTIVE LOCATIONS HUB").uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = SleekMutedText,
                    letterSpacing = 1.2.sp
                )
            )
            
            // Add Location Button (+)
            IconButton(
                onClick = onAddLocationClick,
                modifier = Modifier.size(32.dp).testTag("add_location_hub_button")
            ) {
                Icon(
                    imageVector = Icons.Default.AddCircle,
                    contentDescription = "Add New Location",
                    tint = SleekPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Horizontal Scrollable Row of Pill Buttons
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(savedCities) { city ->
                val isActive = currentLocationName.equals(city.name, true)
                
                val simulatedTemp = when (city.name.lowercase()) {
                    "new york" -> "19°"
                    "mumbai" -> "32°"
                    "london" -> "15°"
                    "reykjavík" -> "8°"
                    "tokyo" -> "21°"
                    "sydney" -> "17°"
                    else -> "22°"
                }
                
                val tempSuffix = if (isActive && weatherInfo != null && !weatherInfo!!.temperatureC.isNaN()) {
                    " (${weatherInfo!!.temperatureC.toInt()}°)"
                } else {
                    " ($simulatedTemp)"
                }

                Row(
                    modifier = Modifier
                        .background(
                            if (isActive) SleekPrimary else SleekCardBg,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .border(
                            BorderStroke(
                                width = if (isActive) 1.5.dp else 0.5.dp,
                                color = if (isActive) SleekPrimary else SleekBorder
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { viewModel.addSavedCity(city) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${city.name}$tempSuffix",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) Color.White else SleekActiveText
                    )
                    
                    if (!isActive) {                
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove City",
                            tint = SleekMutedText,
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { viewModel.deleteSavedCity(city) }
                        )
                    }
                }
            }
        }
    }
}



