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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atpro.db.dao.TotalsRow
import com.atpro.db.entity.DailyStatRow

// ── Design tokens ─────────────────────────────────────────────
private val BgDark    = Color(0xFF0D0D14)
private val CardDark  = Color(0xFF141420)
private val BorderDark= Color(0xFF252538)
private val Purple    = Color(0xFF7C6FE0)
private val Pink      = Color(0xFFEC4899)
private val Green     = Color(0xFF14C98F)
private val Blue      = Color(0xFF60A5FA)
private val Gold      = Color(0xFFF5A623)
private val TextSec   = Color(0xFF9CA3AF)
private val TextMuted = Color(0xFF5C5C78)

// ── Các nền tảng thống kê ─────────────────────────────────────
private enum class StatPlatform(val label: String, val color: Color) {
    TIKTOK   ("TikTok",    Color(0xFF69C9D0)),
    FACEBOOK ("Facebook",  Color(0xFF1877F2)),
    X        ("X",         Color(0xFF1D9BF0)),
    INSTAGRAM("Instagram", Color(0xFF833AB4)),
    THREADS  ("Threads",   Color(0xFFAAAAAA)),
    SNAPCHAT ("Snapchat",  Color(0xFFFFFC00)),
}

// ─────────────────────────────────────────────────────────────
//  Root screen
// ─────────────────────────────────────────────────────────────

@Composable
fun StatsScreen(
    vm:           StatsViewModel,
    onNavigateUp: (() -> Unit)?    = null,
) {
    val state      by vm.uiState.collectAsStateWithLifecycle()
    val range      by vm.range.collectAsStateWithLifecycle()
    var platform   by remember { mutableStateOf(StatPlatform.TIKTOK) }

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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Thống kê", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Tổng hợp hoạt động theo nền tảng", color = TextMuted, fontSize = 11.sp)
            }
        }

        // ── Platform tabs ──
        LazyRow(
            contentPadding        = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(StatPlatform.entries.toList()) { p ->
                FilterChip(
                    selected = platform == p,
                    onClick  = { platform = p },
                    label    = { Text(p.label, fontSize = 12.sp) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = p.color.copy(alpha = 0.15f),
                        selectedLabelColor     = p.color,
                        containerColor         = CardDark,
                        labelColor             = TextSec,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = BorderDark,
                        selectedBorderColor = p.color.copy(alpha = 0.5f),
                        enabled = true, selected = platform == p,
                    ),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Body theo platform ──
        when (platform) {
            StatPlatform.TIKTOK -> TikTokStatsBody(state, range, vm)
            else                -> DemoStatsBody(platform)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  TikTok stats (nội dung cũ)
// ─────────────────────────────────────────────────────────────

@Composable
private fun TikTokStatsBody(
    state: StatsUiState,
    range: StatsViewModel.Range,
    vm:    StatsViewModel,
) {
    // v1.3.10: Card thống kê Golike — số liệu TRỰC TIẾP hiện tại (số dư, tiền
    // đang giữ, rank), không phụ thuộc range selector bên dưới (range chỉ áp
    // dụng cho thống kê phiên nuôi acc cục bộ từ SessionDao).
    val golikeState by vm.golikeStats.collectAsStateWithLifecycle()
    GolikeStatsCard(golikeState, onRefresh = vm::refreshGolikeStats)
    Spacer(Modifier.height(8.dp))

    // Range chips
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
    AnimatedContent(
        targetState   = state.isLoading,
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

// ─────────────────────────────────────────────────────────────
// ─────────────────────────────────────────────────────────────
//  Demo platform stats placeholder [v1.2.4]
// ─────────────────────────────────────────────────────────────

@Composable
private fun DemoStatsBody(platform: StatPlatform) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.BarChart, null, tint = platform.color.copy(alpha = 0.4f), modifier = Modifier.size(52.dp))
            Text(platform.label, color = platform.color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Chế độ Demo — thống kê chưa được theo dõi.\nChạy phiên nuôi acc để xem dữ liệu.",
                color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(platform.color.copy(alpha = 0.08f))
                    .border(0.5.dp, platform.color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("DEMO", color = platform.color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

        // v1.2.3: Card tỷ lệ % tương tác — like/follow/comment trên mỗi video xem.
        item { EngagementRateCard(state.totals) }

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
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.45f))
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(listOf(Purple.copy(alpha = 0.24f), Pink.copy(alpha = 0.12f)))
            )
            .border(1.dp, Purple.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
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
//  Golike stats card [v1.3.10] — số dư/tiền giữ/rank trực tiếp từ Golike
// ─────────────────────────────────────────────────────────────

@Composable
private fun GolikeStatsCard(state: GolikeStatsUiState, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.45f))
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Gold.copy(alpha = 0.20f), Purple.copy(alpha = 0.10f))))
            .border(1.dp, Gold.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.MonetizationOn, null, tint = Gold, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("Golike", color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (state.isLoggedIn && state.rank.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Purple.copy(alpha = 0.16f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(state.rank, color = Purple, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRefresh, modifier = Modifier.size(22.dp)) {
                Icon(Icons.Rounded.Refresh, null, tint = TextMuted, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            state.isLoading -> Box(
                Modifier.fillMaxWidth().height(48.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Gold, modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }

            !state.isLoggedIn -> Text(
                "Chưa đăng nhập Golike — vào tab Dịch vụ để đăng nhập",
                color = TextMuted, fontSize = 12.sp,
            )

            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SummaryMetric(formatCoin(state.coin),        "Số dư",      Icons.Rounded.MonetizationOn, Gold)
                SummaryMetric(formatCoin(state.holdCoin),    "Đang giữ",   Icons.Rounded.HourglassTop,   TextSec)
                SummaryMetric(formatCoin(state.pendingCoin), "Chờ duyệt",  Icons.Rounded.Schedule,       Blue)
            }
        }
    }
}

/** Format số coin: bỏ ".0" thừa nếu là số nguyên, giữ 1 chữ số thập phân nếu lẻ. */
private fun formatCoin(value: Double): String {
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
    else rounded.toString()
}

// ─────────────────────────────────────────────────────────────
//  Engagement rate card [v1.2.3] — tỷ lệ % like/follow/comment trên mỗi video
// ─────────────────────────────────────────────────────────────

@Composable
private fun EngagementRateCard(totals: TotalsRow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 5.dp, shape = RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.4f))
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1F1F35), CardDark)))
            .border(1.dp, BorderDark, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Percent, null, tint = TextSec, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "Tỷ lệ tương tác / video",
                color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "TB ${formatRate(totals.avgVideosPerSession)} video/phiên",
                color = TextMuted, fontSize = 11.sp,
            )
        }

        RateBar("Thích",      totals.likeRate,    Pink,  Icons.Rounded.Favorite)
        RateBar("Theo dõi",   totals.followRate,  Green, Icons.Rounded.PersonAdd)
        RateBar("Bình luận",  totals.commentRate, Blue,  Icons.Rounded.ChatBubble)
    }
}

/** Thanh tiến trình hiển thị tỷ lệ % (0–100), kẹp ở 100% nếu vượt (vd nhiều like/video). */
@Composable
private fun RateBar(label: String, percent: Float, color: Color, icon: ImageVector) {
    val clamped = (percent / 100f).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color.copy(alpha = 0.8f), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = TextSec, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                "${formatRate(percent)}%",
                color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
        }
        LinearProgressIndicator(
            progress   = { clamped },
            modifier   = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(50)),
            color      = color,
            trackColor = color.copy(alpha = 0.12f),
        )
    }
}

/** Format 1 chữ số sau dấu phẩy, bỏ ".0" thừa (vd 12.0 → "12", 12.5 → "12.5"). */
private fun formatRate(value: Float): String {
    val rounded = Math.round(value * 10f) / 10f
    return if (rounded == rounded.toLong().toFloat()) rounded.toLong().toString()
    else rounded.toString()
}

// ─────────────────────────────────────────────────────────────
//  Day group
// ─────────────────────────────────────────────────────────────

@Composable
private fun DayGroup(date: String, rows: List<DailyStatRow>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 4.dp, shape = RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(RoundedCornerShape(16.dp))
            .background(CardDark)
            .border(1.dp, BorderDark.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val dayTotalSessions = rows.sumOf { it.sessionCount }
        val dayTotalVideos   = rows.sumOf { it.videosWatched }
        val dayTotalLikes    = rows.sumOf { it.likes }
        val dayTotalFollows  = rows.sumOf { it.follows }
        val dayLikeRate      = if (dayTotalVideos > 0) dayTotalLikes * 100f / dayTotalVideos else 0f

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                date,
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            // v1.2.3: tỷ lệ % like trong ngày — chip nhỏ cạnh ngày.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Pink.copy(alpha = 0.14f))
                    .border(1.dp, Pink.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text("${formatRate(dayLikeRate)}% thích", color = Pink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
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
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(50))
                .background(Brush.radialGradient(listOf(Purple.copy(alpha = 0.16f), Color.Transparent))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.BarChart,
                contentDescription = null,
                tint     = TextMuted.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp),
            )
        }
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
