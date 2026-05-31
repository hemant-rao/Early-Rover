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
import com.example.alarm.viewmodel.AlarmViewModel
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSettingsScreen(
    viewModel: AlarmViewModel,
    onNavigateBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    
    val lat by viewModel.latitude.collectAsStateWithLifecycle()
    val lng by viewModel.longitude.collectAsStateWithLifecycle()
    val locName by viewModel.locationName.collectAsStateWithLifecycle()
    val tzOffset by viewModel.timezoneOffset.collectAsStateWithLifecycle()

    var showAdvancedDetails by remember { mutableStateOf(false) }

    // Location Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocation = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineLocation || coarseLocation) {
            viewModel.triggerAutoLocationDetect()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("location_settings_screen"),
        containerColor = SleekBackground,
        topBar = {
            TopAppBar(
                title = { Text(viewModel.translate("Location Coordinates"), fontWeight = FontWeight.Bold, color = SleekActiveText) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("loc_back_button")) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = viewModel.translate("Go back"), tint = SleekActiveText)
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            
            // 1. SIMPLIFIED ACTIVE SELECTION DETAILS (Super Friendly for Seniors & Teenagers)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, SleekPrimary.copy(alpha = 0.5f)), shape = RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SleekCardBg)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(SleekPrimary.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = SleekPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = viewModel.translate("Active City:").uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = SleekSecondary,
                                    letterSpacing = 1.2.sp
                                )
                            )
                            Text(
                                text = locName.ifEmpty { "Reykjavík, IS" },
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    color = SleekActiveText
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Optional coordinates hide-show button to prevent confusing elderly/young users
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAdvancedDetails = !showAdvancedDetails }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (showAdvancedDetails) viewModel.translate("Hide details") else viewModel.translate("Show coordinates details"),
                            color = SleekPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (showAdvancedDetails) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = SleekPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    AnimatedVisibility(
                        visible = showAdvancedDetails,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(12.dp))
                            Divider(color = SleekBorder.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(viewModel.translate("Latitude"), fontSize = 11.sp, color = SleekMutedText)
                                    Text(String.format("%.4f°", lat), fontWeight = FontWeight.Bold, color = SleekActiveText, fontSize = 14.sp)
                                }
                                Column {
                                    Text(viewModel.translate("Longitude"), fontSize = 11.sp, color = SleekMutedText)
                                    Text(String.format("%.4f°", lng), fontWeight = FontWeight.Bold, color = SleekActiveText, fontSize = 14.sp)
                                }
                                Column {
                                    Text(viewModel.translate("Timezone"), fontSize = 11.sp, color = SleekMutedText)
                                    val tzString = if (tzOffset >= 0) "UTC +$tzOffset" else "UTC $tzOffset"
                                    Text(tzString, fontWeight = FontWeight.Bold, color = SleekActiveText, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }

            // 2. AUTO DETECT LOCATION (SIMPLE LABELED)
            Button(
                onClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(12.dp, RoundedCornerShape(16.dp))
                    .testTag("gps_detect_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(SleekPrimary, SleekSecondary)))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.GpsFixed, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = viewModel.translate("Use My Current Phone Location (GPS)"),
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 15.sp
                        )
                    }
                }
            }

            Divider(color = SleekBorder.copy(alpha = 0.5f))

            // 3. SEARCH MANUAL CITY LOOKUP LIST
            Text(
                text = viewModel.translate("Manual Search Lookup"),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = SleekSecondary,
                letterSpacing = 1.sp
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { q ->
                    searchQuery = q
                    viewModel.searchLocationQuery(q)
                },
                placeholder = { Text(viewModel.translate("Where are you? Type city name here..."), color = SleekMutedText, fontSize = 14.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = SleekActiveText,
                    unfocusedTextColor = SleekActiveText,
                    focusedBorderColor = SleekPrimary,
                    unfocusedBorderColor = SleekBorder,
                    focusedContainerColor = SleekCardBg,
                    unfocusedContainerColor = SleekCardBg,
                    focusedLeadingIconColor = SleekPrimary,
                    unfocusedLeadingIconColor = SleekMutedText
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("location_search_input"),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                shape = RoundedCornerShape(16.dp),
                maxLines = 1
            )

            // 4. DISPLAY SEARCH ENTRIES
            if (searchQuery.isNotEmpty()) {
                Text(
                    text = "${searchResults.size} ${viewModel.translate("matches found")}",
                    fontSize = 11.sp,
                    color = SleekMutedText,
                    fontWeight = FontWeight.Bold
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(searchResults) { city ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("city_result_${city.name}")
                                .clickable {
                                    viewModel.setManualCitySelection(city)
                                    searchQuery = "" // close list
                                }
                                .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = SleekCardBg)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(SleekPrimary.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.LocationCity, contentDescription = null, tint = SleekSecondary, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text("${city.name}, ${city.country}", fontWeight = FontWeight.Bold, color = SleekActiveText)
                                    Text("Lat: ${city.latitude} | Lng: ${city.longitude} | GMT ${city.timezoneOffset}", fontSize = 11.sp, color = SleekMutedText)
                                }
                            }
                        }
                    }
                }
            } else {
                // Friendly tips banner using conversational language
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = SleekCardBg.copy(alpha = 0.5f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.WbSunny, contentDescription = null, tint = SleekSecondary.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = viewModel.translate("Why set location?"),
                                fontWeight = FontWeight.Bold,
                                color = SleekActiveText,
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = viewModel.translate("We calculate the exact sunrise and sunset times automatically for your location, even without internet!"),
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
    }
}
