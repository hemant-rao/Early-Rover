package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose. runtime.getValue
import androidx.compose.ui.Modifier
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            val viewModel: AlarmViewModel = viewModel()
            model = viewModel

            // Intercept incoming foreground ring intents immediately inside LaunchedEffect
            androidx.compose.runtime.LaunchedEffect(intent) {
                checkIncomingAlarmIntent(intent)
            }

            val darkTheme by viewModel.darkThemeEnabled.collectAsState()

            MyApplicationTheme(darkTheme = darkTheme) {
                val navController = rememberNavController()
                
                // Ringing screen state overlay
                val ringingState by viewModel.ringingAlarm.collectAsState()

                if (ringingState != null) {
                    AlarmRingScreen(
                        title = ringingState!!.title,
                        type = ringingState!!.type,
                        onDismiss = { viewModel.stopRingingAlarmAndDismiss() },
                        onSnooze = { viewModel.stopRingingAlarmAndSnooze() }
                    )
                } else {
                    NavHost(
                        navController = navController,
                        startDestination = "splash",
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
                                onNavigateToAddAlarm = { type ->
                                    viewModel.startNewAlarmScratchpad(type)
                                    navController.navigate("add_edit_alarm")
                                },
                                onNavigateToEditAlarm = { id ->
                                    navController.navigate("add_edit_alarm?id=$id")
                                },
                                onNavigateToLocation = {
                                    navController.navigate("location")
                                },
                                onNavigateToSettings = {
                                    navController.navigate("settings")
                                }
                            )
                        }

                        composable(
                            route = "add_edit_alarm?id={id}",
                            arguments = listOf(
                                navArgument("id") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                }
                            )
                        ) { backStackEntry ->
                            val idString = backStackEntry.arguments?.getString("id")
                            val id = idString?.toIntOrNull()
                            AddEditAlarmScreen(
                                viewModel = viewModel,
                                alarmId = id,
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

    private fun checkIncomingAlarmIntent(intent: Intent?) {
        if (intent != null && intent.hasExtra("RINGING_ALARM_ID")) {
            val id = intent.getIntExtra("RINGING_ALARM_ID", -1)
            val title = intent.getStringExtra("RINGING_ALARM_TITLE") ?: "Alarm Clock"
            val type = intent.getStringExtra("RINGING_ALARM_TYPE") ?: "CUSTOM"
            model.setRingingState(id, title, type)
            
            // Clean values to prevent looping on recompose
            intent.removeExtra("RINGING_ALARM_ID")
        }
    }
}
