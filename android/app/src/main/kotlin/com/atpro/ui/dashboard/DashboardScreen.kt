package com.atpro.ui.dashboard

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atpro.automation.FarmMode
import com.atpro.automation.LiveFarmStats
import com.atpro.network.UpdateInfo
import com.atpro.ui.accounts.AccountsActivity
import com.atpro.ui.config.ConfigActivity
import com.atpro.ui.logs.LogsActivity
import com.atpro.ui.stats.StatsActivity

// ── Design tokens ─────────────────────────────────────────────
private val BgDark      = Color(0xFF0D0D14)
private val CardDark    = Color(0xFF1A1A2E)
private val BorderDark  = Color(0xFF374151)
private val Purple      = Color(0xFF6C63FF)
private val Pink        = Color(0xFFEC4899)
private val Green       = Color(0xFF10B981)
private val Amber       = Color(0xFFF59E0B)
private val RedStop     = Color(0xFFEF4444)
private val TextSec     = Color(0xFF9CA3AF)
private val TextMuted   = Color(0xFF6B7280)

// ─────────────────────────────────────────────────────────────
//  Root Screen
// ─────────────────────────────────────────────────────────────

@Composable
fun DashboardScreen(vm: DashboardViewModel) {
    val state   by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // [v1.0.5] Kiểm tra phiên bản mới khi mở Dashboard — chạy 1 lần
    LaunchedEffect(Unit) { vm.checkForUpdate() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // [v1.0.4] Bỏ permission gate — mở thẳng Dashboard không cần cấp quyền trước.
        // Việc cấp quyền được thực hiện qua tab "Quyền" trong Cài đặt.
        AnimatedContent(
            targetState = state.isFarming,
            transitionSpec = {
                if (targetState) {
                    (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 8 }) togetherWith
                        (fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 8 })
                } else {
                    (fadeIn(tween(350)) + slideInVertically(tween(350)) { -it / 8 }) togetherWith
                        (fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 8 })
                }
            },
            label = "farming_state",
        ) { farming ->
            if (farming) FarmingView(state, vm)
            else         IdleView(state, vm)
        }

        // ── Startup status popup ──────────────────────────────────────────────
        if (state.isStartingUp) {
            StartupStatusDialog(
                status = state.startupStatus,
                onStop = vm::stop,
            )
        }

        // ── [v1.0.5] Update available dialog ─────────────────────────────────
        state.updateInfo?.let { info ->
            UpdateAvailableDialog(
                info      = info,
                onDismiss = vm::dismissUpdate,
                onUpdate  = {
                    vm.dismissUpdate()
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  [THÊM] Startup Status Dialog
//  Hiển thị overlay bán trong suốt với spinner + trạng thái hiện tại
// ─────────────────────────────────────────────────────────────

@Composable
private fun StartupStatusDialog(
    status: String,
    onStop: () -> Unit,
) {
    Dialog(
        onDismissRequest = { /* không dismiss khi tap ngoài */ },
        properties = DialogProperties(
            dismissOnBackPress    = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.82f),
                shape  = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 32.dp),
                ) {
                    // Header logo
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(listOf(Purple, Pink))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "AT",
                            color      = Color.White,
                            fontSize   = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    // Spinner
                    val inf = rememberInfiniteTransition(label = "spinner")
                    val rotation by inf.animateFloat(
                        initialValue  = 0f,
                        targetValue   = 360f,
                        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
                        label         = "rotation",
                    )
                    CircularProgressIndicator(
                        modifier  = Modifier.size(36.dp),
                        color     = Purple,
                        strokeWidth = 3.dp,
                    )

                    Spacer(Modifier.height(20.dp))

                    // Title
                    Text(
                        "Đang khởi động farm",
                        color      = Color.White,
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(Modifier.height(10.dp))

                    // Status message — animated khi text thay đổi
                    AnimatedContent(
                        targetState = status,
                        transitionSpec = {
                            (fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 4 }) togetherWith
                                (fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 4 })
                        },
                        label = "startup_status",
                    ) { msg ->
                        Text(
                            text      = msg,
                            color     = TextSec,
                            fontSize  = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(28.dp))

                    // Divider
                    Divider(
                        color     = BorderDark,
                        thickness = 1.dp,
                        modifier  = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(16.dp))

                    // Hủy button
                    TextButton(
                        onClick = onStop,
                        colors  = ButtonDefaults.textButtonColors(contentColor = RedStop),
                    ) {
                        Icon(
                            Icons.Rounded.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Hủy",
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  [v1.0.5] Update Available Dialog
//  Hiển thị khi phát hiện phiên bản mới trên GitHub Releases.
//  Hai nút: "Để sau" (dismiss) và "Cập nhật" (mở link tải APK).
// ─────────────────────────────────────────────────────────────

@Composable
private fun UpdateAvailableDialog(
    info:      UpdateInfo,
    onDismiss: () -> Unit,
    onUpdate:  () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress    = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.60f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                    onClick           = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication        = null,
                        onClick           = { /* absorb — prevent dismiss */ },
                    ),
                shape     = RoundedCornerShape(20.dp),
                colors    = CardDefaults.cardColors(containerColor = CardDark),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                ) {
                    // ── Header ──
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(listOf(Purple, Pink))
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.SystemUpdate,
                                contentDescription = null,
                                tint     = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Column {
                            Text(
                                "Có phiên bản mới",
                                color      = Color.White,
                                fontSize   = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                info.releaseName,
                                color    = Purple,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // ── Version badge row ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Purple.copy(alpha = 0.07f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Text("Phiên bản mới", color = TextSec, fontSize = 12.sp)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Purple.copy(alpha = 0.18f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                info.tagName,
                                color      = Purple,
                                fontSize   = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    // ── Changelog (truncated) ──
                    if (info.body.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Thay đổi",
                            color      = TextSec,
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier   = Modifier.padding(bottom = 6.dp),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF0D0D14))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                // Giới hạn 300 ký tự để tránh dialog quá dài
                                text = if (info.body.length > 300)
                                    info.body.take(300).trimEnd() + "…"
                                else
                                    info.body,
                                color      = Color(0xFF9CA3AF),
                                fontSize   = 11.sp,
                                lineHeight = 17.sp,
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // ── Buttons ──
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // "Để sau" — outline, neutral
                        OutlinedButton(
                            onClick  = onDismiss,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape    = RoundedCornerShape(12.dp),
                            colors   = ButtonDefaults.outlinedButtonColors(
                                contentColor = TextSec,
                            ),
                            border   = androidx.compose.foundation.BorderStroke(
                                1.dp, Color(0xFF374151),
                            ),
                        ) {
                            Text("Để sau", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }

                        // "Cập nhật" — filled, accent
                        Button(
                            onClick  = onUpdate,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape    = RoundedCornerShape(12.dp),
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = Purple,
                            ),
                        ) {
                            Icon(
                                Icons.Rounded.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Cập nhật", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Idle view
// ─────────────────────────────────────────────────────────────

@Composable
private fun IdleView(state: DashboardUiState, vm: DashboardViewModel) {
    val context = LocalContext.current

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter   = fadeIn(tween(400)) + slideInVertically(tween(400, easing = EaseOut)) { it / 10 },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .statusBarsPadding()
        ) {
            Spacer(Modifier.height(32.dp))

            // ── Header ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(online = state.serviceConnected)
                Spacer(Modifier.width(8.dp))
                Text(
                    text  = if (state.serviceConnected) "Sẵn sàng" else "Chưa bật dịch vụ",
                    color = if (state.serviceConnected) Green else TextMuted,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick  = { context.startActivity(Intent(context, ConfigActivity::class.java)) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = "Cài đặt",
                        tint     = TextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                AppLogo()
            }

            Spacer(Modifier.weight(1f))

            // ── Account / list count ──
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                AnimatedContent(
                    targetState = state.displayCount,
                    transitionSpec = {
                        (fadeIn(tween(300)) + slideInVertically(tween(300)) { -it / 2 }) togetherWith
                            (fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 2 })
                    },
                    label = "count_anim",
                ) { count ->
                    Text(
                        text  = "$count",
                        color = Color.White,
                        fontSize   = 72.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 72.sp,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text  = when {
                        state.farmMode == FarmMode.SELECTED_LIST && state.displayCount == 0 ->
                            "tài khoản trong danh sách"
                        state.displayCount == 0 -> "tài khoản"
                        state.farmMode == FarmMode.SELECTED_LIST -> "tài khoản trong danh sách"
                        else -> "tài khoản hoạt động"
                    },
                    color    = TextMuted,
                    fontSize = 15.sp,
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── Farm mode toggle ──
            FarmModeToggle(
                selected  = state.farmMode,
                onSelect  = vm::setFarmMode,
            )

            // ── Account list input — SELECTED_LIST mode only ──
            AnimatedVisibility(
                visible = state.farmMode == FarmMode.SELECTED_LIST,
                enter   = fadeIn(tween(250)) + expandVertically(tween(300, easing = EaseOut)),
                exit    = fadeOut(tween(200)) + shrinkVertically(tween(250, easing = EaseIn)),
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    AccountListInput(
                        value     = state.customAccounts,
                        onChanged = vm::setCustomAccounts,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Start button ──
            StartButton(
                enabled = state.canStart,
                hint    = state.startHint,
                onClick = vm::startFarm,
            )

            Spacer(Modifier.weight(1f))

            // ── Shortcuts ──
            ShortcutRow()

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Farming view
// ─────────────────────────────────────────────────────────────

@Composable
private fun FarmingView(state: DashboardUiState, vm: DashboardViewModel) {
    val stats = state.liveStats

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .statusBarsPadding()
    ) {
        Spacer(Modifier.height(32.dp))

        // ── Header ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot()
            Spacer(Modifier.width(8.dp))
            AnimatedContent(
                targetState = state.isPaused,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                label = "pause_label",
            ) { paused ->
                Text(
                    text  = if (paused) "Đang tạm dừng" else "Đang farm",
                    color = if (paused) Amber else Green,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = vm::stop,
                colors  = ButtonDefaults.textButtonColors(contentColor = RedStop),
            ) {
                Icon(Icons.Rounded.Stop, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Dừng", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.weight(1f))

        // ── Current account ──
        CurrentAccountCard(stats)

        Spacer(Modifier.height(32.dp))

        // ── Stats grid ──
        AnimatedVisibility(
            visible = stats.account.isNotEmpty(),
            enter   = fadeIn(tween(400)) + expandVertically(tween(400)),
        ) {
            StatsGrid(stats)
        }

        Spacer(Modifier.weight(1f))

        // ── Pause / Resume ──
        PauseResumeButton(
            isPaused = state.isPaused,
            onPause  = vm::pause,
            onResume = vm::resume,
        )

        Spacer(Modifier.height(32.dp))
    }
}

// ─────────────────────────────────────────────────────────────
//  Farm mode toggle
// ─────────────────────────────────────────────────────────────

@Composable
private fun FarmModeToggle(selected: FarmMode, onSelect: (FarmMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardDark),
    ) {
        ModeTab(
            label    = "Toàn bộ máy",
            icon     = Icons.Rounded.PhoneAndroid,
            active   = selected == FarmMode.ALL_LOCAL,
            modifier = Modifier.weight(1f),
            onClick  = { onSelect(FarmMode.ALL_LOCAL) },
        )
        ModeTab(
            label    = "Danh sách chọn",
            icon     = Icons.Rounded.FormatListBulleted,
            active   = selected == FarmMode.SELECTED_LIST,
            modifier = Modifier.weight(1f),
            onClick  = { onSelect(FarmMode.SELECTED_LIST) },
        )
    }
}

@Composable
private fun ModeTab(
    label: String,
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val bgColor by animateColorAsState(
        targetValue  = if (active) Purple else Color.Transparent,
        animationSpec = tween(200),
        label        = "tab_bg",
    )
    val contentColor by animateColorAsState(
        targetValue  = if (active) Color.White else TextMuted,
        animationSpec = tween(200),
        label        = "tab_fg",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(14.dp))
            Text(
                label,
                color      = contentColor,
                fontSize   = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Account list input (SELECTED_LIST mode)
// ─────────────────────────────────────────────────────────────

@Composable
private fun AccountListInput(value: String, onChanged: (String) -> Unit) {
    Column {
        Text(
            "Danh sách tài khoản cần nuôi",
            color    = TextSec,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        OutlinedTextField(
            value         = value,
            onValueChange = onChanged,
            modifier      = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp, max = 200.dp),
            placeholder = {
                Text(
                    "username1\nusername2\n@username3",
                    color = Color(0xFF4B5563),
                    fontSize = 13.sp,
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            shape  = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor   = CardDark,
                unfocusedContainerColor = CardDark,
                focusedBorderColor      = Purple,
                unfocusedBorderColor    = Color.Transparent,
                focusedTextColor        = Color.White,
                unfocusedTextColor      = Color.White,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Mỗi dòng một tài khoản. @ tự động bỏ qua.",
            color    = TextMuted,
            fontSize = 11.sp,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  Components
// ─────────────────────────────────────────────────────────────

@Composable
private fun AppLogo() {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(Purple, Pink))),
        contentAlignment = Alignment.Center,
    ) {
        Text("AT", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun StatusDot(online: Boolean) {
    val color by animateColorAsState(
        targetValue  = if (online) Green else TextMuted,
        animationSpec = tween(300),
        label        = "status_dot",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun PulseDot() {
    val inf = rememberInfiniteTransition(label = "pulse")
    val alpha by inf.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1000, easing = EaseInOut), RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(Green.copy(alpha = alpha))
    )
}

@Composable
private fun StartButton(enabled: Boolean, hint: String?, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue   = if (isPressed) 0.97f else 1f,
        animationSpec = tween(100),
        label         = "btn_scale",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick           = onClick,
            enabled           = enabled,
            interactionSource = interactionSource,
            modifier          = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .scale(scale),
            shape  = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor         = Purple,
                disabledContainerColor = BorderDark,
            ),
        ) {
            Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Bắt đầu farm",
                fontSize   = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        AnimatedVisibility(
            visible = hint != null,
            enter   = fadeIn(tween(200)) + expandVertically(tween(200)),
            exit    = fadeOut(tween(150)) + shrinkVertically(tween(150)),
        ) {
            Column {
                Spacer(Modifier.height(10.dp))
                Text(hint ?: "", color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CurrentAccountCard(stats: LiveFarmStats) {
    val progress = if (stats.total > 0) stats.index.toFloat() / stats.total else 0f
    val animProgress by animateFloatAsState(
        targetValue   = progress,
        animationSpec = tween(500, easing = EaseOut),
        label         = "progress",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        AnimatedContent(
            targetState = stats.account,
            transitionSpec = {
                (fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 }) togetherWith
                    (fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 4 })
            },
            label = "account_name",
        ) { acc ->
            Text(
                "@${acc.ifEmpty { "..." }}",
                color      = Color.White,
                fontSize   = 28.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            Text("${stats.index}", color = Purple, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(" / ", color = TextMuted, fontSize = 15.sp)
            Text("${stats.total}", color = TextMuted, fontSize = 15.sp)
        }
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { animProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color      = Purple,
            trackColor = BorderDark,
        )
    }
}

@Composable
private fun StatsGrid(stats: LiveFarmStats) {
    val mins = stats.remainingSecs / 60
    val secs = stats.remainingSecs % 60
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatBox("${stats.videos}",  "Video",  Icons.Rounded.PlayCircle,  Purple, Modifier.weight(1f))
        StatBox("${stats.likes}",   "Thích",  Icons.Rounded.Favorite,    Pink,   Modifier.weight(1f))
        StatBox("${stats.follows}", "Theo",   Icons.Rounded.PersonAdd,   Green,  Modifier.weight(1f))
        StatBox(
            value    = "$mins:${secs.toString().padStart(2, '0')}",
            label    = "Còn lại",
            icon     = Icons.Rounded.Timer,
            color    = Amber,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatBox(
    value: String,
    label: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(vertical = 16.dp),
    ) {
        Icon(icon, null, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(6.dp))
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(4.dp))
        Text(label, color = TextSec, fontSize = 11.sp)
    }
}

@Composable
private fun PauseResumeButton(isPaused: Boolean, onPause: () -> Unit, onResume: () -> Unit) {
    val contentColor by animateColorAsState(
        targetValue  = if (isPaused) Green else TextSec,
        animationSpec = tween(200),
        label        = "pause_btn_color",
    )
    OutlinedButton(
        onClick  = if (isPaused) onResume else onPause,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape    = RoundedCornerShape(14.dp),
        border   = ButtonDefaults.outlinedButtonBorder.copy(
            brush = SolidColor(BorderDark),
        ),
        colors   = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
    ) {
        Icon(
            if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
            null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text       = if (isPaused) "Tiếp tục" else "Tạm dừng",
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ShortcutRow() {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ShortcutTile(
            icon     = Icons.Rounded.ManageAccounts,
            label    = "Tài khoản",
            modifier = Modifier.weight(1f),
        ) { context.startActivity(Intent(context, AccountsActivity::class.java)) }
        ShortcutTile(
            icon     = Icons.Rounded.BarChart,
            label    = "Thống kê",
            modifier = Modifier.weight(1f),
        ) { context.startActivity(Intent(context, StatsActivity::class.java)) }
        ShortcutTile(
            icon     = Icons.Rounded.Assignment,
            label    = "Nhật ký",
            modifier = Modifier.weight(1f),
        ) { context.startActivity(Intent(context, LogsActivity::class.java)) }
    }
}

@Composable
private fun ShortcutTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue   = if (isPressed) 0.94f else 1f,
        animationSpec = tween(100),
        label         = "tile_scale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(CardDark)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Icon(icon, null, tint = Purple.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = TextSec, fontSize = 11.sp)
    }
}

// Helper
private fun SolidColor(color: Color) = Brush.linearGradient(listOf(color, color))
