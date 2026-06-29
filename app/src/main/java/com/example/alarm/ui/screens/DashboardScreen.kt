package com.example.alarm.ui.screens

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.alarm.data.SunAlarmResolver
import com.example.alarm.opengl.Celestial3DView
import com.example.alarm.opengl.Sun3DProgressionView
import java.time.LocalTime
import com.example.alarm.ui.weather.WeatherBackground
import com.example.alarm.weather.AirQualityInfo
import com.example.alarm.viewmodel.AlarmViewModel
import com.example.ui.theme.*
import com.example.ui.AppLogo
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: AlarmViewModel,
    onNavigateToAddAlarm: (type: String) -> Unit,
    onNavigateToEditAlarm: (id: Int) -> Unit,
    onNavigateToLocation: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToManageCities: () -> Unit,
    onNavigateToPrivacyPolicy: () -> Unit,
    onNavigateToTermsConditions: () -> Unit,
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
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val weather by viewModel.weather.collectAsStateWithLifecycle()
    val airQuality by viewModel.airQuality.collectAsStateWithLifecycle()
    val savedCities by viewModel.savedCities.collectAsStateWithLifecycle()
    val tzOffset by viewModel.timezoneOffset.collectAsStateWithLifecycle()
    val isDetectingLocation by viewModel.isDetectingLocation.collectAsStateWithLifecycle()
    val headerScope = rememberCoroutineScope()

    val formattedDate = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM dd", Locale.getDefault()))
    }

    var showLocationSearchDialog by remember { mutableStateOf(false) }
    var selectedPlanet by rememberSaveable { mutableStateOf<String?>(null) }
    var activeTab by rememberSaveable { mutableIntStateOf(0) }
    // Alarm whose OFF-toggle on a repeating alarm triggered the skip/turn-off dialog.
    var skipDialogAlarm by remember { mutableStateOf<Alarm?>(null) }
    // Alarm whose deletion triggered confirmation dialog.
    var deleteDialogAlarm by remember { mutableStateOf<Alarm?>(null) }
    
    val isTravelTrackingActive by viewModel.isTravelTrackingActive.collectAsStateWithLifecycle()

    val permissionContext = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(permissionContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(permissionContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasLocationPermission = ContextCompat.checkSelfPermission(permissionContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(permissionContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val dashListState = rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }
    val isWeatherEnabled by viewModel.isWeatherEnabled.collectAsStateWithLifecycle()
    val isSolarTrendsEnabled by viewModel.isSolarTrendsEnabled.collectAsStateWithLifecycle()
    val isTravelEnabled by viewModel.isTravelEnabled.collectAsStateWithLifecycle()

    // Shared OFF-toggle handler: repeating alarms prompt skip-vs-turn-off; one-time alarms toggle directly.
    val onAlarmToggle: (Alarm) -> Unit = { alarm ->
        if (alarm.active && alarm.isRepeating()) {
            skipDialogAlarm = alarm
        } else {
            viewModel.toggleAlarmActive(alarm)
        }
    }

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

    // After savedCities/active selection changes (e.g. a brand-new city was just added and
    // made the manual selection), land the pager on the active city so the dashboard shows it.
    LaunchedEffect(activeCityIndex, savedCities.size) {
        if (savedCities.isNotEmpty() &&
            activeCityIndex in 0 until savedCities.size &&
            pagerState.currentPage != activeCityIndex
        ) {
            pagerState.animateScrollToPage(activeCityIndex)
        }
    }

    val swipeModifier = if (savedCities.size > 1) {
        Modifier.pointerInput(savedCities, pagerState) {
            detectHorizontalDragGestures(
                onDragStart = { _ ->
                    totalDragAmount = 0f
                },
                onDragEnd = {
                    val threshold = 120f
                    if (totalDragAmount < -threshold) {
                        // Clamp (don't wrap) so body-swipe matches the pager's own clamping.
                        val nextIdx = (pagerState.currentPage + 1).coerceAtMost(savedCities.lastIndex)
                        if (nextIdx != pagerState.currentPage) {
                            headerScope.launch {
                                pagerState.animateScrollToPage(nextIdx)
                            }
                        }
                    } else if (totalDragAmount > threshold) {
                        val prevIdx = (pagerState.currentPage - 1).coerceAtLeast(0)
                        if (prevIdx != pagerState.currentPage) {
                            headerScope.launch {
                                pagerState.animateScrollToPage(prevIdx)
                            }
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

    // Drive the 3D scene from the SAME effective-theme source as the app chrome.
    // Reading themeMode here makes this recompute when the user changes Light/Dark/Auto;
    // AUTO additionally follows daylight at the active location.
    val darkTheme = remember(themeMode, isDayAtLocation) {
        viewModel.isEffectiveDark(themeMode, isDayAtLocation)
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
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top),
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
                            AppLogo(
                                modifier = Modifier.size(24.dp),
                                tint = if (activeTab == 0) SleekPrimary else SleekMutedText.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = viewModel.translate("DASH"),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeTab == 0) SleekPrimary else SleekMutedText.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // 2. TRAVEL Tab
                    if (isTravelEnabled) {
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
                                    text = viewModel.translate("TRAVEL"),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeTab == 3) SleekPrimary else SleekMutedText.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
 
                    // 3. WEATHER Tab
                    if (isWeatherEnabled) {
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
                                    text = viewModel.translate("WEATHER"),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeTab == 1) SleekPrimary else SleekMutedText.copy(alpha = 0.7f)
                                )
                            }
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
                                text = viewModel.translate("SETTINGS"),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeTab == 2) SleekPrimary else SleekMutedText.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        // Only let the body-swipe drive location switching on the Dash/Weather tabs;
        // Settings and Travel have their own horizontal content and must not be hijacked.
        val activeSwipeModifier = if (activeTab == 0 || activeTab == 1) swipeModifier else Modifier
        Box(modifier = Modifier.fillMaxSize().then(activeSwipeModifier).padding(bottom = innerPadding.calculateBottomPadding())) {
            @OptIn(ExperimentalAnimationApi::class)
            androidx.compose.animation.Crossfade(
                targetState = activeTab,
                animationSpec = tween(500),
                label = "tab_switch"
            ) { targetTab ->
                when (targetTab) {
                0 -> {
                    LazyColumn(
                        state = dashListState,
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        AppLogo(
                                            modifier = Modifier.size(24.dp).padding(end = 6.dp),
                                            tint = SleekPrimary
                                        )
                                        Text(
                                            text = "EARLY ROVER",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Black,
                                                color = SleekPrimary,
                                                letterSpacing = 1.2.sp
                                            )
                                        )
                                    }
                                    // Compact temp + icon + local time widget (top-right, near the city).
                                    CompactWeatherTimeWidget(weather = weather, tzOffset = tzOffset)
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
                                    onManageCitiesClick = onNavigateToManageCities,
                                    hasLocationPermission = hasLocationPermission,
                                    onRequestLocationPermission = {
                                        dashboardPermissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                                Manifest.permission.ACCESS_COARSE_LOCATION
                                            )
                                        )
                                    }
                                )

                                Spacer(modifier = Modifier.height(12.dp))


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

                                    // The search dialog is now a real Dialog window that renders
                                    // above the OpenGL surface, so the 3D scene no longer needs to be
                                    // torn down/recreated when the dialog opens.
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
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val sunriseAlarm = alarms.find { it.alarmType == "SUNRISE" }
                                    val sunsetAlarm = alarms.find { it.alarmType == "SUNSET" }
                                    val today = java.time.LocalDate.now()
                                    val sunriseTimeLocalized = com.example.alarm.data.SunAlarmResolver.fireTimeOn(
                                        sunriseAlarm ?: com.example.alarm.data.Alarm(title = "Sunrise", alarmType = "SUNRISE", hour = 6, minute = 0, latitude = lat, longitude = lng, timezoneOffset = tzOffset, locationName = locationName),
                                        today
                                    )
                                    val sunsetTimeLocalized = com.example.alarm.data.SunAlarmResolver.fireTimeOn(
                                        sunsetAlarm ?: com.example.alarm.data.Alarm(title = "Sunset", alarmType = "SUNSET", hour = 18, minute = 0, latitude = lat, longitude = lng, timezoneOffset = tzOffset, locationName = locationName),
                                        today
                                    )

                                    SunriseSunsetAlarmCard(
                                        modifier = Modifier.weight(1f),
                                        title = viewModel.translate("Sunrise"),
                                        time = String.format("%02d:%02d %s", if (sunriseTimeLocalized.hour % 12 == 0) 12 else sunriseTimeLocalized.hour % 12, sunriseTimeLocalized.minute, if (sunriseTimeLocalized.hour >= 12) "PM" else "AM"),
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
                                        time = String.format("%02d:%02d %s", if (sunsetTimeLocalized.hour % 12 == 0) 12 else sunsetTimeLocalized.hour % 12, sunsetTimeLocalized.minute, if (sunsetTimeLocalized.hour >= 12) "PM" else "AM"),
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

                        // Weather/AQI now live exclusively on the WEATHER tab; the DASH header
                        // shows a compact temp + time widget instead (see CompactWeatherTimeWidget).

                        // TRAVEL on home: active-journey tracker (if any) then an Add Travel Alarm CTA.
                        if (isTravelEnabled) {
                            if (isTravelTrackingActive) {
                                item {
                                    JourneyActiveCard(
                                        viewModel = viewModel,
                                        onClick = { activeTab = 3 }
                                    )
                                }
                            }
                            item {
                                AddTravelAlarmCard(
                                    viewModel = viewModel,
                                    onClick = { activeTab = 3 }
                                )
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
                                                if (repeats.isEmpty()) "$typeLabel • ${viewModel.translate("Once")}" else "$typeLabel • ${getDailyRepeaterString(repeats) { viewModel.translate(it) }}"
                                            } else {
                                                viewModel.translate("No Alarms Scheduled")
                                            }
                                            
                                            Text(
                                                text = repeatDesc,
                                                fontSize = 12.sp,
                                                color = Color.White.copy(alpha = 0.9f)
                                            )
                                            
                                            if (nextAlarm?.alarmType == "SUNRISE") {
                                                val advice = viewModel.getAdviceForUpcomingAlarm()
                                                if (advice != null) {
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Text(
                                                        text = "☀ " + advice,
                                                        fontSize = 12.sp,
                                                        color = Color(0xFFFDE68A), // Light aesthetic yellow
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }

                                        val na = nextAlarm
                                        if (na != null) {
                                            Switch(
                                                checked = na.active,
                                                onCheckedChange = { onAlarmToggle(na) },
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
                                            text = viewModel.translate("Use the Sunrise/Sunset cards above or the Add Standard Alarm button to schedule alarms."),
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
                                        onToggleActive = { onAlarmToggle(alarm) },
                                        onDeleteClick = { deleteDialogAlarm = alarm },
                                        remainingTime = viewModel.calculateTimeUntilTrigger(alarm),
                                        translate = { viewModel.translate(it) }
                                    )
                                }
                            }
                        }
                        // §778 — one small, non-intrusive banner pinned to the very
                        // bottom of the alarm list. It scrolls past with the content and
                        // never overlaps the clock, sun cards, or controls. Config-driven
                        // (OdioBook admin) — no-op until ads are enabled for "earlyrover".
                        item {
                            com.example.ads.OdioBookAds.Banner(
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
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
                                onManageCitiesClick = onNavigateToManageCities,
                                hasLocationPermission = hasLocationPermission,
                                onRequestLocationPermission = {
                                    dashboardPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            )
                        }

                        if (isWeatherEnabled) {
                            item {
                                SleekWeatherSection(
                                    viewModel = viewModel,
                                    modifier = Modifier.fillMaxWidth(),
                                    onChangeLocationClick = { showLocationSearchDialog = true },
                                    showExtendedData = true
                                )
                            }
                            // Full AQI card lives on the WEATHER tab.
                            airQuality?.let { aqi ->
                                item {
                                    AirQualityCard(aqi = aqi, viewModel = viewModel)
                                }
                            }
                        }

                    }
                }

                2 -> { // SETTINGS
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding())
                    ) {
                        SettingsScreen(
                            viewModel = viewModel,
                            onNavigateBack = { activeTab = 0 },
                            onNavigateToPrivacyPolicy = onNavigateToPrivacyPolicy,
                            onNavigateToTermsConditions = onNavigateToTermsConditions
                        )
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
        }

        }
    }

    // Skip-vs-turn-off dialog for turning OFF a repeating alarm (D6 / Task 2).
    skipDialogAlarm?.let { alarm ->
        SkipOrTurnOffDialog(
            viewModel = viewModel,
            onSkip = {
                viewModel.skipNextOccurrence(alarm)
                skipDialogAlarm = null
            },
            onTurnOff = {
                viewModel.toggleAlarmActive(alarm)
                skipDialogAlarm = null
            },
            onDismiss = { skipDialogAlarm = null }
        )
    }

    // Delete confirmation dialog
    deleteDialogAlarm?.let { alarm ->
        AlertDialog(
            onDismissRequest = { deleteDialogAlarm = null },
            title = { Text(viewModel.translate("Delete Alarm")) },
            text = { Text(viewModel.translate("Are you sure you want to delete this alarm?")) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAlarm(alarm)
                    deleteDialogAlarm = null
                }) {
                    Text(viewModel.translate("Delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogAlarm = null }) {
                    Text(viewModel.translate("Cancel"))
                }
            }
        )
    }

    if (showLocationSearchDialog) {
        LocationSearchDialog(
            initialQuery = "",
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
    onDeleteClick: () -> Unit,
    remainingTime: String?,
    translate: (String) -> String = { it }
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
                // For SUNRISE/SUNSET alarms the stored hour/minute is the RAW (standard, no-DST)
                // wall clock. Render the same absolute instant the hero/next-occurrence uses so the
                // card matches the real ring time (and isn't off by the DST offset for half the year).
                val (displayHour, displayMinute) = remember(alarm) {
                    if (alarm.alarmType == "SUNRISE" || alarm.alarmType == "SUNSET") {
                        try {
                            val instant = SunAlarmResolver.nextTriggerInstant(
                                alarm,
                                java.time.Instant.now(),
                                java.time.ZoneId.systemDefault()
                            )
                            val local = instant.atZone(java.time.ZoneId.systemDefault())
                            Pair(local.hour, local.minute)
                        } catch (e: Exception) {
                            Pair(alarm.hour, alarm.minute)
                        }
                    } else {
                        Pair(alarm.hour, alarm.minute)
                    }
                }
                val hour12 = if (displayHour % 12 == 0) 12 else displayHour % 12
                val ampm = if (displayHour >= 12) "PM" else "AM"
                
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format("%02d:%02d", hour12, displayMinute),
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
                    if (remainingTime != null && alarm.active) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${translate("in")} $remainingTime",
                            fontSize = 12.sp,
                            color = SleekSolarAccent
                        )
                    }
                }

                val titleDefault = if (alarm.title.isNotEmpty()) alarm.title else when (alarm.alarmType) {
                    "SUNRISE" -> translate("Sunrise Tracker")
                    "SUNSET" -> translate("Sunset Tracker")
                    else -> translate("Manual Alarm")
                }

                val trackerDesc = when (alarm.alarmType) {
                    "SUNRISE", "SUNSET" -> {
                        val exactPart = if (alarm.ringAtExactAlso) "${translate("exactly")} + " else ""
                        val offsetPart = if (alarm.offsetMinutes == 0) (if (!alarm.ringAtExactAlso) translate("exactly based") else translate("(no offset)")) else if (alarm.offsetMinutes < 0) "${-alarm.offsetMinutes}${translate("m before")}" else "${alarm.offsetMinutes}${translate("m after")}"
                        val desc = "$exactPart$offsetPart".trimEnd(' ', '+')
                        desc
                    }
                    else -> if (alarm.title.isEmpty()) translate("Standard Clock Alarm") else ""
                }

                Text(
                    text = titleDefault + if (trackerDesc.isNotEmpty()) " - $trackerDesc" else "",
                    fontSize = 12.sp,
                    color = if (alarm.active) SleekMutedText else SleekMutedText.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Normal
                )
                
                if (alarm.isRepeating()) {
                    Text(
                        text = getDailyRepeaterString(alarm.getRepeatDaysList(), translate),
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
                        text = if (alarm.snoozeEnabled) "${translate("Snooze")} ${alarm.snoozeMinutes}m" else translate("Snooze off"),
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

/**
 * Compact top-right header widget: current temperature + a weather icon + the device-local
 * time (HH:mm). Falls back gracefully when weather hasn't loaded yet.
 */
@Composable
fun CompactWeatherTimeWidget(
    weather: com.example.alarm.weather.WeatherInfo?,
    tzOffset: Double
) {
    // Re-tick the displayed time roughly every 30s so HH:mm stays current.
    var nowTick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            nowTick = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }

    val localTime = remember(nowTick, tzOffset) {
        try {
            // Convert decimal hours (e.g. 5.5) to integer seconds (e.g. 19800) then to a ZoneOffset.
            // ZoneOffset is a subclass of ZoneId and is the most reliable way to handle fractional hour zones (India +5.5, Nepal +5.75).
            val offsetSeconds = Math.round(tzOffset * 3600.0).toInt().coerceIn(-18 * 3600, 18 * 3600)
            val zone = java.time.ZoneOffset.ofTotalSeconds(offsetSeconds)
            java.time.ZonedDateTime.now(zone)
                .format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.US))
        } catch (e: Exception) {
            java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.US))
        }
    }

    Row(
        modifier = Modifier
            .background(SleekCardBg.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .border(BorderStroke(0.5.dp, SleekBorder), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (weather != null && !weather.temperatureC.isNaN()) {
            val (icon, color) = getWeatherIconAndColor(weather.condition, weather.isDay)
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "${weather.temperatureC.roundToInt()}°",
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                color = SleekActiveText
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(SleekBorder)
            )
        }
        Text(
            text = localTime,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = SleekPrimary
        )
    }
}

/**
 * Compact "Journey Active" tracker shown on the DASH tab while travel tracking is running.
 * Tapping it opens the full Travel tab.
 */
@Composable
fun JourneyActiveCard(
    viewModel: AlarmViewModel,
    onClick: () -> Unit
) {
    val distanceKm by viewModel.travelTotalTripDistance.collectAsStateWithLifecycle()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .border(BorderStroke(0.5.dp, SleekPrimary.copy(alpha = 0.5f)), RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SleekCardBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(SleekPrimary.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = null,
                    tint = SleekPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = viewModel.translate("Journey Active"),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = SleekActiveText
                )
                Text(
                    text = "${viewModel.translate("Tracking")} • ${String.format(Locale.US, "%.1f", distanceKm)} km",
                    fontSize = 12.sp,
                    color = SleekSecondary,
                    fontWeight = FontWeight.Medium
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = SleekMutedText,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** "Add Travel Alarm" CTA on the DASH tab that routes the user to the Travel tab. */
@Composable
fun AddTravelAlarmCard(
    viewModel: AlarmViewModel,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .border(BorderStroke(0.5.dp, SleekBorder), RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SleekCardBg.copy(alpha = 0.85f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(SleekSecondary.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Explore,
                    contentDescription = null,
                    tint = SleekSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = viewModel.translate("Add Travel Alarm"),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = SleekActiveText
                )
                Text(
                    text = viewModel.translate("Get woken when you near your destination."),
                    fontSize = 12.sp,
                    color = SleekMutedText,
                    fontWeight = FontWeight.Medium
                )
            }
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = SleekSecondary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Dialog shown when a user turns OFF a REPEATING alarm: offers to skip only the next
 * occurrence (alarm stays active) or turn the alarm off completely.
 */
@Composable
fun SkipOrTurnOffDialog(
    viewModel: AlarmViewModel,
    onSkip: () -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, SleekBorder), RoundedCornerShape(20.dp)),
            color = SleekCardBg,
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = viewModel.translate("Turn off this alarm?"),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = SleekActiveText
                )
                Text(
                    text = viewModel.translate("This alarm repeats. Skip just the next occurrence, or turn it off completely?"),
                    fontSize = 13.sp,
                    color = SleekMutedText
                )

                Button(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(viewModel.translate("Skip today only"), color = Color.White)
                }

                OutlinedButton(
                    onClick = onTurnOff,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = SleekActiveText
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(viewModel.translate("Turn off completely"), color = SleekActiveText)
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(viewModel.translate("Cancel"), color = SleekMutedText)
                }
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

private fun getDailyRepeaterString(days: List<Int>, translate: (String) -> String = { it }): String {
    if (days.size == 7) return translate("Every day")
    if (days.size == 5 && !days.contains(6) && !days.contains(7)) return translate("Weekdays")
    if (days.size == 2 && days.contains(6) && days.contains(7)) return translate("Weekends")

    val dayChars = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    return days.sorted().mapNotNull { dayChars.getOrNull(it - 1)?.let(translate) }.joinToString(", ")
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

    // Open with an empty input (D8 / Task 10): never auto-search the current city. Clear any
    // stale results from a previous session so the helper hint shows on first open.
    LaunchedEffect(Unit) {
        if (initialQuery.isEmpty()) {
            viewModel.searchLocationQuery("")
        } else {
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

    // Host the search UI in a real Dialog window so it renders ABOVE the native OpenGL
    // solar-system surface (which is drawn on top of the main window and would otherwise
    // bleed through normal composition). The Dialog also provides the scrim, back handling,
    // outside-tap dismissal, and proper WindowInsets/IME handling for free.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 56.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
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
                        text = viewModel.translate("Search city (e.g. Reykjavik, London, Tokyo...)"),
                        fontSize = 11.sp,
                        color = SleekMutedText,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
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

    var selectedTab by rememberSaveable { mutableStateOf(0) } // 0 = TODAY, 1 = FUTURE FORECAST, 2 = HISTORIC HISTORY
    var expandedDayIso by rememberSaveable { mutableStateOf<String?>(null) } // for item accordion expansion

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
                                        text = if (weather.current.temperatureC.isNaN()) "--" else "${weather.current.temperatureC.roundToInt()}°",
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
                                        value = if (weather.apparentTemperatureC.isNaN()) "--" else "${weather.apparentTemperatureC.roundToInt()}°C",
                                        icon = Icons.Default.WbSunny
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    WeatherParamLabelValue(
                                        label = translateWeatherText("Precip", lang),
                                        value = String.format(java.util.Locale.US, "%.1f mm", weather.precipitationMm),
                                        icon = Icons.Default.PlayArrow
                                    )
                                    WeatherParamLabelValue(
                                        label = translateWeatherText("Wind", lang),
                                        value = "${weather.windSpeedKmh.roundToInt()} km/h",
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

                        // Next 24 hours of hourly details (horizontal scroll).
                        // API timeIso strings are in the LOCATION's local time (timezone=auto), so
                        // compute "now" in the location's zone too — not the device's — otherwise the
                        // 24h slice starts at the wrong hour for a city in another timezone.
                        val now = try {
                            java.time.LocalDateTime.now(
                                java.time.ZoneOffset.ofTotalSeconds((tzOffset * 3600).toInt())
                            )
                        } catch (e: Exception) {
                            java.time.LocalDateTime.now()
                        }
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
                                        val hourDay = weather.dailyList.firstOrNull { it.dateIso == hour.timeIso.substringBefore("T") }
                                        val (icon, color) = getWeatherIconAndColor(hour.condition, isIsoTimeDaytime(hour.timeIso, hourDay?.sunriseIso, hourDay?.sunsetIso))
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = color,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = if (hour.temperatureC.isNaN()) "--" else "${hour.temperatureC.roundToInt()}°",
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
                        val hiStr = if (day.maxTempC.isNaN()) "--" else "${day.maxTempC.roundToInt()}°"
                        val loStr = if (day.minTempC.isNaN()) "--" else "${day.minTempC.roundToInt()}°"
                        Text(
                            text = "$hiStr / $loStr",
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
                                val (hIcon, hCol) = getWeatherIconAndColor(hour.condition, isIsoTimeDaytime(hour.timeIso, day.sunriseIso, day.sunsetIso))
                                Icon(
                                    imageVector = hIcon,
                                    contentDescription = null,
                                    tint = hCol,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (hour.temperatureC.isNaN()) "--" else "${hour.temperatureC.roundToInt()}°",
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

/** Maps a US AQI value to a qualitative label + accent color using the EPA 0-500 scale. */
private fun usAqiQuality(aqi: Int): Pair<String, Color> = when {
    aqi <= 50 -> "Good" to Color(0xFF22C55E)
    aqi <= 100 -> "Moderate" to Color(0xFFFBBF24)
    aqi <= 150 -> "Unhealthy for Sensitive Groups" to Color(0xFFF97316)
    aqi <= 200 -> "Unhealthy" to Color(0xFFEF4444)
    aqi <= 300 -> "Very Unhealthy" to Color(0xFF8B5CF6)
    else -> "Hazardous" to Color(0xFF7F1D1D)
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
        // US AQI uses the EPA 0-500 scale with its own bands.
        usAqiQuality(aqi.usAqi)
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
                            value = "${aqi.pm25.roundToInt()} µg/m³",
                            icon = Icons.Default.Air
                        )
                    }
                    if (showPm10) {
                        WeatherParamLabelValue(
                            label = translateWeatherText("PM10", lang),
                            value = "${aqi.pm10.roundToInt()} µg/m³",
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

/**
 * Decides day vs. night for a forecast hour using the real sunrise/sunset window when both
 * bounds are available, falling back to the coarse 6..17 heuristic when they can't be parsed.
 * This is correct at high latitudes / other timezones where the fixed 6-17 band is wrong.
 */
fun isIsoTimeDaytime(isoTime: String, sunriseIso: String?, sunsetIso: String?): Boolean {
    return try {
        val time = java.time.LocalDateTime.parse(isoTime)
        val sunrise = sunriseIso?.let { java.time.LocalDateTime.parse(it) }
        val sunset = sunsetIso?.let { java.time.LocalDateTime.parse(it) }
        if (sunrise != null && sunset != null) {
            time >= sunrise && time < sunset
        } else {
            isIsoTimeDaytime(isoTime)
        }
    } catch (e: Exception) {
        isIsoTimeDaytime(isoTime)
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
                    " (${weatherInfo!!.temperatureC.roundToInt()}°)"
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

@Composable
fun Stylized3DSunArcProgressionCard(
    viewModel: AlarmViewModel,
    alarms: List<Alarm>
) {
    val darkTheme = isSystemInDarkTheme()
    val lat by viewModel.latitude.collectAsState()
    val lng by viewModel.longitude.collectAsState()
    val tzOffset by viewModel.timezoneOffset.collectAsState()
    val locationName by viewModel.locationName.collectAsState()

    val sunriseAlarm = alarms.find { it.alarmType == "SUNRISE" }
    val sunsetAlarm = alarms.find { it.alarmType == "SUNSET" }
    val today = java.time.LocalDate.now()
    
    val sunriseTimeLocalized = com.example.alarm.data.SunAlarmResolver.fireTimeOn(
        sunriseAlarm ?: com.example.alarm.data.Alarm(title = "Sunrise", alarmType = "SUNRISE", hour = 6, minute = 0, latitude = lat, longitude = lng, timezoneOffset = tzOffset, locationName = locationName),
        today
    )
    val sunsetTimeLocalized = com.example.alarm.data.SunAlarmResolver.fireTimeOn(
        sunsetAlarm ?: com.example.alarm.data.Alarm(title = "Sunset", alarmType = "SUNSET", hour = 18, minute = 0, latitude = lat, longitude = lng, timezoneOffset = tzOffset, locationName = locationName),
        today
    )

    val activeAlarms = alarms.filter { it.active }.map { LocalTime.of(it.hour, it.minute) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SleekCardBg)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.WbSunny,
                    contentDescription = null,
                    tint = SleekSolarAccent,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = viewModel.translate("3D Solar Arc progression"),
                        fontWeight = FontWeight.Bold,
                        color = SleekActiveText,
                        fontSize = 15.sp
                    )
                    Text(
                        text = viewModel.translate("Progression with selected active alarm indicators"),
                        color = SleekMutedText,
                        fontSize = 11.sp
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (darkTheme) Color(0xFF070A13) else Color(0xFFF1F5F9))
            ) {
                Sun3DProgressionView(
                    modifier = Modifier.fillMaxSize(),
                    sunriseTime = sunriseTimeLocalized,
                    sunsetTime = sunsetTimeLocalized,
                    currentTime = LocalTime.now(),
                    alarmTimes = activeAlarms,
                    isDark = darkTheme
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                LegendItem(color = Color(0xFFF59E0B), text = viewModel.translate("Sunrise"))
                LegendItem(color = Color(0xFFFA503C), text = viewModel.translate("Sunset"))
                LegendItem(color = Color(0xFF1ECDC0), text = viewModel.translate("Alarms"))
            }
        }
    }
}

@Composable
fun LegendItem(color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Text(
            text = text,
            fontSize = 11.sp,
            color = SleekMutedText,
            fontWeight = FontWeight.Medium
        )
    }
}



