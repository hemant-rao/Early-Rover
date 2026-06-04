package com.example

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import com.example.alarm.scheduling.RescheduleReceiver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.alarm.util.LocaleHelper
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.alarm.ui.screens.*
import com.example.alarm.viewmodel.AlarmViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private lateinit var model: AlarmViewModel

    // Pending in-app destination requested by a tapped notification (e.g. "travel").
    // Held as Compose state so onCreate/onNewIntent can both route the UI to the right page.
    private val pendingNavDestination = mutableStateOf<String?>(null)

    // Apply the saved per-app locale to every fresh Activity context (no AppCompat needed).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    // ACTION_TIME_CHANGED / ACTION_TIMEZONE_CHANGED are only delivered to dynamically
    // registered receivers (not manifest-declared ones), so we register RescheduleReceiver
    // here to recalibrate sun alarms when the user changes the clock or flies to a new zone.
    private val timeChangeReceiver = RescheduleReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val timeFilter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timeChangeReceiver, timeFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(timeChangeReceiver, timeFilter)
        }
        
        setContent {
            val viewModel: AlarmViewModel = viewModel()
            model = viewModel

            // Intercept incoming foreground ring intents immediately inside LaunchedEffect
            androidx.compose.runtime.LaunchedEffect(intent) {
                checkIncomingAlarmIntent(intent)
            }

            // Adaptive theme: AUTO follows daylight at the active location, LIGHT/DARK are explicit.
            val themeMode by viewModel.themeMode.collectAsState()
            val weather by viewModel.weather.collectAsState()
            val sunriseTime by viewModel.sunriseTime.collectAsState()
            val sunsetTime by viewModel.sunsetTime.collectAsState()
            val tzOffset by viewModel.timezoneOffset.collectAsState()
            // Prefer the live weather day/night signal; fall back to current-hour vs sunrise/sunset.
            // Evaluate "now" in the ACTIVE LOCATION's timezone (matching sunrise/sunset), not the device's.
            val isDayAtLocation = weather?.isDay ?: run {
                try {
                    val zone = java.time.ZoneOffset.ofTotalSeconds((tzOffset * 3600).toInt())
                    val now = java.time.OffsetDateTime.ofInstant(java.time.Instant.now(), zone).toLocalTime()
                    if (sunsetTime <= sunriseTime) now.hour in 6..17 // polar/wrap fallback by hour
                    else !now.isBefore(sunriseTime) && now.isBefore(sunsetTime)
                } catch (e: Exception) {
                    java.time.LocalTime.now().hour in 6..17
                }
            }
            val darkTheme = viewModel.isEffectiveDark(isDayAtLocation)
            val switching by viewModel.isSwitchingLanguage.collectAsState()

            // Request location permissions at startup
            val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                if (permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                    viewModel.triggerAutoLocationDetect()
                }
            }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                permissionLauncher.launch(arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            }

            // While the activity recreates for a language switch, keep a loader on top long
            // enough to cover the native black gap, then clear the flag so it disappears.
            LaunchedEffect(switching) {
                if (switching) {
                    kotlinx.coroutines.delay(700)
                    viewModel.clearLanguageSwitching()
                }
            }

            MyApplicationTheme(darkTheme = darkTheme) {
                Box(modifier = Modifier.fillMaxSize()) {
                val navController = rememberNavController()
                
                // Ringing screen state overlay
                val ringingState by viewModel.ringingAlarm.collectAsState()

                // When a notification is tapped, jump straight to the dashboard (skipping
                // splash) so the requested tab can be shown.
                androidx.compose.runtime.LaunchedEffect(pendingNavDestination.value) {
                    if (pendingNavDestination.value != null) {
                        navController.navigate("dashboard") {
                            popUpTo("splash") { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }

                if (ringingState != null) {
                    AlarmRingScreen(
                        title = ringingState!!.title,
                        type = ringingState!!.type,
                        onDismiss = {
                            val isTravel = ringingState!!.type == "TRAVEL"
                            viewModel.stopRingingAlarmAndDismiss()
                            if (isTravel) {
                                pendingNavDestination.value = "travel"
                            }
                        },
                        onSnooze = {
                            val isTravel = ringingState!!.type == "TRAVEL"
                            viewModel.stopRingingAlarmAndSnooze()
                            if (isTravel) {
                                pendingNavDestination.value = "travel"
                            }
                        }
                    )
                } else {
                    NavHost(
                        navController = navController,
                        // On a recreate (language change, rotation, restore) skip the splash and
                        // land on the dashboard instead of replaying the splash animation.
                        startDestination = if (savedInstanceState != null) "dashboard" else "splash",
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable("splash") {
                            SplashScreen(
                                onNavigateToDashboard = {
                                    navController.navigate("dashboard") {
                                        popUpTo("splash") { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable("dashboard") {
                            DashboardScreen(
                                viewModel = viewModel,
                                requestedTab = destinationToTab(pendingNavDestination.value),
                                onTabConsumed = { pendingNavDestination.value = null },
                                onNavigateToAddAlarm = { type ->
                                    navController.navigate("add_edit_alarm?type=$type")
                                },
                                onNavigateToEditAlarm = { id ->
                                    navController.navigate("add_edit_alarm?id=$id")
                                },
                                onNavigateToLocation = {
                                    navController.navigate("location")
                                },
                                onNavigateToSettings = {
                                    navController.navigate("settings")
                                },
                                onNavigateToManageCities = {
                                    navController.navigate("manage_cities")
                                }
                            )
                        }

                        composable("manage_cities") {
                            ManageCitiesScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.navigateUp() }
                            )
                        }

                        composable(
                            route = "add_edit_alarm?id={id}&type={type}",
                            arguments = listOf(
                                navArgument("id") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                },
                                navArgument("type") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                }
                            )
                        ) { backStackEntry ->
                            val id = backStackEntry.arguments?.getString("id")?.toIntOrNull()
                            val type = backStackEntry.arguments?.getString("type")
                            AddEditAlarmScreen(
                                viewModel = viewModel,
                                alarmId = id,
                                alarmType = type,
                                onNavigateBack = { navController.navigateUp() }
                            )
                        }

                        composable("location") {
                            LocationSettingsScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.navigateUp() }
                            )
                        }

                        composable("settings") {
                            SettingsScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.navigateUp() }
                            )
                        }
                    }
                }

                    // Full-screen loader drawn above everything while the language switch
                    // recreate is in flight — replaces the native black flash.
                    if (switching) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF0B1020)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = viewModel.translate("Applying language…"),
                                    color = Color.White,
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::model.isInitialized) {
            checkIncomingAlarmIntent(intent)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(timeChangeReceiver)
        } catch (e: Exception) {
            // Receiver may already be unregistered; ignore.
        }
        super.onDestroy()
    }

    private fun checkIncomingAlarmIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.hasExtra("RINGING_ALARM_ID")) {
            val id = intent.getIntExtra("RINGING_ALARM_ID", -1)
            val title = intent.getStringExtra("RINGING_ALARM_TITLE") ?: "Alarm Clock"
            val type = intent.getStringExtra("RINGING_ALARM_TYPE") ?: "CUSTOM"
            model.setRingingState(id, title, type)

            // Clean values to prevent looping on recompose
            intent.removeExtra("RINGING_ALARM_ID")
        }

        // A non-ringing notification (e.g. travel tracking) asks us to open a specific page.
        if (intent.hasExtra("NAV_DESTINATION")) {
            pendingNavDestination.value = intent.getStringExtra("NAV_DESTINATION")
            intent.removeExtra("NAV_DESTINATION")
        }
    }

    // Maps a notification's destination key to the Dashboard's internal tab index.
    private fun destinationToTab(destination: String?): Int? = when (destination) {
        "dashboard" -> 0
        "weather" -> 1
        "settings" -> 2
        "travel" -> 3
        else -> null
    }
}
