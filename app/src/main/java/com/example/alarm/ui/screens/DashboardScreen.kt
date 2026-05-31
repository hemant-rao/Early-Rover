package com.example.alarm.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.alarm.data.Alarm
import com.example.alarm.opengl.Celestial3DView
import com.example.alarm.ui.weather.WeatherBackground
import com.example.alarm.viewmodel.AlarmViewModel
import com.example.ui.theme.*
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
    onNavigateToSettings: () -> Unit
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

    val formattedDate = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM dd", Locale.getDefault()))
    }

    var showQuickAddMenu by remember { mutableStateOf(false) }

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
            // Elegant Sleek Bottom Navigation Bar with 3 equal elements
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
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tabModifier = Modifier
                        .weight(1f)
                        .height(72.dp)

                    // 1. DASH Tab
                    Box(
                        modifier = tabModifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { /* Already here */ }
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
                                tint = SleekPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "DASH",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekPrimary
                            )
                        }
                    }

                    // 2. COORDINATES / LOCATION Tab
                    Box(
                        modifier = tabModifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onNavigateToLocation() }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = "Location",
                                tint = SleekMutedText.copy(alpha = 0.7f),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "LOCATION",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekMutedText.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // 3. SETTINGS Tab
                    Box(
                        modifier = tabModifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onNavigateToSettings() }
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
                                tint = SleekMutedText.copy(alpha = 0.7f),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "SETTINGS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekMutedText.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
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
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // A. SLEEK USER HEADER BLOCK
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = viewModel.translate("CURRENT LOCATION"),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = SleekMutedText,
                                letterSpacing = 1.5.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { onNavigateToLocation() }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = SleekSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = locationName.ifEmpty { "Reykjavík, IS" },
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = SleekActiveText
                                    )
                                )
                            }
                            
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
                                        "${w.temperatureC.toInt()}° • ${w.description.uppercase()}"
                                    else viewModel.translate("SOLARIS LIVE"),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekMutedText
                                )
                            }
                        }
                    }
                }

                // 1. INTERACTIVE NATIVE 3D SOLAR SYSTEM (OpenGL ES)
                // Sun at centre; every planet sits at its real heliocentric angle for
                // the current instant. Drag to orbit the camera, pinch to zoom.
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            // Ambient radial glow shows through the transparent GL surface.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize(0.9f)
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(SleekPrimary.copy(alpha = 0.12f), Color.Transparent),
                                            radius = 350f
                                        )
                                    )
                            )

                            Celestial3DView(
                                modifier = Modifier.fillMaxSize(),
                                sunriseTime = sunrise,
                                sunsetTime = sunset,
                                activeAlarms = alarms.filter { it.active }.map { Pair(it.hour, it.minute) },
                                isDark = darkTheme,
                                // true = planets visibly orbit (starting from real positions);
                                // set false for scientifically static real-time positions.
                                animateOrbits = true
                            )
                        }

                        // Sunrise / interaction hint / sunset caption row beneath the scene
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SunCaption(
                                label = viewModel.translate("SUNRISE"),
                                value = String.format("%02d:%02d AM", if (sunrise.hour % 12 == 0) 12 else sunrise.hour % 12, sunrise.minute),
                                icon = Icons.Default.WbSunny,
                                tint = SleekSolarAccent
                            )
                            Text(
                                text = "DRAG • PINCH",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekMutedText.copy(alpha = 0.7f),
                                letterSpacing = 1.5.sp
                            )
                            SunCaption(
                                label = viewModel.translate("SUNSET"),
                                value = String.format("%02d:%02d PM", if (sunset.hour % 12 == 0) 12 else sunset.hour % 12, sunset.minute),
                                icon = Icons.Default.WbTwilight,
                                tint = SleekSecondary,
                                alignEnd = true
                            )
                        }
                    }
                }

                // 2. NEXT ALARM GRADIENT HERO CARD
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
                            // Circular abstract background graphic overlay
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

                // 3. SOLAR DETAILS / BOTTOM ACCENTS CARD (TWO BLOCKS SIDE-BY-SIDE)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Card Panel 1: Solar Offset info
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
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(SleekSolarAccent, CircleShape)
                                    )
                                    Text(
                                        text = "SOLAR",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekMutedText,
                                        letterSpacing = 1.sp
                                    )
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

                                Text(
                                    text = activeOffsetStr,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekActiveText
                                )
                                Text(
                                    text = activeOffsetSub,
                                    fontSize = 10.sp,
                                    color = SleekMutedText,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }

                        // Card Panel 2: Celestial vibration/vibe settings
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
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(SleekSecondary, CircleShape)
                                    )
                                    Text(
                                        text = "VIBE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekMutedText,
                                        letterSpacing = 1.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                val vibeStr = if (nextAlarm?.vibrationEnabled == true) "Vibration ON" else "Vibration OFF"
                                val vibeSub = if (nextAlarm?.snoozeEnabled == true) "Snooze ${nextAlarm?.snoozeMinutes}m Enabled" else "Instant Wakeup"

                                Text(
                                    text = vibeStr,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekActiveText
                                )
                                Text(
                                    text = vibeSub,
                                    fontSize = 10.sp,
                                    color = SleekMutedText,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }

                // 4. ALARM SCHEDULES LIST HEADER
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
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

                // 5. ALARM ITEMS CARDS
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
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

            // Quick Add Popup Menu matching modern sleek animations
            AnimatedVisibility(
                visible = showQuickAddMenu,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-96).dp)
                    .padding(horizontal = 24.dp)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
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
                "SUNRISE" -> Triple(Icons.Default.WbSunny, SleekSolarAccent, SleekSolarAccent.copy(alpha = 0.15f))
                "SUNSET" -> Triple(Icons.Default.WbTwilight, SleekSecondary, SleekSecondary.copy(alpha = 0.15f))
                else -> Triple(Icons.Default.AccessTime, Color.LightGray, Color.LightGray.copy(alpha = 0.15f))
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
                        color = if (alarm.active) SleekActiveText else SleekActiveText.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = ampm,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (alarm.active) SleekActiveText else SleekActiveText.copy(alpha = 0.5f),
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
                    color = SleekMutedText,
                    fontWeight = FontWeight.Normal
                )
                
                if (alarm.isRepeating()) {
                    Text(
                        text = getDailyRepeaterString(alarm.getRepeatDaysList()),
                        fontSize = 11.sp,
                        color = SleekSecondary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp)
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

private fun getDailyRepeaterString(days: List<Int>): String {
    if (days.size == 7) return "Every day"
    if (days.size == 5 && !days.contains(6) && !days.contains(7)) return "Weekdays"
    if (days.size == 2 && days.contains(6) && days.contains(7)) return "Weekends"
    
    val dayChars = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    return days.sorted().map { dayChars[it - 1] }.joinToString(", ")
}
