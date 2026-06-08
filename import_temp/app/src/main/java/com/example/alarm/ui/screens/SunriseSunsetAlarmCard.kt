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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Quick-pick offsets. These MUST be a subset of the editor's
                // (AddEditAlarmScreen) offset list so a value chosen here is
                // always reachable/round-trippable in the full editor.
                // Editor list: listOf(-30, -15, -10, -5, 0, 5, 10, 15, 30)
                val chips = listOf("0" to 0, "-15m" to -15, "-30m" to -30)
                chips.forEach { (label, offsetValue) ->
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
                // If the alarm's offset isn't one of the quick-pick chips (e.g. it
                // was set to -10/-5/+10 in the editor), surface a neutral "custom"
                // indicator instead of silently leaving every chip unhighlighted.
                if (chips.none { it.second == currentOffset }) {
                    Text(
                        text = "${currentOffset}m",
                        fontSize = 10.sp,
                        color = SleekPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
