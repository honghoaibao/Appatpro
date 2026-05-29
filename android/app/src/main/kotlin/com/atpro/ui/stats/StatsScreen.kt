package com.atpro.ui.stats

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import com.atpro.db.dao.TotalsRow
import com.atpro.db.entity.DailyStatRow

// ── Design tokens ─────────────────────────────────────────────
private val BgDark    = Color(0xFF0D0D14)
private val CardDark  = Color(0xFF1A1A2E)
private val BorderDark= Color(0xFF374151)
private val Purple    = Color(0xFF6C63FF)
private val Pink      = Color(0xFFEC4899)
private val Green     = Color(0xFF10B981)
private val Amber     = Color(0xFFF59E0B)
private val Blue      = Color(0xFF60A5FA)
private val TextSec   = Color(0xFF9CA3AF)
private val TextMuted = Color(0xFF6B7280)

// ─────────────────────────────────────────────────────────────
//  Root screen
// ─────────────────────────────────────────────────────────────

@Composable
fun StatsScreen(vm: StatsViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .statusBarsPadding()
    ) {
        // ── Header ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { activity?.finish() }) {
                Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "Quay lại", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Thống kê",
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    "Tổng hợp phiên farm",
                    color = TextMuted, fontSize = 11.sp,
                )
            }
        }

        // ── Range chips ──
        LazyRow(
            contentPadding        = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(StatsViewModel.Range.entries.toList()) { r ->
                FilterChip(
                    selected = range == r,
                    onClick  = { vm.setRange(r) },
                    label    = { Text(r.label, fontSize = 12.sp) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Purple.copy(alpha = 0.15f),
                        selectedLabelColor     = Purple,
                        containerColor         = CardDark,
                        labelColor             = TextSec,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = BorderDark,
                        selectedBorderColor = Purple.copy(alpha = 0.5f),
                        enabled = true, selected = range == r,
                    ),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Body ──
        AnimatedContent(
            targetState = state.isLoading,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
            label = "loading",
        ) { loading ->
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Purple, modifier = Modifier.size(32.dp))
                }
            } else {
                StatsContent(state)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Content
// ─────────────────────────────────────────────────────────────

@Composable
private fun StatsContent(state: StatsUiState) {
    if (state.totals == null || state.totals.sessionCount == 0) {
        EmptyStatsView()
        return
    }

    LazyColumn(
        contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SummaryCard(state.totals) }

        if (state.dailyStats.isNotEmpty()) {
            item {
                Text(
                    "Theo ngày",
                    color = TextSec, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            val byDate = state.dailyStats.groupBy { it.date }
            byDate.entries.sortedByDescending { it.key }.forEach { (date, rows) ->
                item(key = date) { DayGroup(date = date, rows = rows) }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────
//  Summary card
// ─────────────────────────────────────────────────────────────

@Composable
private fun SummaryCard(totals: TotalsRow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(listOf(Purple.copy(alpha = 0.2f), Pink.copy(alpha = 0.1f)))
            )
            .border(1.dp, Purple.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(20.dp),
    ) {
        Text(
            "Tổng cộng",
            color = TextSec, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SummaryMetric("${totals.sessionCount}", "Phiên",    Icons.Rounded.Loop,       Purple)
            SummaryMetric("${totals.videosWatched}", "Video",   Icons.Rounded.PlayCircle,  Blue)
            SummaryMetric("${totals.likes}", "Thích",           Icons.Rounded.Favorite,    Pink)
            SummaryMetric("${totals.follows}", "Theo dõi",      Icons.Rounded.PersonAdd,   Green)
        }
    }
}

@Composable
private fun SummaryMetric(value: String, label: String, icon: ImageVector, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(4.dp))
        Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = TextMuted, fontSize = 11.sp)
    }
}

// ─────────────────────────────────────────────────────────────
//  Day group
// ─────────────────────────────────────────────────────────────

@Composable
private fun DayGroup(date: String, rows: List<DailyStatRow>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val dayTotalSessions = rows.sumOf { it.sessionCount }
        val dayTotalVideos   = rows.sumOf { it.videosWatched }
        val dayTotalLikes    = rows.sumOf { it.likes }
        val dayTotalFollows  = rows.sumOf { it.follows }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                date,
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                MicroStat("${dayTotalSessions}", Icons.Rounded.Loop,        TextSec)
                MicroStat("${dayTotalVideos}",   Icons.Rounded.PlayCircle,  Blue)
                MicroStat("${dayTotalLikes}",    Icons.Rounded.Favorite,    Pink)
                MicroStat("${dayTotalFollows}",  Icons.Rounded.PersonAdd,   Green)
            }
        }

        if (rows.size > 1) {
            HorizontalDivider(color = BorderDark, thickness = 0.5.dp)
            rows.sortedByDescending { it.sessionCount }.forEach { row ->
                AccountDayRow(row)
            }
        }
    }
}

@Composable
private fun AccountDayRow(row: DailyStatRow) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "@${row.accountId}",
            color = Purple.copy(alpha = 0.8f), fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MicroStat("${row.sessionCount}",   Icons.Rounded.Loop,       TextSec)
            MicroStat("${row.videosWatched}",  Icons.Rounded.PlayCircle, Blue)
            MicroStat("${row.likes}",          Icons.Rounded.Favorite,   Pink)
            MicroStat("${row.follows}",        Icons.Rounded.PersonAdd,  Green)
        }
    }
}

@Composable
private fun MicroStat(value: String, icon: ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, null, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(10.dp))
        Text(value, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─────────────────────────────────────────────────────────────
//  Empty state
// ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyStatsView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.BarChart,
            contentDescription = null,
            tint     = TextMuted.copy(alpha = 0.4f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Chưa có dữ liệu",
            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Thống kê sẽ xuất hiện\nsau khi hoàn thành phiên farm đầu tiên",
            color     = TextMuted,
            fontSize  = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
    }
}
