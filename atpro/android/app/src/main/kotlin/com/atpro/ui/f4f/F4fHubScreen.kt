package com.atpro.ui.f4f

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atpro.tasks.f4f.F4fAuth
import com.atpro.tasks.f4f.F4fAuthManager
import com.atpro.tasks.f4f.F4fAuthState
import com.atpro.tasks.f4f.F4fLeaderEntry
import com.atpro.tasks.f4f.F4fLeaderboard
import com.atpro.ui.tasks.F4fLoginDialog
import com.atpro.ui.theme.AtBadge
import com.atpro.ui.theme.AtButton
import com.atpro.ui.theme.AtButtonStyle
import com.atpro.ui.theme.AtCard
import com.atpro.ui.theme.staggerIn
import com.atpro.ui.theme.AtChip
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AtDialog
import com.atpro.ui.theme.AtEmptyState
import com.atpro.ui.theme.AtHeroCard
import com.atpro.ui.theme.AtSegmented
import com.atpro.ui.theme.AtStatTile
import com.atpro.ui.theme.AtTopBar
import com.atpro.ui.theme.AtType

// ─────────────────────────────────────────────────────────────────────────────
//  Ví Follow4Follow — Tổng quan · Xếp hạng · Rút tiền · Lịch sử · Thông báo (v1.10.0: bỏ tab Giới thiệu)
//  Toàn màn hình, mở từ tab Dịch vụ. Dữ liệu qua F4fService (xem F4fHubViewModel).
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun F4fHubScreen(initialTab: F4fHubTab, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val vm: F4fHubViewModel = viewModel(factory = F4fHubViewModel.Factory(context))
    val auth = remember { F4fAuth.manager(context) }
    val authState by auth.state.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()

    var tab by remember(initialTab) { mutableStateOf(initialTab) }
    var showLogin by remember { mutableStateOf(false) }
    val loggedIn = authState as? F4fAuthState.LoggedIn

    BackHandler(onBack = onBack)
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(tab, loggedIn != null) {
        if (loggedIn == null) return@LaunchedEffect
        // Lần mở đầu: tải mới (VM sống lâu hơn màn hình nên dữ liệu cũ có thể đã lỗi thời); sau đó chỉ tải tab chưa có.
        if (!opened) { opened = true; vm.refresh(tab) } else vm.ensureLoaded(tab)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(AtColors.Bg)
            .statusBarsPadding(),
    ) {
        AtTopBar(
            title    = "Ví Follow4Follow",
            subtitle = loggedIn?.username?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "Chưa đăng nhập",
            onBack   = onBack,
            actions  = {
                if (loggedIn != null) {
                    IconButton(onClick = { vm.refresh(tab) }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Làm mới", tint = AtColors.TextSec, modifier = Modifier.size(22.dp))
                    }
                }
            },
        )

        if (loggedIn == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AtEmptyState(icon = Icons.Rounded.Lock, title = "Chưa đăng nhập")
                    AtButton("Đăng nhập", { showLogin = true }, height = 48.dp)
                }
            }
        } else {
            // ── Chọn tab ──
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(F4fHubTab.values().toList()) { t ->
                    AtChip(text = t.label, selected = tab == t, onClick = { tab = t }, icon = t.icon)
                }
            }
            Spacer(Modifier.height(12.dp))

            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(140)) },
                label = "f4f_tab",
                modifier = Modifier.weight(1f),
            ) { current ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 28.dp),
                ) {
                    when (current) {
                        F4fHubTab.OVERVIEW      -> OverviewPanel(state, vm, onOpen = { tab = it })
                        F4fHubTab.LEADERBOARD   -> LeaderboardPanel(state, vm, auth)
                        F4fHubTab.WITHDRAW      -> WithdrawPanel(state, vm, loggedIn.balance)
                        F4fHubTab.HISTORY       -> HistoryPanel(state, vm)
                        F4fHubTab.NOTIFICATIONS -> NotificationsPanel(state, vm)
                    }
                }
            }
        }
    }

    state.result?.let { res ->
        AtDialog(
            onDismiss   = vm::consumeResult,
            title       = if (res.success) "Thành công" else "Chưa thực hiện được",
            message     = res.message,
            confirmText = "Đóng",
            onConfirm   = vm::consumeResult,
            dismissText = null,
        )
    }
    if (showLogin) F4fLoginDialog(onDismiss = { showLogin = false })
}

// ─────────────────────────────────────────────────────────────────────────────
//  Tổng quan
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun OverviewPanel(state: F4fHubUiState, vm: F4fHubViewModel, onOpen: (F4fHubTab) -> Unit) {
    val stats = (state.earnStats as? F4fLoad.Ok)?.data
    PanelColumn {
        LoadBlock(state.dashboard, onRetry = { vm.refresh(F4fHubTab.OVERVIEW) }) { d ->
            val n = walletNumbers(d, stats)
            AtHeroCard(Modifier.fillMaxWidth().staggerIn(0)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Số dư", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (!d.levelName.isNullOrBlank()) {
                        Text(
                            d.levelName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    F4fAuthManager.formatCoin(d.balance) ?: "—",
                    style = AtType.Title.copy(fontSize = 34.sp), color = Color.White,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeroAction("Rút tiền", Icons.Rounded.AccountBalanceWallet, Modifier.weight(1f)) { onOpen(F4fHubTab.WITHDRAW) }
                    HeroAction("Lịch sử", Icons.Rounded.History, Modifier.weight(1f)) { onOpen(F4fHubTab.HISTORY) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AtStatTile("Xu chờ", n.pending.fmt(), Modifier.weight(1f), valueColor = AtColors.Warning)
                AtStatTile("Làm hôm nay", "${n.todayJobs} job", Modifier.weight(1f))
                AtStatTile("Tổng xu", n.totalCoins.fmt(), Modifier.weight(1f))
            }
            if (n.todayCoins > 0L || n.totalJobs > 0) {
                Text(
                    buildString {
                        append("Hôm nay +").append(n.todayCoins.fmt()).append(" xu")
                        if (n.totalJobs > 0) append("  ·  ").append(n.totalJobs).append(" job tổng")
                    },
                    color = AtColors.TextMuted, fontSize = 12.sp,
                )
            }
        }

        LoadBlock(state.jobRewards, onRetry = { vm.refresh(F4fHubTab.OVERVIEW) }) { rewards ->
            if (rewards.isNotEmpty()) {
                GroupTitle("Job còn lại hôm nay")
                AtCard(Modifier.fillMaxWidth().staggerIn(1)) {
                    rewards.forEach { r ->
                        StatLine(
                            "${r.platform.replaceFirstChar { it.uppercase() }} · ${r.typeJob}",
                            "${r.remains}/${r.jobs} · ${r.maxRewardFormatted}",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AtColors.TextSec, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, color = AtColors.TextPrim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Bảng xếp hạng
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LeaderboardPanel(state: F4fHubUiState, vm: F4fHubViewModel, auth: F4fAuthManager) {
    val me = (auth.state.collectAsStateWithLifecycle().value as? F4fAuthState.LoggedIn)?.username.orEmpty()
    PanelColumn {
        LoadBlock(state.leaderboard, onRetry = { vm.refresh(F4fHubTab.LEADERBOARD) }) { board ->
            if (board.entries.isEmpty()) {
                AtEmptyState(Icons.Rounded.EmojiEvents, "Chưa có xếp hạng")
            } else {
                board.myRank?.let { rank ->
                    AtCard(Modifier.fillMaxWidth().staggerIn(2), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Hạng của bạn", color = AtColors.TextSec, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text("#$rank", color = AtColors.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Podium(board, me)
                val rest = board.entries.filter { it.rank > 3 }
                if (rest.isNotEmpty()) {
                    AtCard(Modifier.fillMaxWidth().staggerIn(0), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                        rest.forEach { e -> LeaderRow(e, mine = e.username.equals(me, ignoreCase = true)) }
                    }
                }
            }
        }
    }
}

/** Số xu hiển thị: số đã đọc được; nếu server trả chuỗi không đọc ra số thì hiện nguyên văn; không có gì thì "—". */
private fun F4fLeaderEntry.coinLabel(): String =
    if (coins > 0L) "${coins.fmt()} xu" else coinsText.ifBlank { "—" }

private val Gold = Color(0xFFF5B301)
private val Silver = Color(0xFF9AA7B8)
private val Bronze = Color(0xFFC77B3B)

@Composable
private fun Podium(board: F4fLeaderboard, me: String) {
    val byRank = board.entries.sortedBy { it.rank }
    val first = byRank.getOrNull(0)
    val second = byRank.getOrNull(1)
    val third = byRank.getOrNull(2)
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        PodiumColumn(second, 2, Silver, 64.dp, me, Modifier.weight(1f))
        PodiumColumn(first, 1, Gold, 92.dp, me, Modifier.weight(1f))
        PodiumColumn(third, 3, Bronze, 48.dp, me, Modifier.weight(1f))
    }
}

@Composable
private fun PodiumColumn(entry: F4fLeaderEntry?, place: Int, ring: Color, pedestal: androidx.compose.ui.unit.Dp, me: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (entry != null) {
            val size = if (place == 1) 68.dp else 54.dp
            Box(
                Modifier.size(size).clip(CircleShape).background(AtColors.AccentSoft).border(3.dp, ring, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.username.take(1).uppercase().ifBlank { "?" },
                    color = AtColors.Accent, fontSize = if (place == 1) 24.sp else 19.sp, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                entry.username, color = AtColors.TextPrim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(entry.coinLabel(), color = AtColors.TextMuted, fontSize = 12.sp)
            if (entry.username.equals(me, ignoreCase = true)) AtBadge("Bạn", AtColors.Accent, Modifier.padding(top = 4.dp))
        } else {
            Spacer(Modifier.height(40.dp))
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(pedestal)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .background(ring.copy(alpha = 0.18f))
                .border(1.dp, ring.copy(alpha = 0.45f), RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text("$place", color = ring, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun LeaderRow(e: F4fLeaderEntry, mine: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (mine) AtColors.AccentSoft else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${e.rank}", color = AtColors.TextMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(32.dp))
        Box(Modifier.size(34.dp).clip(CircleShape).background(AtColors.SurfaceHigh), contentAlignment = Alignment.Center) {
            Text(e.username.take(1).uppercase().ifBlank { "?" }, color = AtColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(e.username, color = AtColors.TextPrim, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (mine) AtBadge("Bạn", AtColors.Accent, Modifier.padding(end = 8.dp))
        Text(e.coinLabel(), color = AtColors.TextPrim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Lịch sử job
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HistoryPanel(state: F4fHubUiState, vm: F4fHubViewModel) {
    PanelColumn {
        AtSegmented(
            options = listOf("Follow", "Like"),
            selectedIndex = if (state.historyAction == "like") 1 else 0,
            onSelect = { vm.setHistoryAction(if (it == 1) "like" else "follow") },
        )
        LoadBlock(state.history, onRetry = { vm.refresh(F4fHubTab.HISTORY) }) { items ->
            if (items.isEmpty()) {
                AtEmptyState(Icons.Rounded.History, "Chưa có job")
            } else {
                AtCard(Modifier.fillMaxWidth().staggerIn(1), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                    items.forEach { h ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    h.objectId.ifBlank { "Job #${h.jobId}" },
                                    color = AtColors.TextPrim, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(h.createdAt, color = AtColors.TextMuted, fontSize = 11.sp)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("+${h.coin}", color = AtColors.Success, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                if (h.statusLabel.isNotBlank()) AtBadge(h.statusLabel, statusTone(h.statusLabel), Modifier.padding(top = 3.dp))
                            }
                        }
                    }
                }
                if (state.historyHasMore) {
                    AtButton(
                        if (state.historyMoreBusy) "Đang tải…" else "Tải thêm",
                        vm::loadMoreHistory,
                        enabled = !state.historyMoreBusy,
                        style = AtButtonStyle.Tonal,
                        height = 46.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Thông báo
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NotificationsPanel(state: F4fHubUiState, vm: F4fHubViewModel) {
    PanelColumn {
        LoadBlock(state.notifications, onRetry = { vm.refresh(F4fHubTab.NOTIFICATIONS) }) { n ->
            if (n.items.isEmpty()) {
                AtEmptyState(Icons.Rounded.NotificationsNone, "Chưa có thông báo")
            } else {
                if (n.unread > 0) Text("${n.unread} chưa đọc", color = AtColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                n.items.forEach { item ->
                    AtCard(Modifier.fillMaxWidth().staggerIn(2), contentPadding = PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape)
                                    .background(if (item.read) AtColors.HairlineHi else AtColors.Blue),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title.ifBlank { "Thông báo" }, color = AtColors.TextPrim, fontSize = 14.sp, fontWeight = if (item.read) FontWeight.Medium else FontWeight.Bold)
                                if (item.message.isNotBlank()) {
                                    Spacer(Modifier.height(3.dp))
                                    Text(item.message, color = AtColors.TextSec, fontSize = 13.sp, lineHeight = 18.sp)
                                }
                                Spacer(Modifier.height(5.dp))
                                Text(item.createdAt, color = AtColors.TextMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
