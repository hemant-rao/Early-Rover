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
import com.example.alarm.data.Alarm
import com.example.alarm.opengl.Celestial3DView
import com.example.alarm.ui.weather.WeatherBackground
import com.example.alarm.viewmodel.AlarmViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: AlarmViewModel,
    onNavigateToAddAlarm: (type: String) -> Unit,
    onNavigateToEditAlarm: (id: Int) -> Unit,
    onNavigateToLocation: () -> Unit,
    onNavigateToSettings: () -> Unit,
    requestedTab: Int? = null,
    onTabConsumed: () -> Unit = {}
) {
    val alarms by viewModel.allAlarms.collectAsStateWithLifecycle()
    val nextAlarm by viewModel.nextUpcomingAlarm.collectAsStateWithLifecycle()
    val nextAlarmFormatted by viewModel.nextUpcomingAlarmTimeFormatted.collectAsStateWithLifecycle()
    
    val sunrise by viewModel.sunriseTime.collectAsStateWithLifecycle()
    val sunset by viewModel.sunsetTime.collectAsStateWithLifecycle()
    val locationName by viewModel.locationName.collectAsStateWithLifecycle()
    val lat by viewModel.latitude.collectAsStateWithLifecycle()
    val lng by viewModel.longitude.collectAsStateWithLifecycle()
    val darkTheme by viewModel.darkThemeEnabled.collectAsStateWithLifecycle()
    val weather by viewModel.weather.collectAsStateWithLifecycle()
    val savedCities by viewModel.savedCities.collectAsStateWithLifecycle()
    val headerScope = rememberCoroutineScope()

    val formattedDate = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM dd", Locale.getDefault()))
    }

    var showQuickAddMenu by remember { mutableStateOf(false) }
    var showLocationSearchDialog by remember { mutableStateOf(false) }
    var selectedPlanet by remember { mutableStateOf<String?>(null) }
    var activeTab by remember { mutableIntStateOf(0) }

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

    Box(modifier = Modifier.fillMaxSize()) {
      // Location-aware animated weather sky behind everything
      WeatherBackground(weather = weather, modifier = Modifier.fillMaxSize())
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
                                contentDescription = "Home",
                                tint = if (activeTab == 0) SleekPrimary else SleekMutedText.copy(alpha = 0.7f),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "HOME",
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
        Box(modifier = Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding())) {
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
                                        text = "DIG DEEP DELETE",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Black,
                                            color = SleekPrimary,
                                            letterSpacing = 1.2.sp
                                        )
                                    )
                                    
                                    // Visual indicator representing live sync
                                    Row(
                                        modifier = Modifier
                                            .background(SleekCardBg, RoundedCornerShape(12.dp))
                                            .border(BorderStroke(0.5.dp, SleekBorder), RoundedCornerShape(12.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(Color(0xFF10B981), CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        val w = weather
                                        Text(
                                            text = if (w != null && !w.temperatureC.isNaN())
                                                "${w.temperatureC.toInt()}°C • ${w.description.uppercase()}"
                                            else viewModel.translate("SOLARIS LIVE"),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SleekMutedText
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(4.dp))
                                
                                // Interactive location header: active name (swipeable) + saved-location
                                // dots + an add (+) button. Swiping or tapping a dot switches location.
                                if (savedCities.isEmpty()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.LocationOn,
                                            contentDescription = null,
                                            tint = SleekSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = locationName.ifEmpty { "Reykjavík, IS" },
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = SleekActiveText
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { showLocationSearchDialog = true }
                                        )
                                        IconButton(
                                            onClick = { showLocationSearchDialog = true },
                                            modifier = Modifier.size(28.dp).testTag("add_location_header_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AddCircle,
                                                contentDescription = "Add New Location",
                                                tint = SleekPrimary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                } else {
                                    val activeCityIndex = remember(savedCities, locationName) {
                                        savedCities.indexOfFirst { c ->
                                            locationName.equals(c.name, true) ||
                                                locationName.startsWith(c.name, true) ||
                                                c.name.startsWith(locationName, true)
                                        }.coerceAtLeast(0)
                                    }
                                    val pagerState = rememberPagerState(initialPage = activeCityIndex) { savedCities.size }

                                    // External location change (e.g. via search dialog) -> move the pager.
                                    LaunchedEffect(activeCityIndex, savedCities.size) {
                                        if (activeCityIndex in savedCities.indices &&
                                            pagerState.currentPage != activeCityIndex
                                        ) {
                                            pagerState.animateScrollToPage(activeCityIndex)
                                        }
                                    }
                                    // User swipe/dot tap settles on a page -> make that location active.
                                    // drop(1) skips the initial emission so we never override an
                                    // unsaved/auto-detected active location on first composition.
                                    LaunchedEffect(pagerState, savedCities) {
                                        snapshotFlow { pagerState.currentPage }
                                            .drop(1)
                                            .collect { page ->
                                                val c = savedCities.getOrNull(page) ?: return@collect
                                                // Same symmetric match as activeCityIndex, so the two
                                                // effects agree and never fight.
                                                val matches = locationName.equals(c.name, true) ||
                                                    locationName.startsWith(c.name, true) ||
                                                    c.name.startsWith(locationName, true)
                                                if (!matches) viewModel.setManualCitySelection(c)
                                            }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.LocationOn,
                                            contentDescription = null,
                                            tint = SleekSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        HorizontalPager(
                                            state = pagerState,
                                            modifier = Modifier.weight(1f)
                                        ) { page ->
                                            Text(
                                                text = savedCities[page].name,
                                                style = MaterialTheme.typography.titleLarge.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = SleekActiveText
                                                ),
                                                maxLines = 1,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { showLocationSearchDialog = true }
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        // Saved-location dots (one per city), tappable to switch.
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            savedCities.indices.forEach { i ->
                                                val selected = i == pagerState.currentPage
                                                Box(
                                                    modifier = Modifier
                                                        .size(if (selected) 8.dp else 6.dp)
                                                        .background(
                                                            if (selected) SleekPrimary
                                                            else SleekMutedText.copy(alpha = 0.4f),
                                                            CircleShape
                                                        )
                                                        .clickable {
                                                            headerScope.launch { pagerState.animateScrollToPage(i) }
                                                        }
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        IconButton(
                                            onClick = { showLocationSearchDialog = true },
                                            modifier = Modifier.size(28.dp).testTag("add_location_header_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AddCircle,
                                                contentDescription = "Add New Location",
                                                tint = SleekPrimary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
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

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SunCaption(
                                        label = viewModel.translate("SUNRISE"),
                                        value = String.format("%02d:%02d %s", if (sunrise.hour % 12 == 0) 12 else sunrise.hour % 12, sunrise.minute, if (sunrise.hour >= 12) "PM" else "AM"),
                                        icon = Icons.Default.WbSunny,
                                        tint = SleekSolarAccent
                                    )
                                    Text(
                                        text = "3D CELESTIAL ORBITS",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekMutedText.copy(alpha = 0.7f),
                                        letterSpacing = 1.2.sp
                                    )
                                    SunCaption(
                                        label = viewModel.translate("SUNSET"),
                                        value = String.format("%02d:%02d %s", if (sunset.hour % 12 == 0) 12 else sunset.hour % 12, sunset.minute, if (sunset.hour >= 12) "PM" else "AM"),
                                        icon = Icons.Default.WbTwilight,
                                        tint = SleekSecondary,
                                        alignEnd = true
                                    )
                                }
                            }
                        }

                        // 2. SLEEK WEATHER SECTION (Moved lower under the Solar scene!)
                        item {
                            SleekWeatherSection(
                                viewModel = viewModel,
                                onChangeLocationClick = { showLocationSearchDialog = true }
                            )
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(20.dp)),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = SleekCardBg)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(modifier = Modifier.size(8.dp).background(SleekSolarAccent, CircleShape))
                                            Text("SOLAR", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = SleekMutedText, letterSpacing = 1.sp)
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        val activeOffsetStr = if (nextAlarm != null && nextAlarm!!.alarmType != "CUSTOM") {
                                            val min = nextAlarm!!.offsetMinutes
                                            if (min == 0) "At Peak Event" else if (min < 0) "${-min}m Before" else "${min}m After"
                                        } else {
                                            "Dynamic Time"
                                        }
                                        val activeOffsetSub = if (nextAlarm != null && nextAlarm!!.alarmType != "CUSTOM") {
                                            if (nextAlarm!!.alarmType == "SUNRISE") "Before Sunrise" else "Before Sunset"
                                        } else {
                                            "Standard triggers"
                                        }
                                        Text(text = activeOffsetStr, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SleekActiveText)
                                        Text(text = activeOffsetSub, fontSize = 10.sp, color = SleekMutedText, modifier = Modifier.padding(top = 2.dp))
                                    }
                                }

                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(20.dp)),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = SleekCardBg)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(modifier = Modifier.size(8.dp).background(SleekSecondary, CircleShape))
                                            Text("VIBE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = SleekMutedText, letterSpacing = 1.sp)
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        val vibeStr = if (nextAlarm?.vibrationEnabled == true) "Vibration ON" else "Vibration OFF"
                                        val vibeSub = if (nextAlarm?.snoozeEnabled == true) "Snooze ${nextAlarm?.snoozeMinutes}m Enabled" else "Instant Wakeup"
                                        Text(text = vibeStr, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SleekActiveText)
                                        Text(text = vibeSub, fontSize = 10.sp, color = SleekMutedText, modifier = Modifier.padding(top = 2.dp))
                                    }
                                }
                            }
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
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "LIVE WEATHER RADAR HUB",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Black,
                                        color = SleekPrimary,
                                        letterSpacing = 1.2.sp
                                    )
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Explore real-time weather, solar dynamics, and astronomical predictions.",
                                    fontSize = 12.sp,
                                    color = SleekMutedText
                                )
                            }
                        }

                        item {
                            LocationCarouselSection(
                                viewModel = viewModel,
                                onAddLocationClick = { showLocationSearchDialog = true }
                            )
                        }

                        item {
                            SleekWeatherSection(
                                viewModel = viewModel,
                                onChangeLocationClick = { showLocationSearchDialog = true }
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

                val trackerDesc = when (alarm.alarmType) {
                    "SUNRISE" -> "Sunrise " + if (alarm.offsetMinutes == 0) "exactly" else if (alarm.offsetMinutes < 0) "${-alarm.offsetMinutes}m before" else "${alarm.offsetMinutes}m after"
                    "SUNSET" -> "Sunset " + if (alarm.offsetMinutes == 0) "exactly" else if (alarm.offsetMinutes < 0) "${-alarm.offsetMinutes}m before" else "${alarm.offsetMinutes}m after"
                    else -> alarm.title.ifEmpty { "Manual Alarm" }
                }

                Text(
                    text = trackerDesc,
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
    viewModel: AlarmViewModel,
    onUseMyLocationClick: () -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching = searchQuery.isNotEmpty()

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val resultsListState = rememberLazyListState()

    // Size the results dropdown to the device: ~42% of the screen height, so it grows on
    // tall phones and stays compact on short ones, and scrolls once it hits that cap.
    val configuration = LocalConfiguration.current
    val maxResultsHeight = (configuration.screenHeightDp * 0.42f).dp

    // Once the user starts scrolling the results, drop the keyboard so the full list is visible.
    LaunchedEffect(resultsListState.isScrollInProgress) {
        if (resultsListState.isScrollInProgress) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }

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
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(searchResults) { city ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.addSavedCity(city)
                                            onDismiss()
                                        }
                                        .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(10.dp)),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = SleekBackground)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.LocationCity,
                                            contentDescription = null,
                                            tint = SleekSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "${city.name}, ${city.country}",
                                                fontWeight = FontWeight.Bold,
                                                color = SleekActiveText,
                                                fontSize = 12.sp
                                            )
                                            Text(
                                                text = "Lat: ${city.latitude} | Lng: ${city.longitude}",
                                                fontSize = 9.sp,
                                                color = SleekMutedText
                                            )
                                        }
                                    }
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
    onChangeLocationClick: () -> Unit
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
                } else {
                    IconButton(
                        onClick = onChangeLocationClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search Location",
                            tint = SleekSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
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
                
                // Tabs switcher using native standard buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SleekBackground.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val tabs = listOf("TODAY", "FORECAST (10D)", "HISTORY (10D)")
                    tabs.forEachIndexed { idx, label ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selectedTab == idx) SleekPrimary else Color.Transparent)
                                .clickable { 
                                    selectedTab = idx
                                    expandedDayIso = null // collapse day detail
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = translateWeatherText(label, lang),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == idx) Color.White else SleekMutedText
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedTab) {
                    0 -> { // TODAY Tab
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.Bottom) {
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
                                        modifier = Modifier
                                            .size(36.dp)
                                            .padding(bottom = 6.dp)
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
                                        val (icon, color) = getWeatherIconAndColor(hour.condition, true)
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
                    }

                    1, 2 -> { // FORECAST (1) or HISTORY (2) Tab
                        val isForecast = selectedTab == 1
                        val todayDate = LocalDate.now()
                        
                        val daysList = if (isForecast) {
                            weather.dailyList.filter {
                                try {
                                    val d = LocalDate.parse(it.dateIso)
                                    !d.isBefore(todayDate)
                                } catch(e: Exception) {
                                    true
                                }
                            }.take(10)
                        } else {
                            weather.dailyList.filter {
                                try {
                                    val d = LocalDate.parse(it.dateIso)
                                    d.isBefore(todayDate)
                                } catch(e: Exception) {
                                    false
                                }
                            }.takeLast(10)
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            daysList.forEach { day ->
                                val isExpanded = expandedDayIso == day.dateIso
                                
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(SleekBackground.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                        .border(BorderStroke(0.5.dp, SleekBorder), RoundedCornerShape(16.dp))
                                        .clickable { 
                                            expandedDayIso = if (isExpanded) null else day.dateIso
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
                                        Divider(color = SleekBorder.copy(alpha = 0.3f))
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
                                                    val (hIcon, hCol) = getWeatherIconAndColor(hour.condition, true)
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
            else Icons.Default.Star to Color(0xFF90CAF9)
        }
        com.example.alarm.weather.WeatherCondition.FEW_CLOUDS -> Icons.Default.Cloud to Color(0xFFE2E8F0)
        com.example.alarm.weather.WeatherCondition.CLOUDS -> Icons.Default.Cloud to Color(0xFF94A3B8)
        com.example.alarm.weather.WeatherCondition.FOG -> Icons.Default.Menu to Color(0xFFA5F3FC)
        com.example.alarm.weather.WeatherCondition.RAIN -> Icons.Default.InvertColors to Color(0xFF60A5FA)
        com.example.alarm.weather.WeatherCondition.SNOW -> Icons.Default.Star to Color(0xFFE2E8F0)
        com.example.alarm.weather.WeatherCondition.THUNDER -> Icons.Default.WbSunny to Color(0xFFFBBF24)
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
                val isActive = currentLocationName.equals(city.name, true) ||
                    currentLocationName.startsWith(city.name, true) ||
                    city.name.startsWith(currentLocationName, true)
                
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
                    
                    if (!isActive && savedCities.size > 1) {
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



