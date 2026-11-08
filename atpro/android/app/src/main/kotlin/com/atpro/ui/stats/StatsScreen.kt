package com.atpro.ui.stats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.MonetizationOn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atpro.data.EarningsSummary
import com.atpro.db.dao.TotalsRow
import com.atpro.db.entity.DailyStatRow
import com.atpro.ui.f4f.WalletNumbers
import com.atpro.ui.f4f.fmt
import com.atpro.ui.theme.AnimatedNumber
import com.atpro.ui.theme.AtCard
import com.atpro.ui.theme.AtChip
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AtEmptyState
import com.atpro.ui.theme.AtHeroCard
import com.atpro.ui.theme.AtSectionLabel
import com.atpro.ui.theme.AtSegmented
import com.atpro.ui.theme.AtSkeleton
import com.atpro.ui.theme.AtStatTile
import com.atpro.ui.theme.AtTopBar
import com.atpro.ui.theme.AtType
import com.atpro.ui.theme.staggerIn

// ─────────────────────────────────────────────────────────────────────────────
//  StatsScreen — v1.10.0: làm lại.
//  Hai tab: "Nuôi acc" (hoạt động) và "Kiếm tiền" (thu nhập theo job + số liệu Follow4Follow).
//  Bỏ tab nền tảng demo (chỉ là trang trống) và danh sách chi tiết từng ngày/tài khoản → thay bằng biểu đồ.
// ─────────────────────────────────────────────────────────────────────────────

private val BgDark  get() = AtColors.Bg
private val Pink    get() = AtColors.Rose
private val Sky     get() = AtColors.Sky
private val Blue    get() = AtColors.Blue
private val Green   get() = AtColors.Success
private val Amber   get() = AtColors.Warning
private val TextSec get() = AtColors.TextSec
private val TextMuted get() = AtColors.TextMuted

@Composable
fun StatsScreen(
    vm:           StatsViewModel,
    onNavigateUp: (() -> Unit)? = null,
) {
    val state    by vm.uiState.collectAsStateWithLifecycle()
    val earnings by vm.earnings.collectAsStateWithLifecycle()
    val server   by vm.server.collectAsStateWithLifecycle()
    val range    by vm.range.collectAsStateWithLifecycle()
    var tab      by remember { mutableStateOf(0) }

    // Mở tab Kiếm tiền: đọc lại nhật ký + lấy số liệu phía server.
    LaunchedEffect(tab) {
        if (tab == 1) { vm.refreshEarnings(); vm.loadServer() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .statusBarsPadding()
    ) {
        AtTopBar("Thống kê", onBack = onNavigateUp)

        AtSegmented(
            options       = listOf("Nuôi acc", "Kiếm tiền"),
            selectedIndex = tab,
            onSelect      = { tab = it },
            modifier      = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatsViewModel.Range.values().forEach { r ->
                AtChip(text = r.label, selected = range == r, onClick = { vm.setRange(r) })
            }
        }
        Spacer(Modifier.height(8.dp))

        AnimatedContent(
            targetState    = tab,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label          = "stats_tab",
            modifier       = Modifier.weight(1f).fillMaxWidth(),
        ) { t ->
            if (t == 0) FarmStats(state) else EarnStats(earnings, server)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Tab 1 — Nuôi acc
// ─────────────────────────────────────────────────────────────

@Composable
private fun FarmStats(state: StatsUiState) {
    val totals = state.totals
    when {
        state.isLoading && totals == null -> StatsSkeleton()
        totals == null || (totals.sessionCount == 0 && state.dailyStats.isEmpty()) ->
            AtEmptyState(Icons.Rounded.BarChart, "Chưa có dữ liệu")
        else -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth().staggerIn(0), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AtStatTile("Phiên",  totals.sessionCount.toString(), Modifier.weight(1f))
                AtStatTile("Video",  totals.videosWatched.toString(), Modifier.weight(1f))
                AtStatTile("Thích",  totals.likes.toString(), Modifier.weight(1f), valueColor = Pink)
                AtStatTile("Follow", totals.follows.toString(), Modifier.weight(1f), valueColor = Sky)
            }

            val byDay = remember(state.dailyStats) {
                state.dailyStats.groupBy { it.date }.toSortedMap().map { (date, rows) ->
                    Triple(date, rows.sumOf { it.likes }, rows.sumOf { it.follows })
                }
            }
            if (byDay.isNotEmpty()) {
                ChartCard("Theo ngày", Modifier.staggerIn(1)) {
                    Legend(listOf(Pink to "Thích", Blue to "Follow"))
                    Spacer(Modifier.height(10.dp))
                    AtBarChart(
                        values     = byDay.map { (it.second + it.third).toFloat() },
                        lower      = byDay.map { it.second.toFloat() },
                        labels     = byDay.map { it.first.takeLast(5).replace('-', '/').split('/').reversed().joinToString("/") },
                        color      = Blue,
                        lowerColor = Pink,
                    )
                }
            }

            ChartCard("Tỉ lệ trên video", Modifier.staggerIn(2)) {
                AtRankBars(
                    items  = listOf(
                        "Thích"      to totals.likeRate,
                        "Follow"     to totals.followRate,
                        "Bình luận"  to totals.commentRate,
                    ),
                    color  = Pink,
                    format = { "%.1f%%".format(it) },
                )
            }

            val topAccounts = remember(state.dailyStats) {
                state.dailyStats.groupBy { it.accountId }
                    .map { (acc, rows) -> "@$acc" to rows.sumOf { it.likes + it.follows }.toFloat() }
                    .sortedByDescending { it.second }.take(5)
            }
            if (topAccounts.size > 1) {
                ChartCard("Tài khoản", Modifier.staggerIn(3)) {
                    AtRankBars(topAccounts, color = Sky)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Tab 2 — Kiếm tiền
// ─────────────────────────────────────────────────────────────

@Composable
private fun EarnStats(e: EarningsSummary, server: WalletNumbers?) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Số liệu phía server (đã gộp nhiều nguồn): xu chờ · làm hôm nay · tổng xu
        if (server != null) {
            AtSectionLabel("Follow4Follow", Modifier.staggerIn(0))
            Row(Modifier.fillMaxWidth().staggerIn(0), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AtStatTile("Xu chờ", server.pending.fmt(), Modifier.weight(1f), valueColor = Amber)
                AtStatTile("Làm hôm nay", "${server.todayJobs} job", Modifier.weight(1f))
                AtStatTile("Tổng xu", server.totalCoins.fmt(), Modifier.weight(1f))
            }
        }

        if (e.isEmpty) {
            AtEmptyState(Icons.Rounded.MonetizationOn, "Chưa có thu nhập")
            return@Column
        }

        AtSectionLabel("Trên máy này", Modifier.staggerIn(1))
        AtHeroCard(Modifier.fillMaxWidth().staggerIn(1)) {
            Text("Thu nhập", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedNumber(e.totalCoins.toInt(), AtType.Title.copy(fontSize = 36.sp), color = Color.White)
                Text("  xu", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, modifier = Modifier.padding(bottom = 5.dp))
                Spacer(Modifier.weight(1f))
                e.changePercent?.let { pct ->
                    val up = pct >= 0
                    Text(
                        "${if (up) "▲" else "▼"} ${"%.0f".format(kotlin.math.abs(pct))}%",
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.18f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${e.totalJobs} job  ·  TB ${coin(e.avgPerJob)}/job",
                color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
            )
        }

        Row(Modifier.fillMaxWidth().staggerIn(2), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AtStatTile("Hôm nay", coin(e.todayCoins), Modifier.weight(1f), valueColor = Green)
            AtStatTile("Job hôm nay", e.todayJobs.toString(), Modifier.weight(1f))
            AtStatTile("Ngày tốt nhất", e.bestDay?.let { coin(it.coins) } ?: "—", Modifier.weight(1f))
        }

        ChartCard("Theo ngày", Modifier.staggerIn(3)) {
            Legend(listOf(Sky to "Tim", Blue to "Follow"))
            Spacer(Modifier.height(10.dp))
            AtBarChart(
                values     = e.days.map { it.coins.toFloat() },
                lower      = e.days.map { it.likeCoins.toFloat() },
                labels     = e.days.map { it.label },
                color      = Blue,
                lowerColor = Sky,
                format     = { coin(it.toDouble()) + " xu" },
            )
        }

        if (e.days.size > 1) {
            ChartCard("Cộng dồn", Modifier.staggerIn(4)) {
                AtAreaChart(e.cumulative, e.days.map { it.label }, color = Green)
            }
        }

        ChartCard("Loại job", Modifier.staggerIn(5)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AtDonutChart(
                    parts = listOf(e.likeCoins.toFloat() to Sky, e.followCoins.toFloat() to Blue),
                    centerTop = "${e.likeJobs + e.followJobs}",
                    centerBottom = "job",
                )
                Spacer(Modifier.padding(start = 18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AtLegendRow(Sky,  "Tim · ${e.likeJobs} job",     coin(e.likeCoins))
                    AtLegendRow(Blue, "Follow · ${e.followJobs} job", coin(e.followCoins))
                }
            }
        }

        ChartCard("Theo giờ", Modifier.staggerIn(6)) {
            AtBarChart(
                values = e.hourCoins.map { it.toFloat() },
                labels = (0..23).map { "${it}h" },
                color  = Amber,
                height = 110.dp,
                format = { coin(it.toDouble()) + " xu" },
            )
        }

        if (e.topAccounts.isNotEmpty()) {
            ChartCard("Tài khoản", Modifier.staggerIn(7)) {
                AtRankBars(
                    items  = e.topAccounts.map { "@${it.first}" to it.second.toFloat() },
                    color  = Green,
                    format = { coin(it.toDouble()) },
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ─────────────────────────────────────────────────────────────
//  Thành phần chung
// ─────────────────────────────────────────────────────────────

@Composable
private fun ChartCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AtCard(modifier.fillMaxWidth()) {
        Text(title, color = AtColors.TextPrim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun Legend(items: List<Pair<Color, String>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (c, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(3.dp)).background(c))
                Spacer(Modifier.width(6.dp))
                Text(label, color = TextSec, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StatsSkeleton() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AtSkeleton(Modifier.fillMaxWidth().height(64.dp))
        AtSkeleton(Modifier.fillMaxWidth().height(200.dp))
        AtSkeleton(Modifier.fillMaxWidth().height(120.dp))
    }
}

/** 12 → "12", 12.5 → "12,5" (xu thường là số nguyên; chỉ hiện số lẻ khi có). */
private fun coin(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().fmt() else java.util.Locale.getDefault().let { "%.1f".format(it, v) }
