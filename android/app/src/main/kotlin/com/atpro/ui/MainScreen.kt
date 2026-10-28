package com.atpro.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.atpro.ui.theme.AtDivider
import com.atpro.ui.theme.pressClickable
import com.atpro.ui.theme.AtColors
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atpro.ui.accounts.AccountsScreen
import com.atpro.ui.accounts.AccountsViewModel
import com.atpro.ui.dashboard.DashboardScreen
import com.atpro.ui.dashboard.DashboardViewModel
import com.atpro.ui.dashboard.UpdateAvailableDialog
import com.atpro.ui.schedule.ScheduleScreen
import com.atpro.ui.schedule.ScheduleViewModel
import com.atpro.ui.services.ServicesScreen
import com.atpro.ui.stats.StatsScreen
import com.atpro.ui.stats.StatsViewModel

// ── Design tokens ────────────────────────────────────────────
private val BgDark    = AtColors.Bg
private val NavBg     = AtColors.Surface
private val Purple    = AtColors.Accent
private val TextMuted = AtColors.TextMuted

// ─────────────────────────────────────────────────────────────
//  Tab definition
//  v1.2.9: Xóa LOGS — Nhật ký chuyển vào Cài đặt.
// ─────────────────────────────────────────────────────────────

private enum class Tab(
    val label: String,
    val icon:  ImageVector,
) {
    DASHBOARD("Tổng quan", Icons.Rounded.Home),
    SERVICES ("Dịch vụ",   Icons.Rounded.Layers),
    STATS    ("Thống kê",  Icons.Rounded.BarChart),
    ACCOUNTS ("Tài khoản", Icons.Rounded.ManageAccounts),
    SCHEDULE ("Lịch",      Icons.Rounded.Schedule),
}

// ─────────────────────────────────────────────────────────────
//  MainScreen — shell duy nhất chứa tất cả tab
// ─────────────────────────────────────────────────────────────

@Composable
fun MainScreen(
    dashboardVm: DashboardViewModel,
    statsVm:     StatsViewModel,
    accountsVm:  AccountsViewModel,
    scheduleVm:  ScheduleViewModel,
) {
    var selectedTab by remember { mutableStateOf(Tab.DASHBOARD) }
    val dashboardState by dashboardVm.uiState.collectAsStateWithLifecycle()

    // v1.2.9: Kiểm tra phiên bản mới ở cấp MainScreen (không phải DashboardScreen) —
    // chạy đúng 1 lần cho cả phiên app, không bị huỷ/refire khi chuyển tab qua lại.
    // Dialog cũng được render ở dưới (ngoài Scaffold) nên luôn hiện đè lên mọi tab,
    // không chỉ riêng tab Dashboard.
    LaunchedEffect(Unit) { dashboardVm.checkForUpdate() }

    Scaffold(
        containerColor      = BgDark,
        contentWindowInsets = WindowInsets(0),
        bottomBar = { AtBottomBar(selected = selectedTab, onSelect = { selectedTab = it }) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            AnimatedContent(
                targetState   = selectedTab,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (fadeIn(tween(220)) + slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) {
                        if (forward) it / 12 else -it / 12
                    }) togetherWith (fadeOut(tween(160)) + slideOutHorizontally(tween(220)) {
                        if (forward) -it / 12 else it / 12
                    })
                },
                label = "tab_transition",
            ) { tab ->
                when (tab) {
                    Tab.DASHBOARD -> DashboardScreen(vm = dashboardVm)

                    Tab.SERVICES  -> ServicesScreen(
                        onOpenFarmService   = {
                            dashboardVm.setServiceMode(com.atpro.automation.ServiceMode.FARM)
                            selectedTab = Tab.DASHBOARD
                        },
                        onOpenTaskService   = {
                            dashboardVm.setServiceMode(com.atpro.automation.ServiceMode.TASK)
                            selectedTab = Tab.DASHBOARD
                        },
                        onOpenFacebookService = {
                            dashboardVm.setServiceMode(com.atpro.automation.ServiceMode.FACEBOOK_NURTURE)
                            selectedTab = Tab.DASHBOARD
                        },
                        onOpenXService = {
                            dashboardVm.setServiceMode(com.atpro.automation.ServiceMode.X_NURTURE)
                            selectedTab = Tab.DASHBOARD
                        },
                        onOpenInstagramService = {
                            dashboardVm.setServiceMode(com.atpro.automation.ServiceMode.INSTAGRAM_NURTURE)
                            selectedTab = Tab.DASHBOARD
                        },
                        onOpenThreadsService = {
                            dashboardVm.setServiceMode(com.atpro.automation.ServiceMode.THREADS_NURTURE)
                            selectedTab = Tab.DASHBOARD
                        },
                        onOpenSnapchatService = {
                            dashboardVm.setServiceMode(com.atpro.automation.ServiceMode.SNAPCHAT_NURTURE)
                            selectedTab = Tab.DASHBOARD
                        },
                    )

                    Tab.STATS    -> StatsScreen(
                        vm           = statsVm,
                        onNavigateUp = { selectedTab = Tab.DASHBOARD },
                    )
                    Tab.ACCOUNTS -> AccountsScreen(
                        vm           = accountsVm,
                        onNavigateUp = { selectedTab = Tab.DASHBOARD },
                    )
                    Tab.SCHEDULE -> ScheduleScreen(
                        vm           = scheduleVm,
                        onNavigateUp = { selectedTab = Tab.DASHBOARD },
                    )
                }
            }
        }
    }

    // v1.2.9: Update dialog ở cấp MainScreen — luôn hiện đè lên mọi tab (không chỉ
    // Dashboard) vì state.updateInfo sống trong dashboardVm, ViewModel chung cho cả app.
    dashboardState.updateInfo?.let { info ->
        UpdateAvailableDialog(
            info             = info,
            downloadProgress = dashboardState.downloadProgress,
            onDismiss        = dashboardVm::dismissUpdate,
            onUpdate         = { dashboardVm.startUpdate() },
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  v1.4.0 — Thanh điều hướng dưới: phẳng, vạch chỉ báo trượt mượt, nhấn co nhẹ.
//  (Thay NavigationBar mặc định của Material — viên thuốc chỉ báo trông "mặc định".)
// ─────────────────────────────────────────────────────────────
@Composable
private fun AtBottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(
        Modifier
            .background(BgDark)
            .navigationBarsPadding()
    ) {
        AtDivider()
        BoxWithConstraints(Modifier.fillMaxWidth().height(60.dp)) {
            val itemW = maxWidth / Tab.entries.size
            val indicatorX by animateDpAsState(
                targetValue   = itemW * selected.ordinal,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 450f),
                label         = "nav_indicator",
            )
            Box(
                Modifier
                    .offset(x = indicatorX + itemW / 2 - 12.dp)
                    .width(24.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(com.atpro.ui.theme.AtColors.TextPrim)
            )
            Row(Modifier.fillMaxSize()) {
                Tab.entries.forEach { tab ->
                    val tint by animateColorAsState(
                        targetValue   = if (tab == selected) com.atpro.ui.theme.AtColors.TextPrim else TextMuted,
                        animationSpec = tween(180),
                        label         = "nav_tint",
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pressClickable(pressedScale = 0.92f) { onSelect(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint     = tint,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            tab.label,
                            color      = tint,
                            fontSize   = 10.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines   = 1,
                            softWrap   = false,
                            overflow   = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
