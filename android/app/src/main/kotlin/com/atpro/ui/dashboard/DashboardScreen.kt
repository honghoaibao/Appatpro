package com.atpro.ui.dashboard

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.atpro.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
import com.atpro.ui.golike.GolikeUiState
import com.atpro.ui.golike.GolikeViewModel
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
private val TextPrim    = Color(0xFFEEEEF5)
private val TextSec     = Color(0xFF9CA3AF)
private val TextMuted   = Color(0xFF6B7280)

// ─────────────────────────────────────────────────────────────
//  Root Screen
// ─────────────────────────────────────────────────────────────

@Composable
fun DashboardScreen(vm: DashboardViewModel, golikeVm: GolikeViewModel) {
    val state      by vm.uiState.collectAsStateWithLifecycle()
    val golikeState by golikeVm.state.collectAsStateWithLifecycle()
    val context    = LocalContext.current

    // [v1.1.5] Trạng thái thu nhỏ popup — reset mỗi khi farm kết thúc
    var isMinimized by remember { mutableStateOf(false) }
    LaunchedEffect(state.isStartingUp) {
        if (!state.isStartingUp) isMinimized = false
    }

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
            else         IdleView(state, vm, golikeState)
        }

        // ── [v1.1.5] Startup status popup — có nút thu nhỏ ──────────────────
        if (state.isStartingUp && !isMinimized) {
            StartupStatusDialog(
                status     = state.startupStatus,
                isPaused   = state.isPaused,
                onPause    = vm::pause,
                onResume   = vm::resume,
                onStop     = vm::stop,
                onMinimize = { isMinimized = true },
            )
        }

        // ── [v1.1.5] Bubble thu nhỏ — hiện khi popup bị minimize ────────────
        AnimatedVisibility(
            visible = state.isStartingUp && isMinimized,
            enter   = fadeIn(tween(200)) + scaleIn(tween(220), initialScale = 0.6f),
            exit    = fadeOut(tween(150)) + scaleOut(tween(150)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp),
        ) {
            MinimizedBubble(
                isPaused  = state.isPaused,
                onExpand  = { isMinimized = false },
            )
        }

        // ── [v1.0.5] Update available dialog ─────────────────────────────────
        state.updateInfo?.let { info ->
            UpdateAvailableDialog(
                info             = info,
                downloadProgress = state.downloadProgress,
                onDismiss        = vm::dismissUpdate,
                onUpdate         = { vm.startUpdate() },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  [v1.0.9] Startup Status Dialog — compact, đẹp hơn
//  Hiển thị overlay trong suốt với trạng thái farm + điều khiển
// ─────────────────────────────────────────────────────────────

@Composable
private fun StartupStatusDialog(
    status:    String,
    isPaused:  Boolean,
    onPause:   () -> Unit,
    onResume:  () -> Unit,
    onStop:    () -> Unit,
    onMinimize: () -> Unit,                          // [v1.1.5] thu nhỏ thành bubble
) {
    Dialog(
        onDismissRequest = { /* không dismiss khi tap ngoài */ },
        properties = DialogProperties(
            dismissOnBackPress      = false,
            dismissOnClickOutside   = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center,
        ) {
            // Card gọn hơn v1.0.8 — không có header logo thừa
            Card(
                modifier  = Modifier.fillMaxWidth(0.78f),
                shape     = RoundedCornerShape(18.dp),
                colors    = CardDefaults.cardColors(containerColor = CardDark),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                border    = BorderStroke(1.dp, BorderDark),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                ) {
                    // ── Spinner + tiêu đề + nút thu nhỏ ──
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(18.dp),
                            color       = Purple,
                            strokeWidth = 2.dp,
                        )
                        AnimatedContent(
                            targetState = isPaused,
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                            label = "startup_title",
                        ) { paused ->
                            Text(
                                text       = if (paused) "Đã tạm dừng" else "Đang khởi động farm",
                                color      = if (paused) Amber else Color.White,
                                fontSize   = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // [v1.1.5] Nút thu nhỏ — collapse popup thành bubble tròn
                        IconButton(
                            onClick  = onMinimize,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Thu nhỏ",
                                tint     = TextMuted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ── Status text ──
                    AnimatedContent(
                        targetState = status,
                        transitionSpec = {
                            (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 4 }) togetherWith
                                (fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 4 })
                        },
                        label = "startup_status",
                    ) { msg ->
                        Text(
                            text      = msg,
                            color     = TextSec,
                            fontSize  = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    // ── Nút điều khiển: Tạm dừng/Tiếp tục + Dừng ──
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Tạm dừng / Tiếp tục
                        OutlinedButton(
                            onClick  = if (isPaused) onResume else onPause,
                            modifier = Modifier.weight(1f).height(38.dp),
                            shape    = RoundedCornerShape(10.dp),
                            border   = BorderStroke(
                                1.dp,
                                if (isPaused) Green.copy(alpha = 0.6f) else BorderDark,
                            ),
                            colors   = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (isPaused) Green else TextSec,
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(
                                if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (isPaused) "Tiếp tục" else "Tạm dừng",
                                fontSize   = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        // Dừng
                        OutlinedButton(
                            onClick  = onStop,
                            modifier = Modifier.weight(1f).height(38.dp),
                            shape    = RoundedCornerShape(10.dp),
                            border   = BorderStroke(1.dp, RedStop.copy(alpha = 0.5f)),
                            colors   = ButtonDefaults.outlinedButtonColors(contentColor = RedStop),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Dừng", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  [v1.0.9] Update Available Dialog
//  - Tự tải APK về máy và mở installer (không mở browser)
//  - Hiển thị progress bar khi đang tải
//  - Báo lỗi nếu tải thất bại
// ─────────────────────────────────────────────────────────────

@Composable
private fun UpdateAvailableDialog(
    info:             UpdateInfo,
    downloadProgress: Int,           // -1=idle, 0–99=đang tải, -2=lỗi
    onDismiss:        () -> Unit,
    onUpdate:         () -> Unit,
) {
    val isDownloading = downloadProgress in 0..99
    val hasError      = downloadProgress == -2

    Dialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress      = !isDownloading,
            dismissOnClickOutside   = !isDownloading,
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
                    onClick           = { if (!isDownloading) onDismiss() },
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
                border    = BorderStroke(1.dp, BorderDark),
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
                                .background(Brush.linearGradient(listOf(Purple, Pink))),
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
                                color      = Purple,
                                fontSize   = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // ── Version badge ──
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

                    // ── Changelog (scrollable markdown) ──
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
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BgDark),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                MarkdownChangelogText(markdown = info.body)
                            }
                        }
                    }

                    // ── [v1.0.9] Download progress bar ──
                    AnimatedVisibility(
                        visible = isDownloading || hasError,
                        enter   = fadeIn(tween(200)) + expandVertically(tween(250)),
                        exit    = fadeOut(tween(150)) + shrinkVertically(tween(200)),
                    ) {
                        Column {
                            Spacer(Modifier.height(14.dp))
                            if (isDownloading) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment     = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Đang tải APK...",
                                        color    = TextSec,
                                        fontSize = 11.sp,
                                    )
                                    Text(
                                        "$downloadProgress%",
                                        color      = Purple,
                                        fontSize   = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { downloadProgress / 100f },
                                    modifier  = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color      = Purple,
                                    trackColor = BorderDark,
                                )
                            } else if (hasError) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.ErrorOutline,
                                        contentDescription = null,
                                        tint     = RedStop,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        "Tải thất bại. Kiểm tra mạng rồi thử lại.",
                                        color    = RedStop,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // ── Buttons ──
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick  = onDismiss,
                            enabled  = !isDownloading,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape    = RoundedCornerShape(12.dp),
                            colors   = ButtonDefaults.outlinedButtonColors(contentColor = TextSec),
                            border   = BorderStroke(1.dp, BorderDark),
                        ) {
                            Text("Để sau", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }

                        Button(
                            onClick  = onUpdate,
                            enabled  = !isDownloading,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape    = RoundedCornerShape(12.dp),
                            colors   = ButtonDefaults.buttonColors(
                                containerColor         = Purple,
                                disabledContainerColor = Purple.copy(alpha = 0.5f),
                            ),
                        ) {
                            if (isDownloading) {
                                CircularProgressIndicator(
                                    modifier    = Modifier.size(14.dp),
                                    color       = Color.White,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.FileDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (isDownloading) "Đang tải..." else "Cập nhật",
                                fontSize   = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
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
private fun IdleView(state: DashboardUiState, vm: DashboardViewModel, golikeState: GolikeUiState) {
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

            Spacer(Modifier.height(20.dp))

            // ── [v1.1.9] Golike summary card ──
            GolikeSummaryCard(
                state    = golikeState,
                onSetup  = { context.startActivity(Intent(context, ConfigActivity::class.java)) },
            )

            Spacer(Modifier.weight(1f))

            // [v1.1.5] ShortcutRow đã được chuyển sang bottom NavigationBar — không cần ở đây nữa.

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
    Image(
        painter            = painterResource(R.drawable.icon_app),
        contentDescription = "AT PRO",
        contentScale       = ContentScale.Crop,
        modifier           = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp)),
    )
}

// ─────────────────────────────────────────────────────────────
//  [v1.1.5] MinimizedBubble — bubble tròn khi popup được thu nhỏ
//  Hiển thị logo app + pulse ring để báo farm vẫn đang chạy.
//  Tap để mở lại popup StartupStatusDialog.
// ─────────────────────────────────────────────────────────────

@Composable
private fun MinimizedBubble(
    isPaused: Boolean,
    onExpand: () -> Unit,
) {
    val inf    = rememberInfiniteTransition(label = "bubble_pulse")
    val pulse  by inf.animateFloat(
        initialValue  = 0.85f,
        targetValue   = 1.15f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = EaseInOut),
            RepeatMode.Reverse,
        ),
        label = "bubble_scale",
    )
    val ringAlpha by inf.animateFloat(
        initialValue  = 0.55f,
        targetValue   = 0f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = EaseInOut),
            RepeatMode.Reverse,
        ),
        label = "ring_alpha",
    )

    Box(contentAlignment = Alignment.Center) {
        // Pulse ring
        Box(
            modifier = Modifier
                .size(70.dp)
                .scale(pulse)
                .clip(CircleShape)
                .background(
                    (if (isPaused) Amber else Purple).copy(alpha = ringAlpha * 0.35f),
                ),
        )
        // Bubble chính
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            if (isPaused) Amber.copy(alpha = 0.9f) else Purple.copy(alpha = 0.9f),
                            CardDark,
                        ),
                    ),
                )
                .border(1.5.dp, if (isPaused) Amber.copy(alpha = 0.5f) else Purple.copy(alpha = 0.5f), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                    onClick           = onExpand,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter            = painterResource(R.drawable.icon_app),
                contentDescription = "Mở lại farm status",
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .size(38.dp)
                    .clip(CircleShape),
            )
        }
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
        border   = BorderStroke(1.dp, SolidColor(BorderDark)),
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

// ─────────────────────────────────────────────────────────────
//  [v1.1.9] Golike Summary Card — compact dashboard widget
// ─────────────────────────────────────────────────────────────

private val GolikeGold = Color(0xFFF5A623)

@Composable
private fun GolikeSummaryCard(
    state:   GolikeUiState,
    onSetup: () -> Unit,
) {
    if (state.isLoading) return  // don't flash empty card on startup

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(CardDark)
            .border(1.dp, if (state.isLoggedIn) GolikeGold.copy(alpha = 0.22f) else BorderDark,
                androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                    .background(GolikeGold.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.CurrencyExchange, null, tint = GolikeGold, modifier = Modifier.size(17.dp))
            }

            if (!state.isLoggedIn) {
                Column(Modifier.weight(1f)) {
                    Text("Golike", color = GolikeGold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Chưa đăng nhập — bấm để cài đặt", color = TextMuted, fontSize = 11.sp)
                }
                androidx.compose.material3.TextButton(
                    onClick = onSetup,
                    colors  = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = GolikeGold),
                ) {
                    Text("Cài đặt", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Column(Modifier.weight(1f)) {
                    Text(state.displayName.ifEmpty { "Golike" }, color = GolikeGold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state.rankName.isNotEmpty()) {
                            Text(state.rankName, color = TextMuted, fontSize = 10.sp)
                            Text("•", color = TextMuted, fontSize = 10.sp)
                        }
                        Text("${state.totalJobCount} nhiệm vụ", color = TextMuted, fontSize = 10.sp)
                    }
                }
                // Coin + TikTok stats
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${state.coin}", color = GolikeGold, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                        Text("coin", color = TextMuted, fontSize = 9.sp)
                    }
                    if (state.tiktokHold > 0 || state.tiktokPending > 0) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("+${state.tiktokHold}", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("hold", color = TextMuted, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
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

// Helper — không cần thiết nữa vì đã import androidx.compose.ui.graphics.SolidColor

// ─────────────────────────────────────────────────────────────
//  Markdown Changelog Renderer
//  Hỗ trợ: # ## ###, **bold**, - bullet, dòng trống
// ─────────────────────────────────────────────────────────────

private val ChangelogPurple = Color(0xFF6C63FF)
private val ChangelogWhite  = Color.White
private val ChangelogSec    = Color(0xFF9CA3AF)

@Composable
private fun MarkdownChangelogText(markdown: String) {
    val lines = markdown.lines()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("### ") -> {
                    Text(
                        text       = line.removePrefix("### "),
                        color      = ChangelogWhite,
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 18.sp,
                        modifier   = Modifier.padding(top = 6.dp, bottom = 1.dp),
                    )
                }
                line.startsWith("## ") -> {
                    Text(
                        text       = line.removePrefix("## "),
                        color      = ChangelogWhite,
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 19.sp,
                        modifier   = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                line.startsWith("# ") -> {
                    Text(
                        text       = line.removePrefix("# "),
                        color      = ChangelogWhite,
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 20.sp,
                        modifier   = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    Row(modifier = Modifier.padding(top = 1.dp, bottom = 1.dp)) {
                        Text(
                            text  = "•  ",
                            color = ChangelogPurple,
                            fontSize = 11.sp,
                        )
                        Text(
                            text       = parseInlineBold(line.substring(2)),
                            color      = ChangelogSec,
                            fontSize   = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
                line.isBlank() -> {
                    Spacer(Modifier.height(4.dp))
                }
                else -> {
                    Text(
                        text       = parseInlineBold(line),
                        color      = ChangelogSec,
                        fontSize   = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

/** Chuyển **text** thành AnnotatedString có in đậm trắng. */
private fun parseInlineBold(text: String): AnnotatedString {
    val boldPattern = Regex("""\*\*(.+?)\*\*""")
    return buildAnnotatedString {
        var cursor = 0
        for (match in boldPattern.findAll(text)) {
            // Phần trước bold
            append(text.substring(cursor, match.range.first))
            // Phần bold
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = ChangelogWhite)) {
                append(match.groupValues[1])
            }
            cursor = match.range.last + 1
        }
        // Phần còn lại
        append(text.substring(cursor))
    }
}
