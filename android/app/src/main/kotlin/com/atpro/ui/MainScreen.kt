package com.atpro.ui

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atpro.ui.accounts.AccountsScreen
import com.atpro.ui.accounts.AccountsViewModel
import com.atpro.ui.dashboard.DashboardScreen
import com.atpro.ui.dashboard.DashboardViewModel
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
//  v1.2.7: Tab "Dịch vụ" dùng icon Settings để đồng bộ với ConfigScreen (Cài đặt).
//          Tất cả label dùng cùng fontSize = 10.sp.
// ─────────────────────────────────────────────────────────────

private enum class Tab(
    val label: String,
    val icon:  ImageVector,
) {
    DASHBOARD("Dashboard",  Icons.Rounded.Home),
    SERVICES ("Dịch vụ",   Icons.Rounded.Settings),   // v1.2.7: Layers → Settings (đồng bộ ConfigScreen)
    STATS    ("Thống kê",  Icons.Rounded.BarChart),
    ACCOUNTS ("Tài khoản", Icons.Rounded.ManageAccounts),
    LOGS     ("Nhật ký",   Icons.Rounded.EventNote),
}

// ─────────────────────────────────────────────────────────────
//  MainScreen — shell duy nhất chứa tất cả tab
// ─────────────────────────────────────────────────────────────

@Composable
fun MainScreen(
    dashboardVm: DashboardViewModel,
    statsVm:     StatsViewModel,
    accountsVm:  AccountsViewModel,
    logsVm:      LogsViewModel,
) {
    var selectedTab by remember { mutableStateOf(Tab.DASHBOARD) }

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
                                fontSize = 10.sp,   // v1.2.7: tất cả tab label cùng cỡ
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
                    Tab.DASHBOARD -> DashboardScreen(vm = dashboardVm)

                    Tab.SERVICES  -> ServicesScreen(
                        onOpenFarmService = {
                            dashboardVm.setServiceMode(com.atpro.automation.ServiceMode.FARM)
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

                    Tab.STATS     -> StatsScreen(
                        vm           = statsVm,
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
