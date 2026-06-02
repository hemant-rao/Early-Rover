package com.example.alarm.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.alarm.data.Alarm
import com.example.alarm.viewmodel.AlarmViewModel
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditAlarmScreen(
    viewModel: AlarmViewModel,
    alarmId: Int?, // if null, adding. if non-null, editing.
    onNavigateBack: () -> Unit
) {
    val editingState by viewModel.editingAlarm.collectAsState()
    val allAlarms by viewModel.allAlarms.collectAsState()
    val sunriseTime by viewModel.sunriseTime.collectAsState()
    val sunsetTime by viewModel.sunsetTime.collectAsState()

    // Initialize scratchpad from Database if editing. Keyed on allAlarms too so that on a
    // cold start (where the Room-backed list is still empty) it re-resolves once data loads
    // instead of wrongly falling through to a blank CUSTOM scratchpad.
    LaunchedEffect(alarmId, allAlarms) {
        if (alarmId != null) {
            val existing = allAlarms.find { it.id == alarmId }
            when {
                existing != null -> viewModel.editingAlarm.value = existing
                // List has loaded but this id is genuinely gone (e.g. deleted elsewhere):
                // clear any stale scratchpad left over from a prior screen and leave.
                allAlarms.isNotEmpty() -> {
                    viewModel.editingAlarm.value = null
                    onNavigateBack()
                }
                // else: list not loaded yet — wait for the next emission.
            }
        } else if (viewModel.editingAlarm.value == null) {
            viewModel.startNewAlarmScratchpad("CUSTOM")
        }
    }

    val alarm = editingState ?: return

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("add_edit_alarm_screen"),
        containerColor = SleekBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (alarmId == null) viewModel.translate("Schedule Alarm") else viewModel.translate("Edit Alarm"),
                        fontWeight = FontWeight.Bold,
                        color = SleekActiveText
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("alarm_back_button")
                    ) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = viewModel.translate("Go back"), tint = SleekActiveText)
                    }
                },
                actions = {
                    if (alarmId != null) {
                        IconButton(
                            onClick = {
                                viewModel.deleteAlarm(alarm)
                                onNavigateBack()
                            },
                            modifier = Modifier.testTag("delete_alarm_action")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = viewModel.translate("Delete"),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
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
            
            // 1. CHOOSE ALARM TYPE HEADER (SLEEK CUSTOM CARD)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SleekCardBg)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val (icon, tint, label, desc) = when (alarm.alarmType) {
                        "SUNRISE" -> Quad(Icons.Default.WbSunny, SleekSolarAccent, viewModel.translate("Sunrise Alarm"), viewModel.translate("Fires relative to today's local sunrise."))
                        "SUNSET" -> Quad(Icons.Default.WbTwilight, SleekSecondary, viewModel.translate("Sunset Alarm"), viewModel.translate("Fires relative to today's local sunset."))
                        else -> Quad(Icons.Default.AccessTime, SleekPrimary, viewModel.translate("Standard Clock Alarm"), viewModel.translate("Fires at an exact manually set clock time."))
                    }

                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(tint.copy(alpha = 0.15f), CircleShape)
                            .border(BorderStroke(1.dp, tint.copy(alpha = 0.3f)), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = label,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = SleekActiveText
                        )
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = SleekMutedText
                        )
                    }
                }
            }

            // 2. TIME SELECTOR OR EVENT OFFSET SELECTOR
            if (alarm.alarmType == "CUSTOM") {
                Text(
                    text = viewModel.translate("Configure Clock Time"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = SleekSecondary,
                    letterSpacing = 1.sp
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = SleekCardBg)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiStyleTimeWheelSelector(
                            value = alarm.hour,
                            limit = 24,
                            label = viewModel.translate("Hour"),
                            onValueChange = { h ->
                                viewModel.editingAlarm.value = alarm.copy(hour = h)
                            }
                        )
                        
                        Text(
                            text = ":",
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        MiStyleTimeWheelSelector(
                            value = alarm.minute,
                            limit = 60,
                            label = viewModel.translate("Minute"),
                            onValueChange = { m ->
                                viewModel.editingAlarm.value = alarm.copy(minute = m)
                            }
                        )
                    }
                }
            } else {
                Text(
                    text = viewModel.translate("Trigger Offset"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = SleekSecondary,
                    letterSpacing = 1.sp
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = SleekCardBg)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        
                        // User Request Checkbox: Trigger Exactly during natural event
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    val wasChecked = alarm.ringAtExactAlso || alarm.offsetMinutes == 0
                                    if (wasChecked) {
                                        // Turn off exact time. If offset was 0, default to some offset.
                                        viewModel.editingAlarm.value = if (alarm.offsetMinutes == 0) {
                                            alarm.copy(offsetMinutes = -15, ringAtExactAlso = false)
                                        } else {
                                            alarm.copy(ringAtExactAlso = false)
                                        }
                                    } else {
                                        // Turn on exact time. If they didn't have an offset, it's just exact.
                                        viewModel.editingAlarm.value = alarm.copy(ringAtExactAlso = true)
                                    }
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            val isExactChecked = alarm.ringAtExactAlso || alarm.offsetMinutes == 0
                            Checkbox(
                                checked = isExactChecked,
                                onCheckedChange = { checked ->
                                    if (!checked) {
                                        viewModel.editingAlarm.value = if (alarm.offsetMinutes == 0) {
                                            alarm.copy(offsetMinutes = -15, ringAtExactAlso = false)
                                        } else {
                                            alarm.copy(ringAtExactAlso = false)
                                        }
                                    } else {
                                        viewModel.editingAlarm.value = alarm.copy(ringAtExactAlso = true)
                                    }
                                },
                                colors = CheckboxDefaults.colors(checkedColor = SleekPrimary)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = viewModel.translate("Trigger exactly during event (Sunrise/Sunset)"),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = SleekActiveText
                                )
                                Text(
                                    text = viewModel.translate("Fires at the precise moment of astronomical rise/set"),
                                    fontSize = 12.sp,
                                    color = SleekMutedText
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = viewModel.translate("Also trigger at offset time"),
                            fontSize = 13.sp,
                            color = SleekMutedText
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Offset choices
                        val offsets = listOf(-30, -15, -10, -5, 0, 5, 10, 15, 30) // 0 implies ONLY the exact time
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            offsets.forEach { offset ->
                                val selected = alarm.offsetMinutes == offset
                                val labelText = when {
                                    offset < 0 -> "${-offset}m ${viewModel.translate("Before")}"
                                    offset > 0 -> "${offset}m ${viewModel.translate("After")}"
                                    else -> viewModel.translate("No Offset")
                                }
                                
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (selected) SleekPrimary else SleekBorder.copy(alpha = 0.5f)
                                        )
                                        .clickable {
                                            viewModel.editingAlarm.value = alarm.copy(offsetMinutes = offset)
                                        }
                                        .border(
                                            BorderStroke(
                                                width = 1.dp,
                                                color = if (selected) SleekSecondary else Color.Transparent
                                            ),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = labelText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) Color.White else SleekMutedText
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Text summary representation — preview the ACTUAL fire time, i.e. the
                        // sun event + chosen offset (this mirrors how saveEditingAlarm computes it),
                        // so picking an offset chip updates the shown time.
                        val previewBase = when (alarm.alarmType) {
                            "SUNRISE" -> sunriseTime.plusMinutes(alarm.offsetMinutes.toLong())
                            "SUNSET" -> sunsetTime.plusMinutes(alarm.offsetMinutes.toLong())
                            else -> java.time.LocalTime.of(alarm.hour.coerceIn(0, 23), alarm.minute.coerceIn(0, 59))
                        }
                        val hour12 = if (previewBase.hour % 12 == 0) 12 else previewBase.hour % 12
                        val ampm = if (previewBase.hour >= 12) viewModel.translate("PM") else viewModel.translate("AM")
                        val timeStr = String.format("%02d:%02d %s", hour12, previewBase.minute, ampm)
                        Text(
                            text = "${viewModel.translate("Based on location, triggers today at")} $timeStr",
                            fontSize = 12.sp,
                            color = SleekSolarAccent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 3. REPEAT SCHEDULE DAYS SELECTOR
            Text(
                text = viewModel.translate("Repeat Days"),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = SleekSecondary,
                letterSpacing = 1.sp
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SleekCardBg)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = viewModel.translate("Fires weekly during selected days"),
                        fontSize = 13.sp,
                        color = SleekMutedText
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    val activeDays = alarm.getRepeatDaysList()
                    val daysOfWeek = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        for (i in 1..7) {
                            val active = activeDays.contains(i)
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (active) SleekPrimary else SleekBorder.copy(alpha = 0.5f)
                                    )
                                    .clickable {
                                        val newList = if (active) {
                                            activeDays.filter { it != i }
                                        } else {
                                            activeDays + i
                                        }
                                        viewModel.editingAlarm.value = alarm.copy(
                                            repeatDays = newList.sorted().joinToString(",")
                                        )
                                    }
                                    .border(
                                        BorderStroke(
                                            width = 1.dp,
                                            color = if (active) SleekSecondary else Color.Transparent
                                        ),
                                        shape = CircleShape
                                    )
                                    .testTag("day_selector_$i"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = daysOfWeek[i - 1].substring(0, 1),
                                    color = if (active) Color.White else SleekMutedText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            // 4. CUSTOM LABEL INPUT
            Text(
                text = viewModel.translate("Alarm Identifier"),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = SleekSecondary,
                letterSpacing = 1.sp
            )

            OutlinedTextField(
                value = alarm.title,
                onValueChange = { text ->
                    viewModel.editingAlarm.value = alarm.copy(title = text)
                },
                placeholder = { Text(viewModel.translate("e.g. Sunrise Tracker, Yoga Call..."), color = SleekMutedText, fontSize = 14.sp) },
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
                    .testTag("alarm_label_input"),
                shape = RoundedCornerShape(16.dp),
                maxLines = 1,
                leadingIcon = { Icon(Icons.Default.Label, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )

            // 4.5 ALARM TONE PICKER
            Text(
                text = viewModel.translate("Alarm Tone"),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = SleekSecondary,
                letterSpacing = 1.sp
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(24.dp))
                    .testTag("ringtone_picker_card"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SleekCardBg)
            ) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val ringtoneName = remember(alarm.ringtoneUri) {
                    getRingtoneName(context, alarm.ringtoneUri)
                }

                val ringtonePickerLauncher = rememberLauncherForActivityResult(
                    contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    onResult = { result ->
                        if (result.resultCode == android.app.Activity.RESULT_OK) {
                            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                            }
                            viewModel.editingAlarm.value = alarm.copy(ringtoneUri = uri?.toString() ?: "")
                        }
                    }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, viewModel.translate("Select Alarm Tone"))
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                if (alarm.ringtoneUri.isNotEmpty()) {
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(alarm.ringtoneUri))
                                }
                            }
                            ringtonePickerLauncher.launch(intent)
                        }
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(SleekPrimary.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = SleekPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = viewModel.translate("Ringtone"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = SleekActiveText
                        )
                        Text(
                            text = ringtoneName,
                            fontSize = 12.sp,
                            color = SleekMutedText
                        )
                    }
                    
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = "Select Ringtone",
                        tint = SleekMutedText,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // 5. SMART CONTROLS CARD
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SleekCardBg)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(viewModel.translate("Vibration"), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SleekActiveText)
                            Text(viewModel.translate("Vibrate during alarm trigger sequence"), fontSize = 12.sp, color = SleekMutedText)
                        }
                        Switch(
                            checked = alarm.vibrationEnabled,
                            onCheckedChange = { v ->
                                viewModel.editingAlarm.value = alarm.copy(vibrationEnabled = v)
                            },
                            modifier = Modifier.testTag("vibration_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SleekPrimary,
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = SleekBorder
                            )
                        )
                    }

                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SleekBorder.copy(alpha = 0.5f)))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(viewModel.translate("Snooze Awake"), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SleekActiveText)
                            Text("${viewModel.translate("Snooze pause duration")}: ${alarm.snoozeMinutes} ${viewModel.translate("mins")}", fontSize = 12.sp, color = SleekMutedText)
                        }
                        Switch(
                            checked = alarm.snoozeEnabled,
                            onCheckedChange = { s ->
                                viewModel.editingAlarm.value = alarm.copy(snoozeEnabled = s)
                            },
                            modifier = Modifier.testTag("snooze_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SleekPrimary,
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = SleekBorder
                            )
                        )
                    }

                    if (alarm.snoozeEnabled) {
                        // Quick presets so users can set a per-alarm snooze with one tap.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(5, 10, 15, 20).forEach { preset ->
                                val selected = alarm.snoozeMinutes == preset
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (selected) SleekPrimary else SleekBackground)
                                        .border(
                                            BorderStroke(0.5.dp, if (selected) SleekPrimary else SleekBorder),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            viewModel.editingAlarm.value = alarm.copy(snoozeMinutes = preset)
                                        }
                                        .padding(vertical = 8.dp)
                                        .testTag("snooze_preset_$preset"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$preset",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) Color.White else SleekMutedText
                                    )
                                }
                            }
                        }

                        Slider(
                            value = alarm.snoozeMinutes.toFloat(),
                            onValueChange = { value ->
                                viewModel.editingAlarm.value = alarm.copy(snoozeMinutes = value.toInt())
                            },
                            valueRange = 1f..30f,
                            // 28 steps => 30 discrete stops over 1..30, so every integer minute is selectable.
                            steps = 28,
                            modifier = Modifier.testTag("snooze_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = SleekPrimary,
                                inactiveTrackColor = SleekBorder
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 6. SAVE ACTION BUTTON WITH PREMIUM HORIZONTAL GRADIENT
            Button(
                onClick = {
                    viewModel.saveEditingAlarm()
                    onNavigateBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(12.dp, RoundedCornerShape(16.dp))
                    .testTag("save_alarm_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(SleekPrimary, SleekSecondary))),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = viewModel.translate("Save"), tint = Color.White)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = viewModel.translate("Confirm Schedule Settings"),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

// Xiaomi / Premium MI Style Wheel Selector Component (Scrollable)
@Composable
fun MiStyleTimeWheelSelector(
    value: Int,
    limit: Int,
    label: String,
    onValueChange: (Int) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(90.dp)
    ) {
        Text(
            text = label.uppercase(),
            fontSize = 11.sp,
            color = SleekSecondary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Pager for snapping and native-feeling scroll
        val pagerState = androidx.compose.foundation.pager.rememberPagerState(
            initialPage = value + (limit * 1000)
        ) { limit * 2000 }
        
        LaunchedEffect(pagerState.isScrollInProgress) {
            if (!pagerState.isScrollInProgress) {
                val currentPageValue = pagerState.currentPage % limit
                if (currentPageValue != value) {
                    onValueChange(currentPageValue)
                }
            }
        }
        
        LaunchedEffect(value) {
            if (pagerState.currentPage % limit != value) {
                // If it's changed from outside, snap it there
                pagerState.scrollToPage(pagerState.currentPage - (pagerState.currentPage % limit) + value)
            }
        }
        
        Box(
            modifier = Modifier
                .border(BorderStroke(0.5.dp, SleekBorder), shape = RoundedCornerShape(16.dp))
                .background(SleekCardBg, shape = RoundedCornerShape(16.dp))
                .height(130.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.pager.VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 40.dp)
            ) { page ->
                val displayValue = page % limit
                val isSelected = page == pagerState.currentPage
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = String.format("%02d", displayValue),
                        fontSize = if (isSelected) 32.sp else 20.sp,
                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium,
                        color = if (isSelected) SleekPrimary else SleekMutedText.copy(alpha = 0.4f),
                        maxLines = 1
                    )
                }
            }
            // selection overlay outlines
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SleekPrimary.copy(alpha = 0.2f)))
                Spacer(modifier = Modifier.height(48.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SleekPrimary.copy(alpha = 0.2f)))
            }
        }
    }
}

data class Quad<T1, T2, T3, T4>(val first: T1, val second: T2, val third: T3, val fourth: T4)

fun getRingtoneName(context: Context, uriString: String): String {
    if (uriString.isEmpty()) return "Default Alarm Sound"
    return try {
        val uri = Uri.parse(uriString)
        val ringtone = RingtoneManager.getRingtone(context, uri)
        ringtone?.getTitle(context) ?: "Custom Tone"
    } catch (e: Exception) {
        "Custom Tone"
    }
}

