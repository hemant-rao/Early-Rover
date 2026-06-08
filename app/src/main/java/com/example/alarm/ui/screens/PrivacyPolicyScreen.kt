package com.example.alarm.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.SleekActiveText
import com.example.ui.theme.SleekBackground
import com.example.ui.theme.SleekMutedText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(onNavigateBack: () -> Unit) {
    Scaffold(
        containerColor = SleekBackground,
        topBar = {
            TopAppBar(
                title = { Text("Privacy Policy", fontWeight = FontWeight.Bold, color = SleekActiveText) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back", tint = SleekActiveText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Last updated: June 5, 2026", fontSize = 14.sp, color = SleekMutedText)
            Text("Your privacy is important to us. Early Rover uses your location data exclusively to provide personalized, astronomical alarm features such as sunrise and sunset tracking.", fontSize = 16.sp, color = SleekActiveText)
            Text("We do not sell, trade, or share your personal data with third parties. All location-based calculations are performed locally on your device or via secure, anonymous API requests.", fontSize = 16.sp, color = SleekActiveText)
        }
    }
}
