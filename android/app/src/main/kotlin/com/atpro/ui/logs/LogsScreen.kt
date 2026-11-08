package com.atpro.ui.logs

import androidx.compose.animation.*
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AtType
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atpro.db.entity.FarmLogEntity
import com.atpro.ui.theme.AtChip
import com.atpro.ui.theme.AtTopBar
import java.text.SimpleDateFormat
import java.util.*

// ── Design tokens ─────────────────────────────────────────────
private val BgDark get() = AtColors.Bg
private val CardDark get() = AtColors.Surface
private val Purple get() = AtColors.Accent
private val Green get() = AtColors.Success
private val Amber get() = AtColors.Warning
private val RedBad get() = AtColors.Danger
private val TextSec get() = AtColors.TextSec
private val TextMuted get() = AtColors.TextMuted
private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

// ─────────────────────────────────────────────────────────────
//  Root screen
// ─────────────────────────────────────────────────────────────

@Composable
fun LogsScreen(vm: LogsViewModel, onNavigateUp: (() -> Unit)? = null) {
    val logs        by vm.logs.collectAsStateWithLifecycle()
    val levelFilter by vm.levelFilter.collectAsStateWithLifecycle()
    val showDetails by vm.showDetails.collectAsStateWithLifecycle()
    val clipboard   = LocalClipboardManager.current
    val listState   = rememberLazyListState()

    // Auto-scroll to top khi có log mới
    LaunchedEffect(logs.firstOrNull()?.id) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .statusBarsPadding()
    ) {
        // ── Toolbar (v1.8.0: AtTopBar chung; quay lại khi mở dạng màn hình phủ) ──
        AtTopBar(
            title    = "Nhật ký",
            subtitle = if (logs.isEmpty()) "Chưa có sự kiện" else "${logs.size} sự kiện gần nhất",
            onBack   = onNavigateUp,
        ) {
            // Copy all
            IconButton(onClick = {
                val text = logs.joinToString("\n") {
                    "[${timeFmt.format(Date(it.timestamp))}] ${it.message}"
                }
                clipboard.setText(AnnotatedString(text))
            }) {
                Icon(Icons.Rounded.ContentCopy, "Sao chép tất cả", tint = TextSec, modifier = Modifier.size(20.dp))
            }
            // Clear all
            IconButton(onClick = vm::clearAll) {
                Icon(Icons.Rounded.DeleteSweep, "Xoá tất cả", tint = TextSec, modifier = Modifier.size(20.dp))
            }
        }

        // ── Level filter chips ──
        LazyRow(
            contentPadding        = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val levels = listOf(
                null                to ("Tất cả"   to Icons.AutoMirrored.Rounded.List),
                LOG_LEVEL_INFO      to ("Info"     to Icons.Rounded.Info),
                LOG_LEVEL_SUCCESS   to ("OK"       to Icons.Rounded.CheckCircle),
                LOG_LEVEL_WARNING   to ("Cảnh báo" to Icons.Rounded.Warning),
                LOG_LEVEL_ERROR     to ("Lỗi"      to Icons.Rounded.Error),
            )
            items(levels) { (lvl, meta) ->
                val (label, icon) = meta
                AtChip(text = label, selected = levelFilter == lvl, onClick = { vm.setFilter(lvl) }, icon = icon)
            }
            // v1.11.0: hiện các dòng chỉ để gỡ lỗi (ẩn mặc định cho gọn)
            item {
                AtChip(text = "Chi tiết", selected = showDetails, onClick = vm::toggleDetails, icon = Icons.Rounded.Build)
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── Log list ──
        AnimatedContent(
            targetState = logs.isEmpty(),
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
            label = "logs_content",
        ) { empty ->
            if (empty) {
                EmptyLogsView()
            } else {
                LazyColumn(
                    state               = listState,
                    contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    reverseLayout       = false,
                ) {
                    items(logs, key = { it.id }) { log ->
                        // v1.11.0: dòng mới trượt/hiện dần, các dòng cũ dịch chỗ mượt
                        LogRow(log, Modifier.animateItem())
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Log row
// ─────────────────────────────────────────────────────────────

@Composable
private fun LogRow(log: FarmLogEntity, modifier: Modifier = Modifier) {
    val parsed = remember(log.message) { parseLogMessage(log.message) }
    val time   = remember(log.timestamp) { timeFmt.format(Date(log.timestamp)) }
    val color  = parsed.style.color

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CardDark)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Level accent bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(if (log.accountId != null) 34.dp else 18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(8.dp))

        // Level icon
        Icon(
            parsed.style.icon, null,
            tint     = color.copy(alpha = 0.85f),
            modifier = Modifier
                .size(14.dp)
                .padding(top = 1.dp),
        )
        Spacer(Modifier.width(6.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Tag badge — gọn gàng thay cho tiền tố "TAG:" lặp lại trong text.
                parsed.tag?.let { tag ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(color.copy(alpha = 0.14f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text       = tag,
                            color      = color,
                            fontSize   = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text       = parsed.body,
                    color      = AtColors.TextPrim,
                    fontSize   = 12.sp,
                    lineHeight = 16.sp,
                    modifier   = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Text(time, color = TextMuted, fontSize = 10.sp)
            }
            log.accountId?.let {
                Spacer(Modifier.height(2.dp))
                Text("@$it", color = Purple.copy(alpha = 0.7f), fontSize = 10.sp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────

private fun levelColor(level: String?): Color = when (level) {
    LOG_LEVEL_SUCCESS -> Green
    LOG_LEVEL_WARNING -> Amber
    LOG_LEVEL_ERROR   -> RedBad
    else              -> AtColors.Sky // INFO = xanh da trời
}

@Composable
private fun EmptyLogsView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.Assignment,
            contentDescription = null,
            tint     = TextMuted.copy(alpha = 0.4f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Chưa có nhật ký",
            color = AtColors.TextPrim, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}
