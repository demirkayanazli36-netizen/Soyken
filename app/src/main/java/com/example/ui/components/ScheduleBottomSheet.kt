package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.billing.SubscriptionConfig
import com.example.data.model.Channel
import com.example.data.model.ProgramReminder
import com.example.ui.theme.SoykenGold
import com.example.ui.tv.soykenTvFocus
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

private const val WHEEL_CYCLES = 200
private val WHEEL_ITEM_HEIGHT = 50.dp
private val WHEEL_VIEWPORT_HEIGHT = 150.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ScheduleBottomSheet(
    channel: Channel?,
    reminders: List<ProgramReminder>,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSaveTimeReminder: (hourOfDay: Int, minute: Int) -> Unit,
    onRemoveReminder: (ProgramReminder) -> Unit,
    isPremium: Boolean = false
) {
    if (channel == null) return

    val channelReminders = remember(reminders, channel.id) {
        reminders
            .filter { it.channelId == channel.id }
            .sortedBy { it.startTime }
    }

    val (initialHour, initialMinute) = remember(channel.id) {
        val existingTime = channelReminders.firstOrNull()?.programTitle
        val parsed = existingTime?.split(":")?.takeIf { it.size == 2 }
        val h = parsed?.getOrNull(0)?.toIntOrNull()
        val m = parsed?.getOrNull(1)?.toIntOrNull()
        if (h != null && m != null && h in 0..23 && m in 0..59) {
            h to m
        } else {
            val cal = Calendar.getInstance().apply {
                add(Calendar.MINUTE, 1)
            }
            cal.get(Calendar.HOUR_OF_DAY) to cal.get(Calendar.MINUTE)
        }
    }

    val hourTotalCount = 24 * WHEEL_CYCLES
    val minuteTotalCount = 60 * WHEEL_CYCLES
    val initialHourIndex = remember(channel.id) { (WHEEL_CYCLES / 2) * 24 + initialHour }
    val initialMinuteIndex = remember(channel.id) { (WHEEL_CYCLES / 2) * 60 + initialMinute }

    var activeHourRawIndex by remember(channel.id) { mutableIntStateOf(initialHourIndex) }
    var activeMinuteRawIndex by remember(channel.id) { mutableIntStateOf(initialMinuteIndex) }
    var isProgrammaticHourScroll by remember(channel.id) { mutableStateOf(false) }
    var isProgrammaticMinuteScroll by remember(channel.id) { mutableStateOf(false) }
    var userTouchedHourWheel by remember(channel.id) { mutableStateOf(false) }
    var userTouchedMinuteWheel by remember(channel.id) { mutableStateOf(false) }

    val hourListState = rememberLazyListState(initialFirstVisibleItemIndex = initialHourIndex)
    val minuteListState = rememberLazyListState(initialFirstVisibleItemIndex = initialMinuteIndex)
    val coroutineScope = rememberCoroutineScope()

    val centeredHourIndex by remember {
        derivedStateOf {
            val layoutInfo = hourListState.layoutInfo
            if (layoutInfo.visibleItemsInfo.isEmpty()) {
                activeHourRawIndex
            } else {
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                layoutInfo.visibleItemsInfo.minByOrNull { item ->
                    abs((item.offset + item.size / 2) - viewportCenter)
                }?.index ?: activeHourRawIndex
            }
        }
    }

    val centeredMinuteIndex by remember {
        derivedStateOf {
            val layoutInfo = minuteListState.layoutInfo
            if (layoutInfo.visibleItemsInfo.isEmpty()) {
                activeMinuteRawIndex
            } else {
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                layoutInfo.visibleItemsInfo.minByOrNull { item ->
                    abs((item.offset + item.size / 2) - viewportCenter)
                }?.index ?: activeMinuteRawIndex
            }
        }
    }

    LaunchedEffect(hourListState.isScrollInProgress) {
        if (hourListState.isScrollInProgress && !isProgrammaticHourScroll) {
            userTouchedHourWheel = true
        }
    }

    LaunchedEffect(minuteListState.isScrollInProgress) {
        if (minuteListState.isScrollInProgress && !isProgrammaticMinuteScroll) {
            userTouchedMinuteWheel = true
        }
    }

    LaunchedEffect(centeredHourIndex, hourListState.isScrollInProgress) {
        if (userTouchedHourWheel && !isProgrammaticHourScroll && hourListState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
            activeHourRawIndex = centeredHourIndex
        }
    }

    LaunchedEffect(centeredMinuteIndex, minuteListState.isScrollInProgress) {
        if (userTouchedMinuteWheel && !isProgrammaticMinuteScroll && minuteListState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
            activeMinuteRawIndex = centeredMinuteIndex
        }
    }

    val selectedHour = ((activeHourRawIndex % 24) + 24) % 24
    val selectedMinute = ((activeMinuteRawIndex % 60) + 60) % 60
    val formattedSelectedTime = MainViewModel.formatReminderHourMinute(selectedHour, selectedMinute)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("schedule_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Channel Header (Channel Name + Close Only)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(SoykenGold.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Alarm,
                        contentDescription = null,
                        tint = SoykenGold,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("close_schedule_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Kapat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!isPremium) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Ücretsiz Plan: ${reminders.size} / ${SubscriptionConfig.FREE_MAX_REMINDERS} aktif hatırlatıcı • Sınırsız alarm için Premium",
                    style = MaterialTheme.typography.labelSmall,
                    color = SoykenGold,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Pure Hour & Minute Picker Card (SAAT : DAKİKA)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .border(
                        width = 1.dp,
                        color = SoykenGold.copy(alpha = 0.28f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // SAAT (00 - 23)
                    TimeWheelColumn(
                        label = "SAAT",
                        totalCount = hourTotalCount,
                        modulo = 24,
                        selectedRawIndex = activeHourRawIndex,
                        listState = hourListState,
                        incrementTestTag = "hour_increment_button",
                        decrementTestTag = "hour_decrement_button",
                        wheelTestTag = "hour_wheel",
                        onStep = { delta ->
                            val target = (activeHourRawIndex + delta).coerceIn(0, hourTotalCount - 1)
                            activeHourRawIndex = target
                            coroutineScope.launch {
                                isProgrammaticHourScroll = true
                                try {
                                    hourListState.animateScrollToItem(target)
                                } finally {
                                    isProgrammaticHourScroll = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )

                    // Center Colon ":"
                    Text(
                        text = ":",
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 36.sp
                        ),
                        color = SoykenGold,
                        modifier = Modifier.padding(top = 22.dp, start = 8.dp, end = 8.dp)
                    )

                    // DAKİKA (00 - 59)
                    TimeWheelColumn(
                        label = "DAKİKA",
                        totalCount = minuteTotalCount,
                        modulo = 60,
                        selectedRawIndex = activeMinuteRawIndex,
                        listState = minuteListState,
                        incrementTestTag = "minute_increment_button",
                        decrementTestTag = "minute_decrement_button",
                        wheelTestTag = "minute_wheel",
                        onStep = { delta ->
                            val target = (activeMinuteRawIndex + delta).coerceIn(0, minuteTotalCount - 1)
                            activeMinuteRawIndex = target
                            coroutineScope.launch {
                                isProgrammaticMinuteScroll = true
                                try {
                                    minuteListState.animateScrollToItem(target)
                                } finally {
                                    isProgrammaticMinuteScroll = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Primary Confirm Button
            Button(
                onClick = { onSaveTimeReminder(selectedHour, selectedMinute) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .soykenTvFocus(RoundedCornerShape(14.dp))
                    .testTag("save_time_reminder_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SoykenGold,
                    contentColor = Color.Black
                )
            ) {
                Icon(
                    imageVector = Icons.Default.AlarmOn,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Hatırlatıcı Kur  •  $formattedSelectedTime",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                )
            }

            // Active HH:mm Reminders on this channel (compact HH:mm + cancel icon)
            if (channelReminders.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    channelReminders.forEach { reminder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SoykenGold.copy(alpha = 0.1f))
                                .border(
                                    width = 1.dp,
                                    color = SoykenGold.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AlarmOn,
                                contentDescription = null,
                                tint = SoykenGold,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = reminder.programTitle,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.ExtraBold
                                ),
                                color = SoykenGold,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onRemoveReminder(reminder) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("remove_reminder_${reminder.programId}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "${reminder.programTitle} iptal",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimeWheelColumn(
    label: String,
    totalCount: Int,
    modulo: Int,
    selectedRawIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    incrementTestTag: String,
    decrementTestTag: String,
    wheelTestTag: String,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val snapBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            ),
            color = SoykenGold
        )

        IconButton(
            onClick = { onStep(1) },
            modifier = Modifier
                .size(48.dp)
                .testTag(incrementTestTag)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "$label artır",
                tint = SoykenGold,
                modifier = Modifier.size(22.dp)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(WHEEL_VIEWPORT_HEIGHT),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WHEEL_ITEM_HEIGHT)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SoykenGold.copy(alpha = 0.16f))
                    .border(
                        width = 1.5.dp,
                        color = SoykenGold,
                        shape = RoundedCornerShape(12.dp)
                    )
            )

            LazyColumn(
                state = listState,
                flingBehavior = snapBehavior,
                contentPadding = PaddingValues(vertical = WHEEL_ITEM_HEIGHT),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WHEEL_VIEWPORT_HEIGHT)
                    .testTag(wheelTestTag)
            ) {
                items(count = totalCount, key = { it }) { index ->
                    val value = ((index % modulo) + modulo) % modulo
                    val isSelected = index == selectedRawIndex
                    val formatted = String.format(Locale.US, "%02d", value)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(WHEEL_ITEM_HEIGHT)
                            .clickable {
                                val delta = index - selectedRawIndex
                                if (delta != 0) onStep(delta)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = formatted,
                            textAlign = TextAlign.Center,
                            style = if (isSelected) {
                                MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 30.sp
                                )
                            } else {
                                MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 18.sp
                                )
                            },
                            color = if (isSelected) {
                                SoykenGold
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            }
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = { onStep(-1) },
            modifier = Modifier
                .size(48.dp)
                .testTag(decrementTestTag)
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = "$label azalt",
                tint = SoykenGold,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
