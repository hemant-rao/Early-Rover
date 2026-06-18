package com.example.alarm.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
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
import com.example.alarm.viewmodel.ThemeMode
import com.example.alarm.viewmodel.AlarmProfile
import com.example.ui.theme.*
import com.example.ui.AppLogo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AlarmViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToPrivacyPolicy: () -> Unit,
    onNavigateToTermsConditions: () -> Unit
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val defaultSnoozeMinutes by viewModel.defaultSnoozeMinutes.collectAsState()
    val currentLang by viewModel.currentLanguage.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(viewModel.translate("Preferences"), viewModel.translate("Profiles"), viewModel.translate("Advanced"))

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        containerColor = SleekBackground,
        topBar = {
            TopAppBar(
                title = { Text(viewModel.translate("Settings"), fontWeight = FontWeight.Bold, color = SleekActiveText) },
                actions = {
                    AppLogo(modifier = Modifier.size(24.dp).padding(end = 8.dp))
                },
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
        ) {
            // Theme Toggle at the top (Always visible, compact)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SleekCardBg)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(viewModel.translate("Theme"), fontWeight = FontWeight.Bold, color = SleekActiveText, modifier = Modifier.weight(1f))
                
                val themeOptions = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.AUTO)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    themeOptions.forEach { mode ->
                        val isSelected = themeMode == mode
                        val bgColor by animateColorAsState(if (isSelected) SleekPrimary else Color.Transparent, animationSpec = tween(800), label = "theme_bg")
                        val iconColor by animateColorAsState(if (isSelected) Color.White else SleekSecondary, animationSpec = tween(800), label = "theme_icon")

                        IconButton(                
                            onClick = { viewModel.setThemeMode(mode) },
                            modifier = Modifier
                                .size(44.dp)
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(bgColor)
                        ) {
                            Icon(
                                imageVector = when(mode) {
                                    ThemeMode.LIGHT -> Icons.Default.LightMode
                                    ThemeMode.DARK -> Icons.Default.ModeNight
                                    ThemeMode.AUTO -> Icons.Default.BrightnessAuto
                                },                
                                contentDescription = mode.name,
                                tint = iconColor
                            )
                        }
                    }
                }
            }

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = SleekPrimary
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            
            Box(Modifier.fillMaxSize().padding(20.dp)) {
                when(selectedTab) {
                    0 -> PreferencesTab(viewModel, currentLang, defaultSnoozeMinutes)
                    1 -> ProfilesTab(viewModel)
                    2 -> AdvancedTab(viewModel, onNavigateToPrivacyPolicy, onNavigateToTermsConditions)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreferencesTab(viewModel: AlarmViewModel, currentLang: String, defaultSnoozeMinutes: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {

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
            var searchQuery by remember { mutableStateOf("") }
            
            val filteredOptions = remember(searchQuery, langOptions) {
                if (searchQuery.isEmpty()) langOptions
                else langOptions.filter { it.displayName.contains(searchQuery, ignoreCase = true) }
            }

            val currentLangLabel = remember(langOptions, currentLang) {
                langOptions.firstOrNull { it.tag == currentLang }?.displayName
                    ?: langOptions.firstOrNull {
                        it.tag.substringBefore('-').equals(currentLang.substringBefore('-'), true)
                    }?.displayName
                    ?: currentLang
            }
            
            fun getFlagEmoji(tag: String): String {
                return when (tag.substringBefore('-').lowercase()) {
                    "en" -> "🇺🇸"
                    "hi" -> "🇮🇳"
                    "es" -> "🇪🇸"
                    "fr" -> "🇫🇷"
                    "de" -> "🇩🇪"
                    "pt" -> "🇵🇹"
                    "it" -> "🇮🇹"
                    "ru" -> "🇷🇺"
                    "zh" -> "🇨🇳"
                    "ja" -> "🇯🇵"
                    "ko" -> "🇰🇷"
                    "ar" -> "🇸🇦"
                    "bn" -> "🇧🇩"
                    "pa" -> "🇮🇳"
                    else -> "🌐"
                }
            }

            ExposedDropdownMenuBox(
                expanded = langMenuOpen,
                onExpandedChange = { langMenuOpen = !langMenuOpen },
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(24.dp))
            ) {
                TextField(
                    value = searchQuery.ifEmpty { currentLangLabel },
                    onValueChange = { 
                        searchQuery = it
                        langMenuOpen = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langMenuOpen) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = SleekCardBg,
                        unfocusedContainerColor = SleekCardBg,
                        disabledContainerColor = SleekCardBg,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(24.dp)
                )

                ExposedDropdownMenu(
                    expanded = langMenuOpen,
                    onDismissRequest = { langMenuOpen = false },
                    modifier = Modifier.background(SleekCardBg)
                ) {
                    filteredOptions.forEach { opt ->
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    text = "${getFlagEmoji(opt.tag)} ${opt.displayName}",
                                    color = SleekActiveText
                                ) 
                            },
                            onClick = {
                                langMenuOpen = false
                                searchQuery = ""
                                viewModel.setAppLanguage(opt.tag, activity)
                            },
                            modifier = Modifier.testTag("lang_item_${opt.tag}")
                        )
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

			// 3. ALARM PROFILES SECTION
			// (Profiles moved to ProfilesTab)

            // 4. FEATURE TOGGLES
            // (Moved to AdvancedTab)

            // 5. WAKELOCK / PERMISSIONS COMPREHENSIVE GUIDES
            // (Moved to AdvancedTab)

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
                        text = viewModel.translate("EARLY ROVER"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = SleekSolarAccent,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = viewModel.translate("Version 1.0.0 (Concept Edition)"),
                        fontSize = 11.sp,
                        color = SleekMutedText
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = viewModel.translate("Developed using modern Jetpack Compose, Room reactive databases, Alarm Clock APIs, and hardware-accelerated OpenGL ES 2.0 visualization."),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = SleekMutedText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
@Composable
fun ProfilesTab(viewModel: AlarmViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        val alarmProfiles by viewModel.alarmProfiles.collectAsState()
        val activeProfileId by viewModel.activeProfileId.collectAsState()
        var showAddForm by remember { mutableStateOf(false) }
        
        var profileName by remember { mutableStateOf("") }
        var sunriseOffset by remember { mutableFloatStateOf(0f) }
        var sunsetOffset by remember { mutableFloatStateOf(0f) }
        var selectedPattern by remember { mutableStateOf("Steady") }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SleekCardBg)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = viewModel.translate("Save and switch between alarm logic profiles."),
                    fontSize = 13.sp,
                    color = SleekMutedText
                )

                // Render Profiles List
                alarmProfiles.forEach { profile ->
                    val isActive = profile.id == activeProfileId
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isActive) SleekPrimary.copy(alpha = 0.08f) else Color.Transparent)
                            .border(
                                BorderStroke(
                                    if (isActive) 1.5.dp else 0.5.dp, 
                                    if (isActive) SleekPrimary else SleekBorder
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable { viewModel.selectProfile(profile.id) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = isActive,
                                onClick = { viewModel.selectProfile(profile.id) },
                                colors = RadioButtonDefaults.colors(selectedColor = SleekPrimary)
                            )
                            
                            Column {
                                Text(
                                    text = profile.name,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isActive) SleekPrimary else SleekActiveText,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Sunrise: ${if (profile.sunriseOffset >= 0) "+" else ""}${profile.sunriseOffset}m | Sunset: ${if (profile.sunsetOffset >= 0) "+" else ""}${profile.sunsetOffset}m",
                                    fontSize = 12.sp,
                                    color = SleekMutedText
                                )
                                Text(
                                    text = "${viewModel.translate("Vibration Pattern")}: ${profile.vibrationPattern}",
                                    fontSize = 11.sp,
                                    color = SleekSecondary.copy(alpha = 0.8f)
                                )
                            }
                        }
                        
                        // Delete button if custom profile
                        val isDefault = profile.id == "work" || profile.id == "weekend" || profile.id == "mindful"
                        if (!isDefault && !isActive) {
                            IconButton(
                                onClick = { viewModel.deleteProfile(profile.id) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = viewModel.translate("Delete"),
                                    tint = Color(0xFFEF4444).copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }

                // Add Custom Profile Form
                if (showAddForm) {
                    HorizontalDivider(color = SleekBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
                    
                    Text(
                        text = "Add Custom Profile",
                        fontWeight = FontWeight.Bold,
                        color = SleekActiveText,
                        fontSize = 14.sp
                    )
                    
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = { Text("Profile Name (e.g. Work Out)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SleekPrimary,
                            unfocusedBorderColor = SleekBorder,
                            focusedLabelColor = SleekPrimary
                        ),
                        singleLine = true
                    )

                    // Sunrise Offset slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Sunrise Offset: ${sunriseOffset.toInt()}m",
                                fontSize = 12.sp,
                                color = SleekActiveText
                            )
                            Text(
                                text = if (sunriseOffset.toInt() < 0) "Before Sun" else "After Sun",
                                fontSize = 11.sp,
                                color = SleekSecondary
                            )
                        }
                        Slider(
                            value = sunriseOffset,
                            onValueChange = { sunriseOffset = it },
                            valueRange = -60f..60f,
                            steps = 7 // 15m steps
                        )
                    }

                    // Sunset Offset slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Sunset Offset: ${sunsetOffset.toInt()}m",
                                fontSize = 12.sp,
                                color = SleekActiveText
                            )
                            Text(
                                text = if (sunsetOffset.toInt() < 0) "Before Sun" else "After Sun",
                                fontSize = 11.sp,
                                color = SleekSecondary
                            )
                        }
                        Slider(
                            value = sunsetOffset,
                            onValueChange = { sunsetOffset = it },
                            valueRange = -60f..60f,
                            steps = 7
                        )
                    }

                    // Vibration pattern selector
                    Column {
                        Text(
                            text = viewModel.translate("Vibration Pattern"),
                            fontSize = 12.sp,
                            color = SleekActiveText,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        val patterns = listOf("Steady", "Heartbeat", "Siren", "Quick Pulses")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            patterns.forEach { pat ->
                                val isPatSelected = selectedPattern == pat
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isPatSelected) SleekPrimary else SleekBorder.copy(alpha = 0.4f))
                                        .clickable { selectedPattern = pat }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = pat.split(" ").first(),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isPatSelected) Color.White else SleekMutedText
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showAddForm = false },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel", color = SleekSecondary)
                        }
                        
                        Button(
                            onClick = {
                                if (profileName.isNotBlank()) {
                                    val generatedId = "custom_${System.currentTimeMillis()}"
                                    val newProfile = AlarmProfile(
                                        id = generatedId,
                                        name = profileName,
                                        sunriseOffset = sunriseOffset.toInt(),
                                        sunsetOffset = sunsetOffset.toInt(),
                                        vibrationPattern = selectedPattern
                                    )
                                    viewModel.saveProfile(newProfile)
                                    showAddForm = false
                                    profileName = ""
                                    sunriseOffset = 0f
                                    sunsetOffset = 0f
                                    selectedPattern = "Steady"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save Profile", color = Color.White)
                        }
                    }
                } else {
                    Button(
                        onClick = { showAddForm = true },
                        colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary.copy(alpha = 0.15f)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = SleekPrimary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Create Custom Profile", color = SleekPrimary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun ResetConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    title: String,
    message: String
) {
    var text by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Type 'RESET' to confirm") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (text == "RESET") {
                        onConfirm()
                        onDismiss()
                    }
                },
                enabled = text == "RESET",
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
            ) {
                Text("Confirm Reset")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AdvancedTab(viewModel: AlarmViewModel, onNavigateToPrivacyPolicy: () -> Unit, onNavigateToTermsConditions: () -> Unit) {
    var showResetDialog by remember { mutableStateOf<String?>(null) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // 4. FEATURE TOGGLES
        Text(
            text = viewModel.translate("App Management").uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = SleekSecondary,
            letterSpacing = 1.2.sp
        )
        
        Button(
            onClick = { showResetDialog = "reset" },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = SleekSecondary)
        ) {
            Text("Reset App Settings", color = Color.White)
        }
        
        Button(
            onClick = { showResetDialog = "clear" },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
        ) {
            Text("Clear All Data", color = Color.White)
        }

        if (showResetDialog != null) {
            ResetConfirmationDialog(
                onDismiss = { showResetDialog = null },
                onConfirm = {
                    if (showResetDialog == "reset") viewModel.resetAppSettings()
                    else viewModel.clearAllData()
                },
                title = if (showResetDialog == "reset") "Reset Settings?" else "Clear All Data?",
                message = if (showResetDialog == "reset") "This will revert your app settings to default. This action cannot be undone." else "This will permanently remove all alarms, travel alarms, and reset location data. This action cannot be undone."
            )
        }

        Text(
            text = viewModel.translate("Feature Management").uppercase(),
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
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val isWeatherEnabled by viewModel.isWeatherEnabled.collectAsState()
                val isSolarEnabled by viewModel.isSolarTrendsEnabled.collectAsState()
                val isTravelEnabled by viewModel.isTravelEnabled.collectAsState()
                
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(viewModel.translate("Weather/AQI Data"), color = SleekActiveText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Switch(checked = isWeatherEnabled, onCheckedChange = { viewModel.setFeatureEnabled("feature_weather", it) }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = SleekPrimary))
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(viewModel.translate("Solar Trends"), color = SleekActiveText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Switch(checked = isSolarEnabled, onCheckedChange = { viewModel.setFeatureEnabled("feature_solar_trends", it) }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = SleekPrimary))
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(viewModel.translate("Travel Alarms"), color = SleekActiveText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    val isTravelEnabled by viewModel.isTravelEnabled.collectAsState()
                    val isTrackingActive by viewModel.isTravelTrackingActive.collectAsState()
                    var showTravelDisableDialog by remember { mutableStateOf(false) }

                    Switch(
                        checked = isTravelEnabled,
                        onCheckedChange = { isEnabled ->
                            if (!isEnabled && isTrackingActive) {
                                showTravelDisableDialog = true
                            } else {
                                viewModel.setFeatureEnabled("feature_travel", isEnabled)
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = SleekPrimary)
                    )

                    if (showTravelDisableDialog) {
                        AlertDialog(
                            onDismissRequest = { showTravelDisableDialog = false },
                            title = { Text("Disable Travel Alarms?") },
                            text = { Text("A travel journey is currently active. Disabling this feature will stop tracking and delete your active travel journey progress. This cannot be undone.") },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        viewModel.stopTravelTrackingAndDisableFeature()
                                        showTravelDisableDialog = false
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                                ) {
                                    Text("Disable & Delete")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showTravelDisableDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(viewModel.translate("Location Services"), color = SleekActiveText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    val isLocationEnabled by viewModel.isLocationEnabled.collectAsState()
                    Switch(checked = isLocationEnabled, onCheckedChange = { viewModel.setFeatureEnabled("feature_location", it) }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = SleekPrimary))
                }
                
                val isLocationEnabled by viewModel.isLocationEnabled.collectAsState()
                val context = LocalContext.current
                val hasLocationPermission = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                
                if (isLocationEnabled && !hasLocationPermission) {
                    Text(viewModel.translate("Location access is enabled in the app, but permission is denied in system settings. Please grant location access."), color = Color(0xFFEF4444), fontSize = 12.sp)
                } else if (!isLocationEnabled) {
                    Text(viewModel.translate("Location Services disabled. Sunrise/Sunset features require location."), color = Color(0xFFEF4444), fontSize = 12.sp)
                }
            }
        }

        // 4b. OLA MAPS API KEY (dynamic admin config — powers the Travel map, route & search)
        Text(
            text = viewModel.translate("Maps & Location Provider").uppercase(),
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
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Map, contentDescription = null, tint = SleekPrimary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(viewModel.translate("Ola Maps API Key"), fontWeight = FontWeight.Bold, color = SleekActiveText, fontSize = 15.sp)
                }
                Text(
                    text = viewModel.translate("Enter your Ola Maps API key to enable the live travel map, route drawing and place search. Get a key from maps.olakrutrim.com."),
                    fontSize = 12.sp,
                    color = SleekMutedText
                )

                val savedOlaKey by viewModel.olaMapsApiKey.collectAsState()
                var olaKeyInput by remember(savedOlaKey) { mutableStateOf(savedOlaKey) }
                var olaKeyVisible by remember { mutableStateOf(false) }

                OutlinedTextField(
                    value = olaKeyInput,
                    onValueChange = { olaKeyInput = it },
                    label = { Text(viewModel.translate("Ola Maps API Key")) },
                    singleLine = true,
                    visualTransformation = if (olaKeyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { olaKeyVisible = !olaKeyVisible }) {
                            Icon(
                                imageVector = if (olaKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (olaKeyVisible) "Hide" else "Show",
                                tint = SleekMutedText
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("ola_api_key_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SleekPrimary,
                        unfocusedBorderColor = SleekBorder,
                        focusedTextColor = SleekActiveText,
                        unfocusedTextColor = SleekActiveText,
                        cursorColor = SleekPrimary
                    )
                )

                val keyContext = LocalContext.current
                Button(
                    onClick = {
                        viewModel.setOlaMapsApiKey(olaKeyInput)
                        android.widget.Toast.makeText(
                            keyContext,
                            viewModel.translate(if (olaKeyInput.isBlank()) "Ola Maps key cleared" else "Ola Maps key saved"),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("ola_api_key_save"),
                    colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
                ) {
                    Text(viewModel.translate("Save Key"), color = Color.White)
                }

                if (savedOlaKey.isBlank()) {
                    Text(
                        text = viewModel.translate("Map disabled — key required. The app will keep using the basic city search until a key is added."),
                        fontSize = 11.sp,
                        color = Color(0xFFF59E0B)
                    )
                } else {
                    Text(
                        text = viewModel.translate("Ola Maps active ✓"),
                        fontSize = 11.sp,
                        color = Color(0xFF10B981)
                    )
                }
            }
        }

        // 5. WAKELOCK / PERMISSIONS COMPREHENSIVE GUIDELINES
        Text(
            text = viewModel.translate("ALARM SYSTEM RELIABILITY GUIDES"),
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
                    Text(viewModel.translate("Battery Optimizations Exclusions"), fontWeight = FontWeight.Bold, color = Color(0xFFEF4444), fontSize = 15.sp)
                }
                Text(
                    text = viewModel.translate("To guarantee the alarm triggers precisely on-time when the physical screen is off, newer Android versions require excluding the app from system-level battery optimizations."),
                    fontSize = 12.sp,
                    color = SleekMutedText
                )
                
                val context = LocalContext.current
                Button(
                    onClick = {
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // Fallback to main settings if specific action fails
                            val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                            context.startActivity(intent)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(viewModel.translate("Open Battery Settings"), color = Color.White)
                }

                Text(
                    text = viewModel.translate("Go to system App Info -> Battery -> and select 'Unrestricted' for completely uninterrupted wake alarm service."),
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
                    Text(viewModel.translate("System Exact Alarm Permission"), fontWeight = FontWeight.Bold, color = SleekActiveText, fontSize = 15.sp)
                }
                Text(
                    text = viewModel.translate("Solari uses System Alarm Clock info APIs which list upcoming alerts on your lockscreen and bypass Silent / Do Not Disturb boundaries."),
                    fontSize = 12.sp,
                    color = SleekMutedText
                )
                Text(
                    text = viewModel.translate("If scheduled warnings seem deactivated, ensure 'Alarms & Reminders' permission is granted in the device settings panel."),
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
                    text = viewModel.translate("EARLY ROVER"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = SleekSolarAccent,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = viewModel.translate("Version 1.0.0 (Concept Edition)"),
                    fontSize = 11.sp,
                    color = SleekMutedText
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = viewModel.translate("Developed using modern Jetpack Compose, Room reactive databases, Alarm Clock APIs, and hardware-accelerated OpenGL ES 2.0 visualization."),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = SleekMutedText,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onNavigateToPrivacyPolicy) {
                    Text(viewModel.translate("Privacy Policy"), color = SleekPrimary)
                }
                TextButton(onClick = onNavigateToTermsConditions) {
                    Text(viewModel.translate("Terms & Conditions"), color = SleekPrimary)
                }
            }
        }
    }
}
