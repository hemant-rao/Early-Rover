package com.example.alarm.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.alarm.viewmodel.AlarmViewModel
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AlarmViewModel,
    onNavigateBack: () -> Unit
) {
    val darkTheme by viewModel.darkThemeEnabled.collectAsState()
    val defaultSnoozeMinutes by viewModel.defaultSnoozeMinutes.collectAsState()
    val currentLang by viewModel.currentLanguage.collectAsState()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        containerColor = SleekBackground,
        topBar = {
            TopAppBar(
                title = { Text(viewModel.translate("Settings"), fontWeight = FontWeight.Bold, color = SleekActiveText) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("settings_back_button")) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = viewModel.translate("Go back"), tint = SleekActiveText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 1. VISUAL APPEARANCE PREFERENCE
            Text(
                text = viewModel.translate("Preferences & Appearance").uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = SleekSecondary,
                letterSpacing = 1.2.sp
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SleekCardBg)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(SleekPrimary.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (darkTheme) Icons.Default.ModeNight else Icons.Default.LightMode,
                                contentDescription = null,
                                tint = SleekSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(viewModel.translate("Dark Mode"), fontWeight = FontWeight.Bold, color = SleekActiveText, fontSize = 15.sp)
                            Text(viewModel.translate("Render dark celestial color profiles"), fontSize = 12.sp, color = SleekMutedText)
                        }
                        Switch(
                            checked = darkTheme,
                            onCheckedChange = { viewModel.toggleDarkThemeSetting() },
                            modifier = Modifier.testTag("theme_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SleekPrimary,
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = SleekBorder
                            )
                        )
                    }
                }
            }

            // 1b. LANGUAGE SELECTION PREFERENCE
            Text(
                text = viewModel.translate("Select Language").uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = SleekSecondary,
                letterSpacing = 1.2.sp
            )

            // Dropdown populated from the device's configured languages (en + hi always present).
            val context = LocalContext.current
            val activity = context as? android.app.Activity
            val langOptions = remember { viewModel.availableLanguages() }
            var langMenuOpen by remember { mutableStateOf(false) }
            val currentLangLabel = remember(langOptions, currentLang) {
                langOptions.firstOrNull { it.tag == currentLang }?.displayName
                    ?: langOptions.firstOrNull {
                        it.tag.substringBefore('-').equals(currentLang.substringBefore('-'), true)
                    }?.displayName
                    ?: currentLang
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SleekCardBg)
            ) {
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .clickable { langMenuOpen = true }
                            .padding(20.dp)
                            .testTag("language_dropdown"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentLangLabel,
                            fontWeight = FontWeight.Bold,
                            color = SleekActiveText,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = SleekSecondary
                        )
                    }
                    DropdownMenu(
                        expanded = langMenuOpen,
                        onDismissRequest = { langMenuOpen = false }
                    ) {
                        langOptions.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt.displayName) },
                                onClick = {
                                    langMenuOpen = false
                                    viewModel.setAppLanguage(opt.tag, activity)
                                },
                                modifier = Modifier.testTag("lang_item_${opt.tag}")
                            )
                        }
                    }
                }
            }

            Text(
                text = viewModel.translate("Only English & Hindi are fully translated; other languages change date/number format only."),
                fontSize = 11.sp,
                color = SleekMutedText,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // 2. DEFAULT SNOOZE LIMIT TIMERS
            Text(
                text = viewModel.translate("Default Snooze Duration").uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = SleekSecondary,
                letterSpacing = 1.2.sp
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SleekCardBg)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column {
                        Text(viewModel.translate("Default Snooze Duration"), fontWeight = FontWeight.Bold, color = SleekActiveText, fontSize = 15.sp)
                        Text("${viewModel.translate("Snooze pause duration")}: $defaultSnoozeMinutes ${viewModel.translate("mins")}", fontSize = 12.sp, color = SleekMutedText)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val snoozeOptions = listOf(5, 10, 15, 20)
                        snoozeOptions.forEach { opt ->
                            val isSelected = defaultSnoozeMinutes == opt
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isSelected) SleekPrimary else SleekBorder.copy(alpha = 0.5f)
                                    )
                                    .clickable { viewModel.setDefaultSnoozeMinutes(opt) }
                                    .border(
                                        BorderStroke(
                                            width = 1.dp,
                                            color = if (isSelected) SleekSecondary else Color.Transparent
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(vertical = 12.dp)
                                    .testTag("snooze_opt_$opt"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$opt m",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else SleekMutedText
                                )
                            }
                        }
                    }
                }
            }

            // 3. WAKELOCK / PERMISSIONS COMPREHENSIVE GUIDELINES
            Text(
                text = "ALARM SYSTEM RELIABILITY GUIDES",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = SleekSecondary,
                letterSpacing = 1.2.sp
            )

            // Warning battery Optimization Card (Red Sleek border)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(0.5.dp, Color(0xFFEF4444).copy(alpha = 0.7f)), shape = RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFEF4444).copy(alpha = 0.08f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Battery Optimizations Exclusions", fontWeight = FontWeight.Bold, color = Color(0xFFEF4444), fontSize = 15.sp)
                    }
                    Text(
                        text = "To guarantee the alarm triggers precisely on-time when the physical screen is off, newer Android versions require excluding the app from system-level battery optimizations.",
                        fontSize = 12.sp,
                        color = SleekMutedText
                    )
                    Text(
                        text = "Go to system App Info -> Battery -> and select 'Unrestricted' for completely uninterrupted wake alarm service.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFEF4444)
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SleekCardBg)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.LockPerson, contentDescription = null, tint = SleekSecondary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("System Exact Alarm Permission", fontWeight = FontWeight.Bold, color = SleekActiveText, fontSize = 15.sp)
                    }
                    Text(
                        text = "Solari uses System Alarm Clock info APIs which list upcoming alerts on your lockscreen and bypass Silent / Do Not Disturb boundaries.",
                        fontSize = 12.sp,
                        color = SleekMutedText
                    )
                    Text(
                        text = "If scheduled warnings seem deactivated, ensure 'Alarms & Reminders' permission is granted in the device settings panel.",
                        fontSize = 12.sp,
                        color = SleekMutedText,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 4. DEV / ABOUT BANNER
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SleekCardBg)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "SOLARIS ALARM COMPASS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = SleekSolarAccent,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Version 1.0.0 (Concept Edition)",
                        fontSize = 11.sp,
                        color = SleekMutedText
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Developed using modern Jetpack Compose, Room reactive databases, Alarm Clock APIs, and hardware-accelerated OpenGL ES 2.0 visualization.",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = SleekMutedText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
