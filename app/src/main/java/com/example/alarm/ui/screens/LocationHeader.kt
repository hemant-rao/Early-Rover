package com.example.alarm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.alarm.location.CityInfo
import com.example.alarm.viewmodel.AlarmViewModel
import com.example.ui.theme.SleekActiveText
import com.example.ui.theme.SleekMutedText
import com.example.ui.theme.SleekPrimary
import com.example.ui.theme.SleekSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch

@Composable
fun LocationHeader(
    isDetecting: Boolean,
    savedCities: List<CityInfo>,
    locationName: String,
    activeCityIndex: Int,
    pagerState: PagerState,
    headerScope: CoroutineScope,
    viewModel: AlarmViewModel,
    onAddLocationClick: () -> Unit,
    onManageCitiesClick: () -> Unit,
    hasLocationPermission: Boolean,
    onRequestLocationPermission: () -> Unit
) {
    if (!hasLocationPermission) {
        Row(
            verticalAlignment = Alignment.CenterVertically,                
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onRequestLocationPermission() }
                .padding(vertical = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Location Needed",
                tint = Color(0xFFEF4444),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Location access needed - Tap to enable",                
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFEF4444)
                ),
                modifier = Modifier.weight(1f)
            )
        }
        return
    }

    if (isDetecting) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.LocationOn, null, tint = SleekSecondary, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Detecting location...", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = SleekActiveText), modifier = Modifier.weight(1f))
        }
    } else if (savedCities.isEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = SleekSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = locationName.ifEmpty { "Reykjavík, IS" },
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = SleekActiveText
                ),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onAddLocationClick() }
            )
            IconButton(
                onClick = onAddLocationClick,
                modifier = Modifier.size(28.dp).testTag("add_location_header_button")
            ) {
                Icon(
                    imageVector = Icons.Default.AddCircle,
                    contentDescription = "Add New Location",
                    tint = SleekPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    } else {
        LaunchedEffect(savedCities.size) {
            if (savedCities.isNotEmpty() && pagerState.currentPage > savedCities.lastIndex) {
                pagerState.scrollToPage(savedCities.lastIndex)
            }
        }
        LaunchedEffect(activeCityIndex, savedCities.size) {
            if (activeCityIndex in savedCities.indices &&
                pagerState.currentPage != activeCityIndex
            ) {
                pagerState.animateScrollToPage(activeCityIndex)
            }
        }
        LaunchedEffect(pagerState, savedCities) {
            snapshotFlow { pagerState.currentPage }
                .drop(1)
                .collect { page ->
                    val c = savedCities.getOrNull(page) ?: return@collect
                    val matches = locationName.equals(c.name, true)
                    if (!matches) viewModel.setManualCitySelection(c)
                }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = SleekSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                val city = savedCities.getOrNull(page) ?: return@HorizontalPager
                Text(
                    text = city.name,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = SleekActiveText
                    ),
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAddLocationClick() }
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // City indicators
                savedCities.indices.forEach { i ->
                    val selected = i == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .size(if (selected) 8.dp else 6.dp)
                            .background(
                                if (selected) SleekPrimary
                                else SleekMutedText.copy(alpha = 0.2f),
                                CircleShape
                            )
                            .clickable {
                                headerScope.launch { pagerState.animateScrollToPage(i) }
                            }
                    )
                }

                IconButton(
                    onClick = onAddLocationClick,
                    modifier = Modifier.size(28.dp).testTag("add_location_header_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddCircle,
                        contentDescription = "Add New Location",
                        tint = SleekPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                IconButton(
                    onClick = onManageCitiesClick,
                    modifier = Modifier.size(28.dp).testTag("manage_cities_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Manage Cities",
                        tint = SleekMutedText,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
