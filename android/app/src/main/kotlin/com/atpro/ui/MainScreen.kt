package com.atpro.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atpro.automation.ServiceMode
import com.atpro.ui.accounts.AccountsScreen
import com.atpro.ui.accounts.AccountsViewModel
import com.atpro.ui.dashboard.DashboardScreen
import com.atpro.ui.dashboard.DashboardViewModel
import com.atpro.ui.golike.GolikeViewModel
import com.atpro.ui.logs.LogsScreen
import com.atpro.ui.logs.LogsViewModel
import com.atpro.ui.services.ServicesScreen
import com.atpro.ui.stats.StatsScreen
import com.atpro.ui.stats.StatsViewModel

// ── Design tokens (đồng bộ với DashboardScreen) ───────────────
private val BgDark    = Color(0xFF0D0D14)
private val NavBg     = Color(0xFF13131F)
private val Purple    = Color(0xFF6C63FF)
private val TextMuted = Color(0xFF6B7280)

// ─────────────────────────────────────────────────────────────
//  Tab definition
// ─────────────────────────────────────────────────────────────

private enum class Tab(
    val label: String,
    val icon:  ImageVector,
) {
    DASHBOARD("Dashboard",  Icons.Rounded.Home),
    SERVICES ("Dịch vụ",   Icons.Rounded.Layers),          // v1.2.6: đổi từ GridView → Layers (phù hợp "dịch vụ")
    STATS    ("Thống kê",  Icons.Rounded.BarChart),
    ACCOUNTS ("Tài khoản", Icons.Rounded.ManageAccounts),
    LOGS     ("Nhật ký",   Icons.Rounded.EventNote),        // v1.2.6: đổi từ Assignment → EventNote (nhật ký cụ thể hơn)
}

// ─────────────────────────────────────────────────────────────
//  MainScreen — shell duy nhất chứa tất cả tab
//
//  [v1.1.6] Thêm tab "Dịch vụ" (vị trí 2) giữa Dashboard và
//  Thống kê — cho phép chọn Nuôi tài khoản / Kiếm tiền.
// ─────────────────────────────────────────────────────────────

@Composable
fun MainScreen(
    dashboardVm:    DashboardViewModel,
    statsVm:        StatsViewModel,
    accountsVm:     AccountsViewModel,
    logsVm:         LogsViewModel,
    golikeVm:       GolikeViewModel,
    onOpenGolikeLogin: () -> Unit = {},
) {
    var selectedTab by remember { mutableStateOf(Tab.DASHBOARD) }
    val golikeState by golikeVm.state.collectAsState()

    Scaffold(
        containerColor      = BgDark,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(
                modifier        = Modifier.navigationBarsPadding(),
                containerColor  = NavBg,
                tonalElevation  = 0.dp,
            ) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick  = { selectedTab = tab },
                        icon     = {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        label = {
                            Text(
                                tab.label,
                                fontSize = 10.sp,
                                maxLines = 1,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor   = Purple,
                            selectedTextColor   = Purple,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted,
                            indicatorColor      = Purple.copy(alpha = 0.12f),
                        ),
                    )
                }
            }
        },
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
                    Tab.DASHBOARD -> DashboardScreen(vm = dashboardVm, golikeVm = golikeVm)

                    Tab.SERVICES  -> ServicesScreen(
                        // v1.2.1: Mode switch + navigate to Dashboard
                        onOpenFarmService = {
                            dashboardVm.setServiceMode(ServiceMode.FARM)
                            selectedTab = Tab.DASHBOARD
                        },
                        onOpenTaskService = {
                            dashboardVm.setServiceMode(ServiceMode.TASK)
                            selectedTab = Tab.DASHBOARD
                        },
                        // v1.2.3: Demo nuôi acc Facebook
                        onOpenFacebookService = {
                            dashboardVm.setServiceMode(ServiceMode.FACEBOOK_NURTURE)
                            selectedTab = Tab.DASHBOARD
                        },
                        // v1.2.4: Demo nuôi acc X, Instagram, Threads, Snapchat
                        onOpenXService = {
                            dashboardVm.setServiceMode(ServiceMode.X_NURTURE)
                            selectedTab = Tab.DASHBOARD
                        },
                        onOpenInstagramService = {
                            dashboardVm.setServiceMode(ServiceMode.INSTAGRAM_NURTURE)
                            selectedTab = Tab.DASHBOARD
                        },
                        onOpenThreadsService = {
                            dashboardVm.setServiceMode(ServiceMode.THREADS_NURTURE)
                            selectedTab = Tab.DASHBOARD
                        },
                        onOpenSnapchatService = {
                            dashboardVm.setServiceMode(ServiceMode.SNAPCHAT_NURTURE)
                            selectedTab = Tab.DASHBOARD
                        },
                        onOpenGolikeLogin  = onOpenGolikeLogin,
                        onGolikeLogout     = golikeVm::logout,
                        isGolikeLoggedIn   = golikeState.isLoggedIn,
                        golikeDisplayName  = golikeState.displayName,
                    )

                    Tab.STATS     -> StatsScreen(
                        vm           = statsVm,
                        golikeVm     = golikeVm,
                        onNavigateUp = { selectedTab = Tab.DASHBOARD },
                    )
                    Tab.ACCOUNTS  -> AccountsScreen(
                        vm           = accountsVm,
                        onNavigateUp = { selectedTab = Tab.DASHBOARD },
                    )
                    Tab.LOGS      -> LogsScreen(
                        vm           = logsVm,
                        onNavigateUp = { selectedTab = Tab.DASHBOARD },
                    )
                }
            }
        }
    }
}
