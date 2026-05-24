package com.atpro.ui.stats

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atpro.db.entity.DailyStatRow
import com.atpro.db.dao.TotalsRow

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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
        // ── Summary card ──
        item { SummaryCard(state.totals) }

        // ── Daily section header ──
        if (state.dailyStats.isNotEmpty()) {
            item {
                Text(
                    "Theo ngày",
                    color = TextSec, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // Group daily stats by date for display
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
            SummaryMetric(
                value = "${totals.sessionCount}",
                label = "Phiên",
                color = Purple,
            )
            SummaryMetric(
                value = "${totals.videosWatched}",
                label = "Video",
                color = Blue,
            )
            SummaryMetric(
                value = "${totals.likes}",
                label = "Thích",
                color = Pink,
            )
            SummaryMetric(
                value = "${totals.follows}",
                label = "Theo dõi",
                color = Green,
            )
        }
    }
}

@Composable
private fun SummaryMetric(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
        // Date header + day totals
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
            Text(
                "${dayTotalSessions}p · ${dayTotalVideos}v · ❤️${dayTotalLikes} · 👥${dayTotalFollows}",
                color = TextMuted, fontSize = 11.sp,
            )
        }

        // Per-account rows (only if multiple accounts on this day)
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
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "@${row.accountId}",
            color = Purple.copy(alpha = 0.8f), fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MicroStat("${row.sessionCount}", "p",  TextSec)
            MicroStat("${row.videosWatched}", "v", Blue)
            MicroStat("${row.likes}", "❤️",        Pink)
            MicroStat("${row.follows}", "👥",      Green)
        }
    }
}

@Composable
private fun MicroStat(value: String, suffix: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(value, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(suffix, color = TextMuted, fontSize = 10.sp)
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
        Text("📊", fontSize = 40.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "Chưa có dữ liệu",
            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Thống kê sẽ xuất hiện\nsau khi hoàn thành phiên farm đầu tiên",
            color = TextMuted, fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
    }
}
