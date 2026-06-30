package com.atpro.ui.schedule

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.SwipeToDismissBoxValue.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atpro.scheduler.ScheduledFarmManager.FarmSchedule

// ── Palette ───────────────────────────────────────────────────
private val BgDark    = Color(0xFF0D0D14)
private val CardDark  = Color(0xFF1A1A2E)
private val BorderDark= Color(0xFF374151)
private val Purple    = Color(0xFF6C63FF)
private val Green     = Color(0xFF10B981)
private val RedBad    = Color(0xFFEF4444)
private val Amber     = Color(0xFFF59E0B)
private val TextSec   = Color(0xFF9CA3AF)
private val TextMuted = Color(0xFF6B7280)

private val DAY_LABELS = listOf("CN", "T2", "T3", "T4", "T5", "T6", "T7")

// ─────────────────────────────────────────────────────────────
//  Root
//  v1.2.8: thêm onNavigateUp để dùng được cả trong tab MainScreen
//           lẫn standalone ScheduleActivity (default = {}).
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(vm: ScheduleViewModel, onNavigateUp: () -> Unit = {}) {
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    var showAdd   by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Lịch tự động", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(
                            "${schedules.count { it.enabled }} lịch đang bật",
                            color = TextMuted, fontSize = 11.sp,
                        )
                    }
                },
                colors  = TopAppBarDefaults.topAppBarColors(containerColor = BgDark),
                actions = {
                    IconButton(onClick = vm::load) {
                        Icon(Icons.Rounded.Refresh, null, tint = TextSec)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick           = { showAdd = true },
                containerColor    = Purple,
                contentColor      = Color.White,
                shape             = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Rounded.Add, "Thêm lịch")
            }
        },
    ) { padding ->

        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator(color = Purple)
            }
        } else if (schedules.isEmpty()) {
            EmptyScheduleView(Modifier.padding(padding))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start  = 16.dp, end    = 16.dp,
                    top    = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 80.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(schedules, key = { it.id }) { schedule ->
                    SwipeToDeleteScheduleRow(
                        schedule = schedule,
                        onToggle = { vm.toggle(schedule) },
                        onDelete = { vm.delete(schedule) },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddScheduleDialog(
            onDismiss = { showAdd = false },
            onAdd     = { label, hour, minute, days, svcMode ->
                vm.add(label, hour, minute, days, svcMode)
                showAdd = false
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  Schedule row
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteScheduleRow(
    schedule: FarmSchedule,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { v -> if (v == EndToStart) { confirmDelete = true; false } else false }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor   = CardDark,
            title  = { Text("Xóa lịch \"${schedule.label}\"?", color = Color.White) },
            text   = { Text("WorkManager sẽ hủy tác vụ đã đặt.", color = TextSec) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Xóa", color = RedBad)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Hủy", color = TextSec) }
            },
        )
    }

    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(RedBad.copy(alpha = 0.15f))
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Rounded.Delete, null, tint = RedBad) }
        },
        enableDismissFromStartToEnd = false,
    ) {
        ScheduleRow(schedule, onToggle)
    }
}

@Composable
private fun ScheduleRow(schedule: FarmSchedule, onToggle: () -> Unit) {
    // v1.2.9: label dịch vụ
    val serviceLabel = when (schedule.serviceMode) {
        "FACEBOOK_NURTURE"  -> "Nuôi Facebook"
        "X_NURTURE"         -> "Nuôi X"
        "INSTAGRAM_NURTURE" -> "Nuôi Instagram"
        "THREADS_NURTURE"   -> "Nuôi Threads"
        "SNAPCHAT_NURTURE"  -> "Nuôi Snapchat"
        else                -> "Nuôi TikTok"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardDark)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(schedule.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Purple.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(serviceLabel, color = Purple, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "%02d:%02d".format(schedule.hourOfDay, schedule.minute),
                color      = if (schedule.enabled) Purple else TextMuted,
                fontSize   = 22.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1, 2, 3, 4, 5, 6, 7).forEach { day ->
                    val active = day in schedule.daysOfWeek
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(
                                if (active && schedule.enabled) Purple.copy(alpha = 0.2f)
                                else BorderDark.copy(alpha = 0.4f)
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            DAY_LABELS[day - 1],
                            fontSize   = 9.sp,
                            color      = if (active && schedule.enabled) Purple else TextMuted,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        Switch(
            checked         = schedule.enabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Purple,
            ),
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  Add dialog — v1.2.9: tách giờ/phút, thêm chọn dịch vụ
// ─────────────────────────────────────────────────────────────

@Composable
private fun AddScheduleDialog(
    onDismiss: () -> Unit,
    onAdd:     (label: String, hour: Int, minute: Int, days: List<Int>, serviceMode: String) -> Unit,
) {
    var label        by remember { mutableStateOf("Farm") }
    var hour         by remember { mutableIntStateOf(8) }
    var minute       by remember { mutableIntStateOf(0) }
    var selectedDays by remember { mutableStateOf(setOf(2, 3, 4, 5, 6, 7, 1)) }
    var serviceMode  by remember { mutableStateOf("FARM") }

    // Danh sách dịch vụ
    data class SvcItem(val key: String, val label: String, val accent: Color)
    val services = listOf(
        SvcItem("FARM",              "Nuôi TikTok",    Color(0xFF69C9D0)),
        SvcItem("FACEBOOK_NURTURE",  "Nuôi Facebook",  Color(0xFF1877F2)),
        SvcItem("X_NURTURE",         "Nuôi X",         Color(0xFFEEEEEE)),
        SvcItem("INSTAGRAM_NURTURE", "Nuôi Instagram", Color(0xFFFD1D1D)),
        SvcItem("THREADS_NURTURE",   "Nuôi Threads",   Color(0xFFAAAAAA)),
        SvcItem("SNAPCHAT_NURTURE",  "Nuôi Snapchat",  Color(0xFFFFFC00)),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = CardDark,
        title = { Text("Thêm lịch tự động", color = Color.White, fontWeight = FontWeight.Bold) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                // Tên lịch
                OutlinedTextField(
                    value         = label,
                    onValueChange = { label = it },
                    label         = { Text("Tên lịch", color = TextSec, fontSize = 12.sp) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Purple, unfocusedBorderColor  = BorderDark,
                        focusedTextColor     = Color.White, unfocusedTextColor = Color.White,
                        focusedContainerColor   = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                )

                // Chọn dịch vụ
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Dịch vụ:", color = TextSec, fontSize = 13.sp)
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(services) { svc ->
                            val sel = svc.key == serviceMode
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) svc.accent.copy(0.2f) else Color(0xFF1E1E2E))
                                    .border(
                                        1.dp,
                                        if (sel) svc.accent.copy(0.7f) else Color(0xFF2A2A40),
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clickable { serviceMode = svc.key }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    svc.label,
                                    fontSize   = 11.sp,
                                    color      = if (sel) svc.accent else TextMuted,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }

                // Giờ — row riêng
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Giờ:", color = TextSec, fontSize = 13.sp, modifier = Modifier.width(44.dp))
                    TimeStepBtn(Icons.Rounded.Remove) { hour = (hour - 1 + 24) % 24 }
                    Text(
                        "%02d".format(hour),
                        color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    TimeStepBtn(Icons.Rounded.Add) { hour = (hour + 1) % 24 }
                }

                // Phút — row riêng (fix: trước đây cùng row với Giờ → tràn màn hình nhỏ)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Phút:", color = TextSec, fontSize = 13.sp, modifier = Modifier.width(44.dp))
                    TimeStepBtn(Icons.Rounded.Remove) { minute = (minute - 5 + 60) % 60 }
                    Text(
                        "%02d".format(minute),
                        color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    TimeStepBtn(Icons.Rounded.Add) { minute = (minute + 5) % 60 }
                }

                // Ngày lặp
                Column {
                    Text("Ngày trong tuần:", color = TextSec, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 2, 3, 4, 5, 6, 7).forEach { day ->
                            val selected = day in selectedDays
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(if (selected) Purple else BorderDark)
                                    .clickable {
                                        selectedDays = if (selected && selectedDays.size > 1)
                                            selectedDays - day else selectedDays + day
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    DAY_LABELS[day - 1],
                                    fontSize   = 10.sp,
                                    color      = if (selected) Color.White else TextMuted,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(label.ifBlank { "Farm" }, hour, minute, selectedDays.sorted(), serviceMode) },
                enabled = selectedDays.isNotEmpty(),
            ) {
                Text("Thêm", color = Purple, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy", color = TextSec) }
        },
    )
}

@Composable
private fun TimeStepBtn(
    icon:    androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Purple.copy(alpha = 0.15f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Purple, modifier = Modifier.size(14.dp))
    }
}

// ─────────────────────────────────────────────────────────────
//  Empty state
// ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyScheduleView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Schedule, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("Chưa có lịch nào", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Nhấn + để thêm lịch farm tự động\nWorkManager sẽ khởi động farm đúng giờ",
            color     = TextMuted, fontSize = 13.sp, lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
