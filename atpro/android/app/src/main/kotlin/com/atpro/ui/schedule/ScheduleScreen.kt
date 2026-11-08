package com.atpro.ui.schedule

import androidx.compose.foundation.*
import com.atpro.ui.theme.staggerIn
import com.atpro.ui.theme.AtSkeleton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateContentSize
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AtTopBar
import com.atpro.scheduler.ScheduledFarmManager.FarmSchedule
import com.atpro.ui.theme.AtCard
import com.atpro.ui.theme.AtChip
import com.atpro.ui.theme.AtDialog
import com.atpro.ui.theme.AtEmptyState
import com.atpro.ui.theme.AtSwitch
import com.atpro.ui.theme.AtTextField
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// ── Palette ───────────────────────────────────────────────────
private val BgDark get() = AtColors.Bg
private val CardDark get() = AtColors.Surface
private val BorderDark get() = AtColors.Hairline
private val Purple get() = AtColors.Accent
private val Green get() = AtColors.Success
private val RedBad get() = AtColors.Danger
private val Amber get() = AtColors.Warning
private val TextSec get() = AtColors.TextSec
private val TextMuted get() = AtColors.TextMuted
private val DAY_LABELS = listOf("CN", "T2", "T3", "T4", "T5", "T6", "T7")

// ─────────────────────────────────────────────────────────────
//  Root
//  v1.2.8: onNavigateUp — v1.9.0: ScheduleActivity đã gỡ; chỉ còn mở dạng màn hình phủ từ MainScreen.
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(vm: ScheduleViewModel, onNavigateUp: (() -> Unit)? = null) {
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    var showAdd   by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            // v1.9.0: AtTopBar như các màn hình khác (trước là TopAppBar Material riêng); statusBarsPadding vì
            // đây là màn hình phủ toàn bộ, không còn TopAppBar tự cộng inset thanh trạng thái.
            AtTopBar(
                title    = "Lịch tự động",
                subtitle = "${schedules.count { it.enabled }} lịch đang bật",
                onBack   = onNavigateUp,
                modifier = Modifier.statusBarsPadding(),
                actions  = {
                    IconButton(onClick = vm::load) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Tải lại", tint = TextSec)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick        = { showAdd = true },
                containerColor = AtColors.Blue,
                contentColor   = AtColors.OnBlue,
                shape          = RoundedCornerShape(16.dp),
                icon           = { Icon(Icons.Rounded.Add, null) },
                text           = { Text("Thêm lịch", fontWeight = FontWeight.SemiBold) },
            )
        },
    ) { padding ->

        if (isLoading) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(3) { AtSkeleton(Modifier.fillMaxWidth().height(104.dp)) }
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(schedules, key = { _, sc -> sc.id }) { index, schedule ->
                    Box(Modifier.staggerIn(index.coerceAtMost(8), stepMs = 40)) {
                        SwipeToDeleteScheduleRow(
                            schedule = schedule,
                            onToggle = { vm.toggle(schedule) },
                            onDelete = { vm.delete(schedule) },
                        )
                    }
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
        AtDialog(
            onDismiss   = { confirmDelete = false },
            title       = "Xóa lịch?",
            message     = "${schedule.label} · %02d:%02d".format(schedule.hourOfDay, schedule.minute),
            confirmText = "Xóa",
            onConfirm   = { confirmDelete = false; onDelete() },
            danger      = true,
        )
    }

    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
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
    val serviceLabel = serviceLabelOf(schedule.serviceMode)
    val active = schedule.enabled
    // Thẻ mờ đi khi tắt lịch (chuyển mượt thay vì đổi màu đột ngột).
    val alpha by animateFloatAsState(if (active) 1f else 0.55f, tween(250), label = "schedule_alpha")
    val nextRun = remember(schedule) { nextRunText(schedule) }

    AtCard(
        modifier       = Modifier.fillMaxWidth().animateContentSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).graphicsLayer { this.alpha = alpha }) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "%02d:%02d".format(schedule.hourOfDay, schedule.minute),
                        color      = if (active) AtColors.TextPrim else TextMuted,
                        fontSize   = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-1).sp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (schedule.label == serviceLabel || schedule.label.isBlank()) serviceLabel
                        else "${schedule.label} · $serviceLabel",
                        color    = TextSec, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 5.dp).weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    (1..7).forEach { day ->
                        val on = day in schedule.daysOfWeek
                        val bg by animateColorAsState(
                            if (on) Purple.copy(alpha = 0.18f) else BorderDark.copy(alpha = 0.35f),
                            tween(200), label = "day_bg",
                        )
                        Box(
                            modifier = Modifier.size(26.dp).clip(CircleShape).background(bg),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                DAY_LABELS[day - 1], fontSize = 9.sp,
                                color      = if (on) Purple else TextMuted,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                if (nextRun != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(nextRun, color = Green, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            AtSwitch(checked = schedule.enabled, onCheckedChange = { onToggle() })
        }
    }
}

private fun serviceLabelOf(mode: String): String = when (mode) {
    "FACEBOOK_NURTURE"  -> "Nuôi Facebook"
    "X_NURTURE"         -> "Nuôi X"
    "INSTAGRAM_NURTURE" -> "Nuôi Instagram"
    "THREADS_NURTURE"   -> "Nuôi Threads"
    "SNAPCHAT_NURTURE"  -> "Nuôi Snapchat"
    else                -> "Nuôi TikTok"
}

/** "Còn 2 giờ 15 phút" tới lần chạy kế tiếp (null nếu lịch tắt / không có ngày). Ngày 1 = CN … 7 = T7 như Calendar. */
private fun nextRunText(s: FarmSchedule): String? {
    if (!s.enabled || s.daysOfWeek.isEmpty()) return null
    val now = java.util.Calendar.getInstance()
    var best: Long? = null
    for (offset in 0..7) {
        val c = (now.clone() as java.util.Calendar).apply {
            add(java.util.Calendar.DAY_OF_YEAR, offset)
            set(java.util.Calendar.HOUR_OF_DAY, s.hourOfDay)
            set(java.util.Calendar.MINUTE, s.minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (c.get(java.util.Calendar.DAY_OF_WEEK) in s.daysOfWeek && c.timeInMillis > now.timeInMillis) {
            best = c.timeInMillis; break
        }
    }
    val min = ((best ?: return null) - now.timeInMillis) / 60_000L
    return when {
        min < 60        -> "Còn $min phút"
        min < 24 * 60   -> "Còn ${min / 60} giờ ${min % 60} phút"
        else            -> "Còn ${min / 1_440} ngày ${(min % 1_440) / 60} giờ"
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
    var hour         by remember { mutableIntStateOf(8) }
    var minute       by remember { mutableIntStateOf(0) }
    var selectedDays by remember { mutableStateOf(setOf(2, 3, 4, 5, 6, 7, 1)) }
    var serviceMode  by remember { mutableStateOf("FARM") }

    val services = listOf(
        "FARM", "FACEBOOK_NURTURE", "X_NURTURE", "INSTAGRAM_NURTURE", "THREADS_NURTURE", "SNAPCHAT_NURTURE",
    )

    AtDialog(
        onDismiss      = onDismiss,
        title          = "Thêm lịch",
        confirmText    = "Thêm",
        confirmEnabled = selectedDays.isNotEmpty(),
        // v1.10.0: bỏ ô "Tên lịch" — nhãn lấy theo dịch vụ (đỡ một bước nhập).
        onConfirm      = { onAdd(serviceLabelOf(serviceMode), hour, minute, selectedDays.sorted(), serviceMode) },
        dismissText    = "Hủy",
    ) {
        Column(
            modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(services) { key ->
                    AtChip(text = serviceLabelOf(key), selected = key == serviceMode, onClick = { serviceMode = key })
                }
            }

            // Giờ : Phút — một hàng, mỗi cột có nút tăng / giảm
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TimeColumn("%02d".format(hour),   onUp = { hour = (hour + 1) % 24 },      onDown = { hour = (hour - 1 + 24) % 24 })
                Text(":", color = TextMuted, fontSize = 32.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(horizontal = 14.dp))
                TimeColumn("%02d".format(minute), onUp = { minute = (minute + 5) % 60 }, onDown = { minute = (minute - 5 + 60) % 60 })
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (1..7).forEach { day ->
                    val selected = day in selectedDays
                    val bg by animateColorAsState(if (selected) AtColors.Blue else AtColors.SurfaceHigh, tween(160), label = "day_pick")
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(bg)
                            .clickable {
                                selectedDays = if (selected && selectedDays.size > 1) selectedDays - day else selectedDays + day
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            DAY_LABELS[day - 1], fontSize = 10.sp,
                            color = if (selected) AtColors.OnBlue else TextMuted, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeColumn(text: String, onUp: () -> Unit, onDown: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TimeStepBtn(Icons.Rounded.KeyboardArrowUp, onUp)
        Text(
            text, color = AtColors.TextPrim, fontSize = 34.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        TimeStepBtn(Icons.Rounded.KeyboardArrowDown, onDown)
    }
}

@Composable
private fun TimeStepBtn(
    icon:    androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Purple.copy(alpha = 0.15f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Purple, modifier = Modifier.size(20.dp))
    }
}

// ─────────────────────────────────────────────────────────────
//  Empty state
// ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyScheduleView(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AtEmptyState(
            icon     = Icons.Rounded.Schedule,
            title    = "Chưa có lịch",
        )
    }
}
