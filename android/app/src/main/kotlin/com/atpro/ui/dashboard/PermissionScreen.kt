package com.atpro.ui.dashboard

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Design tokens (shared palette) ───────────────────────────
private val BgDark     = Color(0xFF0D0D14)
private val CardDark   = Color(0xFF1A1A2E)
private val BorderDark = Color(0xFF374151)
private val Purple     = Color(0xFF6C63FF)
private val Pink       = Color(0xFFEC4899)
private val Green      = Color(0xFF10B981)
private val Amber      = Color(0xFFF59E0B)
private val TextSec    = Color(0xFF9CA3AF)
private val TextMuted  = Color(0xFF6B7280)

// ─────────────────────────────────────────────────────────────
//  PermissionScreen
//
//  Hiển thị khi app chưa đủ quyền:
//    • Accessibility Service (bắt buộc — không có không farm được)
//    • Overlay / Display over apps (cần cho farm monitor popup)
//    • POST_NOTIFICATIONS (Android 13+, cần cho notification)
//
//  Gọi từ DashboardScreen khi permissionsReady == false.
// ─────────────────────────────────────────────────────────────

@Composable
fun PermissionScreen(
    state:              DashboardUiState,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay:       () -> Unit,
    onOpenNotification:  () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter   = fadeIn(tween(400)) + slideInVertically(tween(400, easing = EaseOut)) { it / 10 },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark)
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            // ── Logo ──
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(Purple, Pink))),
                contentAlignment = Alignment.Center,
            ) {
                Text("AT", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "Cấp quyền cần thiết",
                color      = Color.White,
                fontSize   = 22.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "AT PRO cần các quyền bên dưới để chạy tự động hóa TikTok.",
                color     = TextMuted,
                fontSize  = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(36.dp))

            // ── Permission items ──
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                PermissionItem(
                    icon        = Icons.Rounded.Accessibility,
                    iconColor   = Purple,
                    title       = "Accessibility Service",
                    description = "Bắt buộc. Dùng để điều khiển TikTok tự động.",
                    granted     = state.accessibilityGranted,
                    required    = true,
                    onAction    = onOpenAccessibility,
                )

                PermissionItem(
                    icon        = Icons.Rounded.Layers,
                    iconColor   = Amber,
                    title       = "Hiển thị trên ứng dụng khác",
                    description = "Dùng để hiện popup theo dõi farm trong khi TikTok đang chạy.",
                    granted     = state.overlayGranted,
                    required    = false,
                    onAction    = onOpenOverlay,
                )

                PermissionItem(
                    icon        = Icons.Rounded.NotificationsActive,
                    iconColor   = Green,
                    title       = "Thông báo",
                    description = "Nhận thông báo khi farm xong hoặc có lỗi.",
                    granted     = state.notificationGranted,
                    required    = false,
                    onAction    = onOpenNotification,
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Footer hint ──
            AnimatedVisibility(
                visible = state.accessibilityGranted,
                enter   = fadeIn(tween(300)) + expandVertically(tween(350)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HorizontalDivider(color = BorderDark, thickness = 0.5.dp)
                    Spacer(Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            null,
                            tint     = Green,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            "Quyền bắt buộc đã cấp — tiếp tục để bắt đầu farm.",
                            color    = TextSec,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  PermissionItem
// ─────────────────────────────────────────────────────────────

@Composable
private fun PermissionItem(
    icon:        ImageVector,
    iconColor:   Color,
    title:       String,
    description: String,
    granted:     Boolean,
    required:    Boolean,
    onAction:    () -> Unit,
) {
    val borderColor by animateColorAsState(
        targetValue  = if (granted) Green.copy(alpha = 0.4f) else BorderDark,
        animationSpec = tween(300),
        label        = "perm_border",
    )
    val bgColor by animateColorAsState(
        targetValue  = if (granted) Green.copy(alpha = 0.04f) else CardDark,
        animationSpec = tween(300),
        label        = "perm_bg",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon badge
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(iconColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, null,
                tint     = iconColor,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.width(14.dp))

        // Text
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment      = Alignment.CenterVertically,
                horizontalArrangement  = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    title,
                    color      = Color.White,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (required) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Purple.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            "Bắt buộc",
                            color    = Purple,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(description, color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp)
        }

        Spacer(Modifier.width(12.dp))

        // Status / action
        AnimatedContent(
            targetState = granted,
            transitionSpec = {
                (fadeIn(tween(200)) + scaleIn(tween(200))) togetherWith
                    (fadeOut(tween(150)) + scaleOut(tween(150)))
            },
            label = "perm_status_$title",
        ) { isGranted ->
            if (isGranted) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    null,
                    tint     = Green,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                OutlinedButton(
                    onClick   = onAction,
                    shape     = RoundedCornerShape(10.dp),
                    modifier  = Modifier.height(34.dp),
                    colors    = ButtonDefaults.outlinedButtonColors(contentColor = iconColor),
                    border    = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(iconColor.copy(alpha = 0.4f)),
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text("Cấp quyền", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
