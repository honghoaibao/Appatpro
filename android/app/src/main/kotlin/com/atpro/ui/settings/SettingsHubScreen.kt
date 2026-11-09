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
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MonetizationOn
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    var showSignOutConfirmation by remember { mutableStateOf(false) }
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

    if (showSignOutConfirmation) {
        AtDialog(
            onDismiss = { showSignOutConfirmation = false },
            title = "Đăng xuất khỏi AT PRO?",
            message = "Phiên đăng nhập được lưu trên thiết bị này sẽ bị xóa. Dữ liệu cục bộ của app không bị xóa.",
            confirmText = "Đăng xuất",
            onConfirm = {
                showSignOutConfirmation = false
                onSignOut()
            },
            danger = true,
        )
    }
}
