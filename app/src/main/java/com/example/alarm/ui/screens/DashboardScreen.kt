package com.example.alarm.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.alarm.data.Alarm
import com.example.alarm.opengl.Celestial3DView
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

    val formattedDate = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM dd", Locale.getDefault()))
    }

    var showQuickAddMenu by remember { mutableStateOf(false) }

    var currentLiveTime by remember { mutableStateOf(java.time.LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentLiveTime = java.time.LocalTime.now()
            kotlinx.coroutines.delay(2000) // update every 2 seconds is incredibly efficient and smooth
        }
    }

    // Hardware-accelerated smooth solar system rotation state
    val infiniteTransition = rememberInfiniteTransition(label = "OrbitRotation")
    val orbitRotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 35000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "OrbitRotationAngle"
    )

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_screen"),
        containerColor = SleekBackground,
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
                                Text(
                                    text = viewModel.translate("SOLARIS LIVE"),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekMutedText
                                )
                            }
                        }
                    }
                }

                // 1. DYNAMIC ORBIT SPHERE & 3D GL CELESTIAL VIEWER
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(290.dp)
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Ambient Radial Glow layers corresponding to Sleek theme
                        Box(
                            modifier = Modifier
                                .fillMaxSize(0.9f)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(SleekPrimary.copy(alpha = 0.15f), Color.Transparent),
                                        radius = 350f
                                    )
                                )
                        )

                        // Orbit track box with live rotating Sun and Moon icons!
                        val primaryColor = SleekPrimary
                        Box(
                            modifier = Modifier.size(240.dp)
                        ) {
                            // 1. Orbit dashed line
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawCircle(
                                    color = primaryColor.copy(alpha = 0.35f),
                                    style = Stroke(
                                        width = 1.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                                    )
                                )
                            }

                            // Calculate live geometric floats
                            val liveHourFloat = currentLiveTime.hour + (currentLiveTime.minute / 60.0f) + (currentLiveTime.second / 3600.0f)

                            // Sun position on the 240.dp orbit (radius = 120.dp) + matching rotation state
                            val sunAngleRad = Math.toRadians(- (liveHourFloat / 24.0 * 360.0) + 90.0 + orbitRotationAngle)
                            val sunX = (120.0 * Math.cos(sunAngleRad)).toFloat().dp
                            val sunY = -(120.0 * Math.sin(sunAngleRad)).toFloat().dp

                            // Golden Sun indicator sphere
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset(x = sunX, y = sunY)
                                    .size(28.dp)
                                    .background(
                                        Brush.radialGradient(listOf(SleekSolarAccent, Color(0xFFFF9C1A))),
                                        CircleShape
                                    )
                                    .shadow(6.dp, CircleShape)
                                    .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WbSunny,
                                    contentDescription = "Real-time Sun position",
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            // Moon position (opposite to sun, offset by 12 hours) + matching rotation state
                            val moonHourFloat = (liveHourFloat + 12f) % 24f
                            val moonAngleRad = Math.toRadians(- (moonHourFloat / 24.0 * 360.0) + 90.0 + orbitRotationAngle)
                            val moonX = (120.0 * Math.cos(moonAngleRad)).toFloat().dp
                            val moonY = -(120.0 * Math.sin(moonAngleRad)).toFloat().dp

                            // Midnight Purple Indigo Moon indicator sphere
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset(x = moonX, y = moonY)
                                    .size(28.dp)
                                    .background(
                                        Brush.radialGradient(listOf(Color(0xFF818CF8), Color(0xFF4F46E5))),
                                        CircleShape
                                    )
                                    .shadow(6.dp, CircleShape)
                                    .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ModeNight,
                                    contentDescription = "Real-time Moon position",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        // Central Sphere container matching HTML 3D Mimicry
                        Box(
                            modifier = Modifier.size(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            // Layer 1: Visual background decoration (with shadow, border, circle shape)
                            Spacer(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .shadow(12.dp, CircleShape)
                                    .background(SleekCardBg, CircleShape)
                                    .border(1.dp, SleekBorder, CircleShape)
                            )

                            // Layer 2: Interactive WebGL OpenGL view, safely positioned without clipping
                            Celestial3DView(
                                modifier = Modifier.fillMaxSize(),
                                sunriseTime = sunrise,
                                sunsetTime = sunset,
                                activeAlarms = alarms.filter { it.active }.map { Pair(it.hour, it.minute) },
                                isDark = darkTheme
                            )
                        }

                        // Floating Sunrise Badge (Top Right)
                        Card(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-8).dp, y = 12.dp)
                                .shadow(8.dp, RoundedCornerShape(14.dp)),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = SleekCardBg.copy(alpha = 0.85f)),
                            border = BorderStroke(0.5.dp, SleekBorder)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(
                                    text = viewModel.translate("SUNRISE"),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekSolarAccent,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = String.format("%02d:%02d AM", if (sunrise.hour % 12 == 0) 12 else sunrise.hour % 12, sunrise.minute),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekActiveText
                                )
                            }
                        }

                        // Floating Sunset Badge (Bottom Left)
                        Card(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .offset(x = 8.dp, y = (-12).dp)
                                .shadow(8.dp, RoundedCornerShape(14.dp)),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = SleekCardBg.copy(alpha = 0.85f)),
                            border = BorderStroke(0.5.dp, SleekBorder)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(
                                    text = viewModel.translate("SUNSET"),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekSecondary,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = String.format("%02d:%02d PM", if (sunset.hour % 12 == 0) 12 else sunset.hour % 12, sunset.minute),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekActiveText
                                )
                            }
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
