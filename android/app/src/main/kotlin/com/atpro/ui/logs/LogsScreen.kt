package com.atpro.ui.logs

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atpro.db.entity.FarmLogEntity
import java.text.SimpleDateFormat
import java.util.*

// ── Design tokens ─────────────────────────────────────────────
private val BgDark   = Color(0xFF0D0D14)
private val CardDark = Color(0xFF1A1A2E)
private val Purple   = Color(0xFF6C63FF)
private val Green    = Color(0xFF10B981)
private val Amber    = Color(0xFFF59E0B)
private val RedBad   = Color(0xFFEF4444)
private val TextSec  = Color(0xFF9CA3AF)
private val TextMuted= Color(0xFF6B7280)

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

// ─────────────────────────────────────────────────────────────
//  Root screen
// ─────────────────────────────────────────────────────────────

@Composable
fun LogsScreen(vm: LogsViewModel, onNavigateUp: (() -> Unit)? = null) {
    val logs        by vm.logs.collectAsStateWithLifecycle()
    val levelFilter by vm.levelFilter.collectAsStateWithLifecycle()
    val clipboard   = LocalClipboardManager.current
    val listState   = rememberLazyListState()
    val activity    = LocalContext.current as? Activity

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
        // ── Toolbar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onNavigateUp?.invoke() ?: activity?.finish() }) {
                Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "Quay lại", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Text(
                "Nhật ký",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            AnimatedVisibility(
                visible = logs.isNotEmpty(),
                enter   = fadeIn(tween(200)) + scaleIn(tween(200)),
                exit    = fadeOut(tween(150)) + scaleOut(tween(150)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Purple.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text("${logs.size}", color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
            // Copy all
            IconButton(onClick = {
                val text = logs.joinToString("\n") {
                    "[${timeFmt.format(Date(it.timestamp))}] ${it.message}"
                }
                clipboard.setText(AnnotatedString(text))
            }) {
                Icon(Icons.Rounded.ContentCopy, null, tint = TextSec, modifier = Modifier.size(20.dp))
            }
            // Clear all
            IconButton(onClick = vm::clearAll) {
                Icon(Icons.Rounded.DeleteSweep, null, tint = TextSec, modifier = Modifier.size(20.dp))
            }
        }

        // ── Level filter chips ──
        LazyRow(
            contentPadding        = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val levels = listOf(
                null       to ("Tất cả"   to Icons.Rounded.List),
                "INFO"     to ("Info"     to Icons.Rounded.Info),
                "SUCCESS"  to ("OK"       to Icons.Rounded.CheckCircle),
                "WARNING"  to ("Cảnh báo" to Icons.Rounded.Warning),
                "ERROR"    to ("Lỗi"      to Icons.Rounded.Error),
            )
            items(levels) { (lvl, meta) ->
                val (label, icon) = meta
                FilterChip(
                    selected      = levelFilter == lvl,
                    onClick       = { vm.setFilter(lvl) },
                    label         = { Text(label, fontSize = 12.sp) },
                    leadingIcon   = {
                        Icon(icon, null, modifier = Modifier.size(14.dp))
                    },
                    colors        = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = levelColor(lvl).copy(alpha = 0.15f),
                        selectedLabelColor     = levelColor(lvl),
                        selectedLeadingIconColor = levelColor(lvl),
                        containerColor         = CardDark,
                        labelColor             = TextSec,
                    ),
                    border        = FilterChipDefaults.filterChipBorder(
                        borderColor         = Color(0xFF374151),
                        selectedBorderColor = levelColor(lvl).copy(alpha = 0.4f),
                        enabled = true, selected = levelFilter == lvl,
                    ),
                )
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
                        LogRow(log)
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
private fun LogRow(log: FarmLogEntity) {
    val color = levelColor(log.level)
    val icon  = levelIcon(log.level)
    val time  = remember(log.timestamp) { timeFmt.format(Date(log.timestamp)) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CardDark)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Level accent bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(10.dp))

        // Level icon
        Icon(
            icon, null,
            tint     = color.copy(alpha = 0.8f),
            modifier = Modifier
                .size(14.dp)
                .padding(top = 2.dp),
        )
        Spacer(Modifier.width(8.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text       = log.message,
                color      = Color.White,
                fontSize   = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(time, color = TextMuted, fontSize = 10.sp)
                log.accountId?.let {
                    Text("@$it", color = Purple.copy(alpha = 0.7f), fontSize = 10.sp)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────

private fun levelColor(level: String?): Color = when (level) {
    "SUCCESS" -> Green
    "WARNING" -> Amber
    "ERROR"   -> RedBad
    else      -> Color(0xFF60A5FA) // INFO = blue
}

private fun levelIcon(level: String): ImageVector = when (level) {
    "SUCCESS" -> Icons.Rounded.CheckCircle
    "WARNING" -> Icons.Rounded.Warning
    "ERROR"   -> Icons.Rounded.Error
    else      -> Icons.Rounded.Info
}

@Composable
private fun EmptyLogsView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.Assignment,
            contentDescription = null,
            tint     = TextMuted.copy(alpha = 0.4f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Chưa có nhật ký nào",
            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Nhật ký sẽ xuất hiện\nkhi farm bắt đầu chạy",
            color     = TextMuted,
            fontSize  = 13.sp,
            lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
