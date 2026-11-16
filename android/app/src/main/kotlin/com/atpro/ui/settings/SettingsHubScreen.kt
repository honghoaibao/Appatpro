package com.atpro.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MonetizationOn
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atpro.ui.theme.AtCard
import com.atpro.ui.theme.AtDialog
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AtDivider
import com.atpro.ui.theme.AtListRow
import com.atpro.ui.theme.AtSectionLabel
import com.atpro.ui.theme.AtSegmented
import com.atpro.ui.theme.AtThemeMode
import com.atpro.ui.theme.AtThemeState
import com.atpro.ui.theme.AtTopBar
import com.atpro.ui.theme.staggerIn
import com.atpro.sync.BackupOwnershipState
import com.atpro.sync.SyncStatusSnapshot
import com.atpro.sync.SyncStatusText
import kotlinx.coroutines.delay

/**
 * v1.8.0 — Tab "Cài đặt": một danh mục phẳng, MỘT cấp, thay cho việc chui vào Cài đặt → tab nhóm → chip mục.
 *
 *   • Giao diện Sáng/Tối chỉnh ngay tại chỗ (thao tác hay dùng nhất).
 *   • Công cụ: Thống kê · Lịch tự động (mở màn hình phủ). v1.9.0: Ví Follow4Follow chỉ còn ở tab Dịch vụ (bỏ hàng trùng).
 *   • Cấu hình: mỗi hàng mở thẳng mục tương ứng trong `ConfigActivity` (tên [section] = tên enum Section).
 *
 * @param onOpenConfig mở cài đặt chi tiết tại mục có tên enum `section` (vd "PERMISSIONS").
 */
@Composable
fun SettingsHubScreen(
    onOpenConfig:   (section: String) -> Unit,
    onOpenStats:    () -> Unit,
    onOpenSchedule: () -> Unit,
    authEmail: String?,
    backupOwnershipState: BackupOwnershipState,
    onBindBackupOwner: () -> Unit,
    syncStatus: SyncStatusSnapshot,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    // Đồng hồ cho dòng "Lần cuối … trước": cập nhật mỗi 20 giây, và ngay khi trạng thái đổi.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(syncStatus) { now = System.currentTimeMillis() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000L)
            now = System.currentTimeMillis()
        }
    }
    var showSignOutConfirmation by remember { mutableStateOf(false) }
    var showBackupOwnerConfirmation by remember { mutableStateOf(false) }
    val mode = AtThemeState.mode
    val version = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() } catch (e: Exception) { "" }
    }
    val tasksEnabled = com.atpro.security.AppConstants.TASKS_ENABLED

    Column(
        Modifier
            .fillMaxSize()
            .background(AtColors.Bg)
            .statusBarsPadding(),
    ) {
        AtTopBar("Cài đặt")

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            // ── Tài khoản AT PRO ──
            AtSectionLabel("Tài khoản", Modifier.padding(start = 4.dp, bottom = 8.dp))
            AtCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                AtListRow(
                    "Đang đăng nhập",
                    subtitle = authEmail ?: "Tài khoản AT PRO",
                    icon = Icons.Rounded.AccountCircle,
                    chevron = false,
                )
                AtDivider()
                AtListRow(
                    "Đăng xuất",
                    subtitle = "Đăng xuất khỏi thiết bị này",
                    icon = Icons.Rounded.Logout,
                    tint = AtColors.Danger,
                    onClick = { showSignOutConfirmation = true },
                )
            }
            Spacer(Modifier.height(20.dp))

            // ── Sao lưu dữ liệu (Supabase) ──
            AtSectionLabel("Sao lưu dữ liệu", Modifier.padding(start = 4.dp, bottom = 8.dp))
            AtCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                when (val ownership = backupOwnershipState) {
                    BackupOwnershipState.Checking -> AtListRow(
                        "Đang kiểm tra",
                        subtitle = "Đọc dữ liệu trên máy",
                        icon = Icons.Rounded.Sync,
                        chevron = false,
                    )
                    BackupOwnershipState.SignedOut -> AtListRow(
                        "Chưa đăng nhập",
                        subtitle = "Đăng nhập để sao lưu dữ liệu",
                        icon = Icons.Rounded.CloudOff,
                        chevron = false,
                    )
                    is BackupOwnershipState.NeedsConfirmation -> AtListRow(
                        "Liên kết dữ liệu với tài khoản này",
                        subtitle = "Làm một lần · bản ${ownership.lastRemoteRevision}",
                        icon = Icons.Rounded.CloudQueue,
                        tint = AtColors.Sky,
                        onClick = { showBackupOwnerConfirmation = true },
                    )
                    is BackupOwnershipState.Linked -> {
                        val running = syncStatus.isRunning(now)
                        val attention = !running && !syncStatus.retrying && syncStatus.outcome.needsAttention
                        AtListRow(
                            SyncStatusText.headline(syncStatus, now),
                            subtitle = SyncStatusText.detail(syncStatus, now),
                            icon = when {
                                attention -> Icons.Rounded.ErrorOutline
                                running -> Icons.Rounded.Sync
                                syncStatus.outcome.isSuccess -> Icons.Rounded.CloudDone
                                else -> Icons.Rounded.CloudQueue
                            },
                            tint = if (attention) AtColors.Danger else AtColors.Sky,
                            chevron = false,
                        )
                        AtDivider()
                        AtListRow(
                            if (running) "Đang đồng bộ" else "Đồng bộ ngay",
                            subtitle = "Gửi thay đổi và nhận dữ liệu từ thiết bị khác · bản ${ownership.lastRemoteRevision}",
                            icon = Icons.Rounded.Sync,
                            tint = AtColors.Sky,
                            onClick = if (running) null else onSyncNow,
                            chevron = false,
                        )
                    }
                    BackupOwnershipState.DifferentAccount -> AtListRow(
                        "Dữ liệu thuộc tài khoản khác",
                        subtitle = "Đồng bộ bị khóa, app không tự chuyển quyền sở hữu",
                        icon = Icons.Rounded.AdminPanelSettings,
                        tint = AtColors.Danger,
                        chevron = false,
                    )
                    BackupOwnershipState.InvalidAuthIdentity,
                    BackupOwnershipState.MetadataUnavailable -> AtListRow(
                        "Không xác minh được chủ dữ liệu",
                        subtitle = "Dữ liệu trên máy giữ nguyên, chưa sao lưu",
                        icon = Icons.Rounded.AdminPanelSettings,
                        tint = AtColors.Danger,
                        chevron = false,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))

            // ── Giao diện (chỉnh ngay) ──
            AtSectionLabel("Giao diện", Modifier.padding(start = 4.dp, bottom = 8.dp))
            AtCard(Modifier.fillMaxWidth().staggerIn(0)) {
                AtSegmented(
                    options       = AtThemeMode.values().map { it.label },
                    selectedIndex = mode.ordinal,
                    onSelect      = { AtThemeState.set(context, AtThemeMode.values()[it]) },
                )
            }

            // ── Công cụ ──
            Spacer(Modifier.height(20.dp))
            AtSectionLabel("Công cụ", Modifier.padding(start = 4.dp, bottom = 8.dp))
            AtCard(Modifier.fillMaxWidth().staggerIn(1), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                AtListRow("Thống kê", subtitle = "Hoạt động và thu nhập", icon = Icons.Rounded.BarChart, onClick = onOpenStats)
                AtDivider()
                AtListRow("Lịch tự động", subtitle = "Hẹn giờ chạy", icon = Icons.Rounded.Schedule, tint = AtColors.Sky, onClick = onOpenSchedule)
            }

            // ── Cấu hình ──
            Spacer(Modifier.height(20.dp))
            AtSectionLabel("Cấu hình", Modifier.padding(start = 4.dp, bottom = 8.dp))
            AtCard(Modifier.fillMaxWidth().staggerIn(2), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                AtListRow("Quyền & trợ năng", subtitle = "Cấp quyền cho app", icon = Icons.Rounded.AdminPanelSettings, onClick = { onOpenConfig("PERMISSIONS") })
                AtDivider()
                AtListRow("Thông báo", subtitle = "Hệ thống và Discord", icon = Icons.Rounded.NotificationsNone, tint = AtColors.Sky, onClick = { onOpenConfig("NOTIFICATIONS") })
                AtDivider()
                AtListRow("Nuôi TikTok", subtitle = "Thời gian, hành động", icon = Icons.Rounded.AccountCircle, onClick = { onOpenConfig("TIMING") })
                AtDivider()
                AtListRow("Nuôi Facebook", subtitle = "Thời gian, hành động", icon = Icons.Rounded.ThumbUp, tint = AtColors.Sky, onClick = { onOpenConfig("FACEBOOK_TIMING") })
                if (tasksEnabled) {
                    AtDivider()
                    AtListRow("Nhiệm vụ kiếm tiền", subtitle = "Loại job, giới hạn, nuôi acc", icon = Icons.Rounded.MonetizationOn, onClick = { onOpenConfig("TASK_EARN") })
                }
                AtDivider()
                AtListRow("Dịch vụ khác", subtitle = "X, Instagram, Threads, Snapchat", icon = Icons.Rounded.Apps, tint = AtColors.Sky, onClick = { onOpenConfig("DEMO_X") })
            }

            // ── Ứng dụng ──
            Spacer(Modifier.height(20.dp))
            AtSectionLabel("Ứng dụng", Modifier.padding(start = 4.dp, bottom = 8.dp))
            AtCard(Modifier.fillMaxWidth().staggerIn(3), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                AtListRow("AT PRO", subtitle = if (version.isNotBlank()) "Phiên bản $version" else "AT PRO", icon = Icons.Rounded.Info, chevron = false)
            }
        }
    }

    if (showBackupOwnerConfirmation) {
        AtDialog(
            onDismiss = { showBackupOwnerConfirmation = false },
            title = "Liên kết dữ liệu?",
            message = "Dữ liệu trên máy sẽ gắn với tài khoản đang đăng nhập. Tài khoản khác không đồng bộ được dữ liệu này.",
            confirmText = "Liên kết",
            onConfirm = {
                showBackupOwnerConfirmation = false
                onBindBackupOwner()
            },
        )
    }

    if (showSignOutConfirmation) {
        AtDialog(
            onDismiss = { showSignOutConfirmation = false },
            title = "Đăng xuất khỏi AT PRO?",
            message = "Phiên đăng nhập trên máy này sẽ bị xóa. Dữ liệu trên máy được giữ nguyên.",
            confirmText = "Đăng xuất",
            onConfirm = {
                showSignOutConfirmation = false
                onSignOut()
            },
            danger = true,
        )
    }
}
