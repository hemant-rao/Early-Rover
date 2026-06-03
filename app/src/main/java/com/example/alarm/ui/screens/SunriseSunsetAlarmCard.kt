package com.example.alarm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun SunriseSunsetAlarmCard(
    modifier: Modifier = Modifier,
    title: String,
    time: String,
    icon: ImageVector,
    tint: Color,
    isActive: Boolean,
    currentOffset: Int,
    onAlarmToggle: (Boolean) -> Unit,
    onOffsetSelected: (Int) -> Unit,
    onCardClick: () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SleekCardBg)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.clickable(onClick = onCardClick),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SleekActiveText)
                }
                Checkbox(
                    checked = isActive, 
                    onCheckedChange = onAlarmToggle, 
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Text(
                text = time, 
                fontSize = 18.sp, 
                fontWeight = FontWeight.ExtraBold,
                color = if (isActive) SleekActiveText else SleekMutedText,
                modifier = Modifier.clickable(onClick = onCardClick)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf("-15m", "-30m", "-1h").forEach { label ->
                    val offsetValue = when(label) {
                        "-15m" -> -15
                        "-30m" -> -30
                        "-1h" -> -60
                        else -> 0
                    }
                    val isSelected = currentOffset == offsetValue
                    TextButton(
                        onClick = { onOffsetSelected(offsetValue) },
                        contentPadding = PaddingValues(4.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text(
                            text = label, 
                            fontSize = 10.sp, 
                            color = if (isSelected) SleekPrimary else SleekMutedText,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
