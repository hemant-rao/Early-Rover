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
fun TermsConditionsScreen(onNavigateBack: () -> Unit) {
    Scaffold(
        containerColor = SleekBackground,
        topBar = {
            TopAppBar(
                title = { Text("Terms & Conditions", fontWeight = FontWeight.Bold, color = SleekActiveText) },
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
            Text("By using Early Rover, you agree to these terms and conditions. The app is provided on an 'as-is' basis. We are not responsible for alarm failures due to device-level limitations or battery optimizations.", fontSize = 16.sp, color = SleekActiveText)
            Text("You agree to use the application safely and responsibly, particularly when using location-aware features while traveling.", fontSize = 16.sp, color = SleekActiveText)
        }
    }
}
