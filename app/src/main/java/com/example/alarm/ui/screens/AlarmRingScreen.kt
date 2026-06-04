package com.example.alarm.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun AlarmRingScreen(
    title: String,
    type: String,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    snoozeEnabled: Boolean = true,
    translate: (String) -> String = { it }
) {
    // Breathing/Pulse animation for active ring
    val infiniteTransition = rememberInfiniteTransition(label = "Solar Flare Glow")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    // Vibrant background gradients reflecting our Sleek theme
    val (primaryGlow, bgColors) = when (type) {
        "SUNRISE" -> Pair(
            SleekSolarAccent,
            listOf(Color(0xFF451A03), Color(0xFF1C0A00), SleekBackground)
        )
        "SUNSET" -> Pair(
            SleekSecondary,
            listOf(Color(0xFF2E1065), Color(0xFF1E1B4B), SleekBackground)
        )
        "TRAVEL" -> Pair(
            Color(0xFF10B981), // Emerald arrival green
            listOf(Color(0xFF022C22), Color(0xFF02141C), SleekBackground)
        )
        else -> Pair(
            SleekPrimary,
            listOf(Color(0xFF1E1B4B), Color(0xFF0F172A), SleekBackground)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(bgColors))
            .testTag("alarm_ring_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            
            // 1. BREATHING GRAPHIC PULSING RING ORB (SOLAR ARCHITECT)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(260.dp)
            ) {
                // Glow Pulse waves
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .scale(pulseScale)
                        .background(primaryGlow.copy(alpha = 0.12f), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .scale(pulseScale * 0.9f)
                        .background(primaryGlow.copy(alpha = 0.22f), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .shadow(12.dp, CircleShape)
                        .background(primaryGlow, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    val icon = when (type) {
                        "SUNRISE" -> Icons.Default.WbSunny
                        "SUNSET" -> Icons.Default.WbTwilight
                        "TRAVEL" -> Icons.Default.Explore
                        else -> Icons.Default.AccessTime
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = "Alert active profile icon",
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // 2. ALARM TITLE INFO
            Text(
                text = if (type == "TRAVEL") translate("Destination Reached") else translate("Alarm Triggered"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                color = primaryGlow,
                letterSpacing = 4.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = title.ifEmpty { translate("Wake Up Call") },
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            val eventSummary = when (type) {
                "SUNRISE" -> translate("Solar Sunrise Synchronized Alarm")
                "SUNSET" -> translate("Solar Sunset Synchronized Alarm")
                "TRAVEL" -> translate("Wayfarer Transit Arrival Security")
                else -> translate("Standard Trigger Clock")
            }

            Text(
                text = eventSummary,
                fontSize = 14.sp,
                color = SleekMutedText,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.weight(1f))

            // 3. ACTION TOUCH BUTTONS (LARGE & VISUALLY PROMINENT FOR EASY GRAB)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // DISMISS CALL
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .shadow(16.dp, RoundedCornerShape(20.dp))
                        .testTag("dismiss_ring_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (type == "TRAVEL") Color(0xFF10B981) else Color(0xFFEF4444) // Emergeny green for travel, alert red for clocks
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = if (type == "TRAVEL") translate("ARRIVED - DISMISS ALARM") else translate("DISMISS ALARM"),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 1.sp
                    )
                }

                // SNOOZE CALL (OUTLINED SLEEK BUTTON - HIDDEN IN TRAVEL MODE OR WHEN SNOOZE DISABLED)
                if (type != "TRAVEL" && snoozeEnabled) {
                    OutlinedButton(
                        onClick = onSnooze,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("snooze_ring_button"),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.4f), Color.White.copy(alpha = 0.15f)))
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Snooze, contentDescription = null, tint = SleekMutedText)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = translate("SNOOZE WAKE"),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
