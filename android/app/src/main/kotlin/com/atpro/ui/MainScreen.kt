package com.atpro.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atpro.auth.domain.AuthRepository
import com.atpro.sync.BackupOwnershipManager
import com.atpro.sync.SyncWorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import android.widget.Toast
import com.atpro.ui.accounts.AccountsScreen
import com.atpro.ui.accounts.AccountsViewModel
import com.atpro.ui.dashboard.DashboardScreen
import com.atpro.ui.dashboard.DashboardViewModel
import com.atpro.ui.dashboard.UpdateAvailableDialog
import com.atpro.ui.f4f.F4fHubScreen
import com.atpro.ui.f4f.F4fHubTab
import com.atpro.ui.config.ConfigActivity
import com.atpro.ui.logs.LogsScreen
import com.atpro.ui.logs.LogsViewModel
import com.atpro.ui.settings.SettingsHubScreen
import com.atpro.ui.schedule.ScheduleScreen
import com.atpro.ui.schedule.ScheduleViewModel
import com.atpro.ui.services.ServicesScreen
import com.atpro.ui.stats.StatsScreen
import com.atpro.ui.stats.StatsViewModel

// ── Design tokens ────────────────────────────────────────────
private val BgDark get() = AtColors.Bg
private val NavBg get() = AtColors.Surface
private val Purple get() = AtColors.Accent
private val TextMuted get() = AtColors.TextMuted // ─────────────────────────────────────────────────────────────
//  Tab definition
//  v1.8.0: bố cục lại — 5 tab theo mức độ dùng hằng ngày:
//    Tổng quan (bắt đầu/dừng) · Dịch vụ (chọn việc + ví) · Tài khoản · Nhật ký (theo dõi) · Cài đặt (mọi cấu hình).
//  Thống kê và Lịch tự động là công cụ phụ → màn hình phủ mở từ Tổng quan / Cài đặt (xem [Overlay]).
// ─────────────────────────────────────────────────────────────

private enum class Tab(
    val label: String,
    val icon:  ImageVector,
) {
    DASHBOARD("Tổng quan", Icons.Rounded.Home),
    SERVICES ("Dịch vụ",   Icons.Rounded.Layers),
    ACCOUNTS ("Tài khoản", Icons.Rounded.ManageAccounts),
    LOGS     ("Nhật ký",   Icons.Rounded.Terminal),
    SETTINGS ("Cài đặt",   Icons.Rounded.Settings),
}

/** Màn hình phủ toàn màn hình (trượt từ dưới lên, nút Back hệ thống đóng lại). */
private enum class Overlay { STATS, SCHEDULE }

// ─────────────────────────────────────────────────────────────
//  MainScreen — shell duy nhất chứa tất cả tab
// ─────────────────────────────────────────────────────────────

@Composable
fun MainScreen(
    dashboardVm: DashboardViewModel,
    statsVm:     StatsViewModel,
    accountsVm:  AccountsViewModel,
    scheduleVm:  ScheduleViewModel,
    logsVm:      LogsViewModel,
    authRepository: AuthRepository,
    backupOwnershipManager: BackupOwnershipManager,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    val authSession by authRepository.session.collectAsStateWithLifecycle()
    val backupOwnershipState by backupOwnershipManager.state.collectAsStateWithLifecycle()
    val ownershipScope = rememberCoroutineScope()

    LaunchedEffect(authSession?.user?.id) {
        backupOwnershipManager.refresh(authSession?.user?.id)
        try {
            SyncWorkScheduler.scheduleIfPending(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Pending local changes remain durable; next launch or backed write retries scheduling.
        }
    }
    var selectedTab by remember { mutableStateOf(Tab.DASHBOARD) }
    // v1.8.0: Thống kê / Lịch tự động mở dạng màn hình phủ (null = đóng); giữ overlay cuối để hiệu ứng đóng không bị trống.
    var overlay     by remember { mutableStateOf<Overlay?>(null) }
    var lastOverlay by remember { mutableStateOf(Overlay.STATS) }
    fun openOverlay(o: Overlay) { lastOverlay = o; overlay = o }
    BackHandler(enabled = overlay != null) { overlay = null }
    // v1.7.0: Ví Follow4Follow mở toàn màn hình đè lên các tab (null = đóng).
    var f4fHubOpen by remember { mutableStateOf(false) }
    var f4fHubTab  by remember { mutableStateOf(F4fHubTab.OVERVIEW) }
    val dashboardState by dashboardVm.uiState.collectAsStateWithLifecycle()

    // v1.2.9: Kiểm tra phiên bản mới ở cấp MainScreen (không phải DashboardScreen) —
    // chạy đúng 1 lần cho cả phiên app, không bị huỷ/refire khi chuyển tab qua lại.
    // Dialog cũng được render ở dưới (ngoài Scaffold) nên luôn hiện đè lên mọi tab,
    // không chỉ riêng tab Dashboard.
    LaunchedEffect(Unit) { dashboardVm.checkForUpdate() }

    // v1.12.0: chạm thông báo → mở đúng tab (Nhật ký / Dịch vụ / Tài khoản).
    val requestedTab by com.atpro.notification.NotificationNavigator.requested.collectAsStateWithLifecycle()
    LaunchedEffect(requestedTab) {
        val target = when (requestedTab) {
            "logs"     -> Tab.LOGS
            "services" -> Tab.SERVICES
            "accounts" -> Tab.ACCOUNTS
            "settings" -> Tab.SETTINGS
            else       -> null
        }
        if (target != null) { selectedTab = target; overlay = null; f4fHubOpen = false }
        if (requestedTab != null) com.atpro.notification.NotificationNavigator.consume()
    }

    Box(Modifier.fillMaxSize()) {
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
                        onOpenF4fHub = { f4fHubTab = it; f4fHubOpen = true },
                    )

                    Tab.ACCOUNTS -> AccountsScreen(vm = accountsVm)
                    Tab.LOGS     -> LogsScreen(vm = logsVm)
                    Tab.SETTINGS -> SettingsHubScreen(
                        onOpenConfig   = { section -> context.startActivity(ConfigActivity.intent(context, section)) },
                        onOpenStats    = { openOverlay(Overlay.STATS) },
                        onOpenSchedule = { openOverlay(Overlay.SCHEDULE) },
                        authEmail = authSession?.user?.email,
                        backupOwnershipState = backupOwnershipState,
                        onBindBackupOwner = {
                            ownershipScope.launch {
                                val result = backupOwnershipManager.bindToCurrentUser(
                                    authUserId = authSession?.user?.id,
                                    confirmedByUser = true,
                                )
                                if (result == com.atpro.sync.BackupOwnerBindingResult.Bound ||
                                    result == com.atpro.sync.BackupOwnerBindingResult.AlreadyBound
                                ) {
                                    try {
                                        SyncWorkScheduler.scheduleIfPending(context)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        // Ownership is already stored locally; pending data is not discarded.
                                    }
                                } else {
                                    val message = when (result) {
                                        com.atpro.sync.BackupOwnerBindingResult.DifferentAccount ->
                                            "Dữ liệu cục bộ đang thuộc một tài khoản AT PRO khác."
                                        com.atpro.sync.BackupOwnerBindingResult.ConfirmationRequired ->
                                            "Cần xác nhận liên kết trước khi tiếp tục."
                                        com.atpro.sync.BackupOwnerBindingResult.SignedOut ->
                                            "Vui lòng đăng nhập lại."
                                        else -> "Không thể liên kết dữ liệu sao lưu trên thiết bị này."
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        onSignOut = onSignOut,
                    )
                }
            }
        }
    }

    // v1.7.0: Ví Follow4Follow — toàn màn hình, trượt từ dưới lên.
    AnimatedVisibility(
        visible = f4fHubOpen,
        enter   = fadeIn(tween(220)) + slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it / 10 },
        exit    = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 10 },
    ) {
        F4fHubScreen(initialTab = f4fHubTab, onBack = { f4fHubOpen = false })
    }
    // v1.8.0: Thống kê / Lịch tự động — màn hình phủ có nút quay lại (dùng đúng tham số onNavigateUp của từng màn hình).
    AnimatedVisibility(
        visible = overlay != null,
        enter   = fadeIn(tween(220)) + slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it / 10 },
        exit    = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 10 },
    ) {
        Box(Modifier.fillMaxSize().background(BgDark)) {
            when (lastOverlay) {
                Overlay.STATS    -> StatsScreen(vm = statsVm, onNavigateUp = { overlay = null })
                Overlay.SCHEDULE -> ScheduleScreen(vm = scheduleVm, onNavigateUp = { overlay = null })
            }
        }
    }
    }   // Box

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
    // v1.7.0: nền trắng/xanh đen của bề mặt, viền trên mảnh; tab đang chọn có viên thuốc xanh nhạt trượt
    // mượt phía sau biểu tượng, nhãn đậm + màu xanh dương.
    Column(
        Modifier
            .background(AtColors.Surface)
            .navigationBarsPadding()
    ) {
        AtDivider()
        BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp)) {
            val itemW = maxWidth / Tab.entries.size
            val pillW = 56.dp
            val pillX by animateDpAsState(
                targetValue   = itemW * selected.ordinal + (itemW - pillW) / 2,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 450f),
                label         = "nav_pill",
            )
            Box(
                Modifier
                    .offset(x = pillX, y = 8.dp)
                    .width(pillW)
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(AtColors.AccentSoft)
            )
            Row(Modifier.fillMaxSize()) {
                Tab.entries.forEach { tab ->
                    val active = tab == selected
                    val tint by animateColorAsState(
                        targetValue   = if (active) AtColors.Accent else TextMuted,
                        animationSpec = tween(180),
                        label         = "nav_tint",
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pressClickable(pressedScale = 0.92f) { onSelect(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.padding(top = 8.dp).height(30.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label,
                                tint     = tint,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            tab.label,
                            color      = tint,
                            fontSize   = 10.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
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
