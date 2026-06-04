package com.example.alarm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.alarm.viewmodel.AlarmViewModel
import com.example.ui.theme.*
import androidx.compose.ui.draw.clip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageCitiesScreen(
    viewModel: AlarmViewModel,
    onNavigateBack: () -> Unit
) {
    val savedCities by viewModel.savedCities.collectAsState()
    val locationName by viewModel.locationName.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Cities", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(savedCities) { index, city ->
                val isCurrentLocation = city.name.equals(locationName, ignoreCase = true)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !isCurrentLocation) {
                            viewModel.setManualCitySelection(city)
                            onNavigateBack()
                        }
                        .background(SleekCardBg),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(city.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(city.country, fontSize = 12.sp, color = SleekMutedText)
                        }
                        if (isCurrentLocation) {
                            Text("Current", fontSize = 12.sp, color = SleekPrimary)
                        } else {
                            IconButton(onClick = { viewModel.deleteSavedCity(city) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete City", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
