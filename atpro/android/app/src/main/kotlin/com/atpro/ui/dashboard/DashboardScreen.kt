package com.atpro.ui.dashboard

import android.content.Intent
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AnimatedNumber
import com.atpro.ui.theme.AtButton
import com.atpro.ui.theme.AtButtonStyle
import com.atpro.ui.theme.AtCard
import com.atpro.ui.theme.AtDialogCard
import com.atpro.ui.theme.AtSectionLabel
import com.atpro.ui.theme.AtSegmented
import com.atpro.ui.theme.AtStatus
import com.atpro.ui.theme.AtType
import com.atpro.ui.theme.LiveDot
import com.atpro.ui.theme.pressClickable
import com.atpro.ui.theme.staggerIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.atpro.R
import com.atpro.tasks.f4f.F4fAuth
import com.atpro.tasks.f4f.F4fAuthState
import com.atpro.ui.theme.AtHeroCard
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

// ── Design tokens ─────────────────────────────────────────────
private val BgDark get() = AtColors.Bg
private val CardDark get() = AtColors.Surface
private val BorderDark get() = AtColors.Hairline
private val Purple get() = AtColors.Accent
private val Pink get() = AtColors.Rose
private val Green get() = AtColors.Success
private val Amber get() = AtColors.Warning
private val RedStop get() = AtColors.Danger
private val TextPrim get() = AtColors.TextPrim
private val TextSec get() = AtColors.TextSec
private val TextMuted get() = AtColors.TextMuted // ─────────────────────────────────────────────────────────────
//  Root Screen
// ─────────────────────────────────────────────────────────────

@Composable
fun DashboardScreen(vm: DashboardViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    // [v1.0.5 → v1.2.9] Kiểm tra phiên bản mới — ĐÃ CHUYỂN lên MainScreen.kt để popup
    // không bị mất khi người dùng chuyển tab (DashboardScreen bị unmount khi rời tab Dashboard).
    //
    // v1.9.0: bỏ hộp thoại "Đang khởi động" + bong bóng thu nhỏ trong app — trùng chức năng với popup nổi
    // (OverlayFarmMonitor) và che mất Tổng quan; trạng thái hiện thời nay là một dòng ngay trong FarmingView.

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // [v1.0.4] Bỏ permission gate — mở thẳng Dashboard không cần cấp quyền trước.
        // Việc cấp quyền được thực hiện ở Cài đặt → Quyền & trợ năng.
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
    }
}

// ─────────────────────────────────────────────────────────────
//  [v1.0.9] Update Available Dialog
//  - Tự tải APK về máy và mở installer (không mở browser)
//  - Hiển thị progress bar khi đang tải
//  - Báo lỗi nếu tải thất bại
// ─────────────────────────────────────────────────────────────

@Composable
// v1.2.9: bỏ "private" — được gọi từ MainScreen.kt để hiển thị bất kể tab đang chọn
fun UpdateAvailableDialog(
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
                .background(AtColors.Scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                    onClick           = { if (!isDownloading) onDismiss() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            AtDialogCard(
                widthFraction = 0.88f,
                modifier      = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                    onClick           = { /* absorb — không đóng khi chạm vào thẻ */ },
                ),
            ) {
                // ── Tiêu đề + mã phiên bản ──
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Có phiên bản mới", style = AtType.Title.copy(fontSize = 18.sp), color = TextPrim)
                        if (info.releaseName.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(info.releaseName, color = TextMuted, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(info.tagName, color = TextPrim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }

                // ── Thay đổi (markdown, cuộn được) ──
                if (info.body.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    AtSectionLabel("Thay đổi", Modifier.padding(bottom = 8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AtColors.SurfaceHigh),
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

                // ── Tiến độ tải ──
                AnimatedVisibility(
                    visible = isDownloading || hasError,
                    enter   = fadeIn(tween(200)) + expandVertically(tween(250)),
                    exit    = fadeOut(tween(150)) + shrinkVertically(tween(200)),
                ) {
                    Column {
                        Spacer(Modifier.height(16.dp))
                        if (isDownloading) {
                            Row(
                                modifier              = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Đang tải", color = TextSec, fontSize = 12.sp)
                                Text("$downloadProgress%", color = TextPrim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress   = { downloadProgress / 100f },
                                modifier   = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color      = Purple,
                                trackColor = BorderDark,
                            )
                        } else if (hasError) {
                            Text(
                                "Tải thất bại",
                                color    = RedStop,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── Nút ──
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AtButton(
                        text     = "Để sau",
                        onClick  = onDismiss,
                        modifier = Modifier.weight(1f),
                        enabled  = !isDownloading,
                        style    = AtButtonStyle.Secondary,
                        height   = 46.dp,
                    )
                    AtButton(
                        text     = if (isDownloading) "Đang tải..." else "Cập nhật",
                        onClick  = onUpdate,
                        modifier = Modifier.weight(1f),
                        enabled  = !isDownloading,
                        icon     = if (isDownloading) null else Icons.Rounded.FileDownload,
                        height   = 46.dp,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Idle view — v1.4.0: bố cục tối giản (logo · trạng thái · số lớn · điều khiển · nút chính)
// ─────────────────────────────────────────────────────────────

@Composable
private fun IdleView(
    state: DashboardUiState,
    vm:    DashboardViewModel,
) {
    val context = LocalContext.current

    val (startLabel, startAction) = when (state.serviceMode) {
        com.atpro.automation.ServiceMode.FARM              -> "Bắt đầu nuôi TikTok"    to { vm.startFarm() }
        com.atpro.automation.ServiceMode.TASK              -> "Bắt đầu làm nhiệm vụ"   to { vm.startTask() }
        com.atpro.automation.ServiceMode.FACEBOOK_NURTURE  -> "Bắt đầu nuôi Facebook"  to { vm.startFacebookNurture() }
        com.atpro.automation.ServiceMode.X_NURTURE         -> "Bắt đầu nuôi X"         to { vm.startXNurture() }
        com.atpro.automation.ServiceMode.INSTAGRAM_NURTURE -> "Bắt đầu nuôi Instagram" to { vm.startInstagramNurture() }
        com.atpro.automation.ServiceMode.THREADS_NURTURE   -> "Bắt đầu nuôi Threads"   to { vm.startThreadsNurture() }
        com.atpro.automation.ServiceMode.SNAPCHAT_NURTURE  -> "Bắt đầu nuôi Snapchat"  to { vm.startSnapchatNurture() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .statusBarsPadding()
    ) {
        Spacer(Modifier.height(20.dp))

        // ── Header: logo + tên · trạng thái (Cài đặt đã có tab riêng ở thanh dưới) ──
        Row(
            modifier          = Modifier.fillMaxWidth().staggerIn(0),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppLogo()
            Spacer(Modifier.width(10.dp))
            Text(
                "AT PRO",
                color         = TextPrim,
                fontSize      = 15.sp,
                fontWeight    = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.weight(1f))
            AtStatus(
                text    = if (state.serviceConnected) "Sẵn sàng" else "Chưa bật dịch vụ",
                color   = if (state.serviceConnected) Green else TextMuted,
                pulsing = state.serviceConnected,
            )
        }

        Spacer(Modifier.height(28.dp))

        // ── Thẻ nổi bật: dịch vụ đang chọn · số tài khoản (số đếm lên mượt) ──
        val serviceLabel = when (state.serviceMode) {
            com.atpro.automation.ServiceMode.FARM              -> "Nuôi TikTok"
            com.atpro.automation.ServiceMode.TASK              -> "Nhiệm vụ kiếm tiền"
            com.atpro.automation.ServiceMode.FACEBOOK_NURTURE  -> "Nuôi Facebook"
            com.atpro.automation.ServiceMode.X_NURTURE         -> "Nuôi X"
            com.atpro.automation.ServiceMode.INSTAGRAM_NURTURE -> "Nuôi Instagram"
            com.atpro.automation.ServiceMode.THREADS_NURTURE   -> "Nuôi Threads"
            com.atpro.automation.ServiceMode.SNAPCHAT_NURTURE  -> "Nuôi Snapchat"
        }
        val f4f by F4fAuth.manager(context).state.collectAsStateWithLifecycle()
        AtHeroCard(
            modifier       = Modifier.fillMaxWidth().staggerIn(1),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    serviceLabel,
                    color      = Color.White,
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier   = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.18f))
                        .padding(horizontal = 11.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            AnimatedNumber(
                value = state.displayCount,
                style = AtType.Display.copy(fontSize = 68.sp, lineHeight = 68.sp),
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text  = when {
                    state.serviceMode == com.atpro.automation.ServiceMode.TASK && state.listMode -> "tài khoản đã chọn nhận nhiệm vụ"
                    state.listMode           -> "tài khoản trong danh sách"
                    state.displayCount == 0  -> "tài khoản"
                    else                     -> "tài khoản hoạt động"
                },
                color    = Color.White.copy(alpha = 0.86f),
                fontSize = 14.sp,
            )
            val loggedIn = f4f as? F4fAuthState.LoggedIn
            if (state.serviceMode == com.atpro.automation.ServiceMode.TASK && loggedIn != null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Số dư Follow4Follow", color = Color.White.copy(alpha = 0.86f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(loggedIn.balance ?: "—", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // ── Điều khiển + nút chính ──
        Column(Modifier.fillMaxWidth().staggerIn(2)) {
            // v1.12.0: chế độ nhiệm vụ có cách chọn tài khoản riêng (Cài đặt → Nhiệm vụ → Tài khoản): hiện tóm tắt thay cho ô gõ tay.
            AnimatedVisibility(visible = state.serviceMode == com.atpro.automation.ServiceMode.TASK) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(AtColors.Surface)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Tài khoản nhận nhiệm vụ", color = AtColors.TextPrim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (state.taskAccountManual) "Chọn tay · ${state.taskSelectedCount} tài khoản"
                                else "Tự động · mọi acc đã liên kết và có trên máy",
                                color = TextMuted, fontSize = 12.sp,
                            )
                        }
                        Text("Cài đặt › Nhiệm vụ", color = AtColors.Accent, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            AnimatedVisibility(visible = !state.isDemoMode && state.serviceMode != com.atpro.automation.ServiceMode.TASK) {
                Column {
                    AtSegmented(
                        options       = listOf("Toàn bộ máy", "Danh sách chọn"),
                        selectedIndex = if (state.farmMode == FarmMode.SELECTED_LIST) 1 else 0,
                        onSelect      = { vm.setFarmMode(if (it == 1) FarmMode.SELECTED_LIST else FarmMode.ALL_LOCAL) },
                    )
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
                    Spacer(Modifier.height(16.dp))
                }
            }

            AtButton(
                text    = startLabel,
                onClick = startAction,
                enabled = state.canStart,
                icon    = Icons.Rounded.PlayArrow,
            )

            AnimatedVisibility(
                visible = state.startHint != null,
                enter   = fadeIn(tween(200)) + expandVertically(tween(200)),
                exit    = fadeOut(tween(150)) + shrinkVertically(tween(150)),
            ) {
                Text(
                    text      = state.startHint ?: "",
                    color     = TextMuted,
                    fontSize  = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─────────────────────────────────────────────────────────────
//  Farming view — v1.4.0
// ─────────────────────────────────────────────────────────────

@Composable
private fun FarmingView(state: DashboardUiState, vm: DashboardViewModel) {
    val stats    = state.liveStats
    val taskMode = state.serviceMode == com.atpro.automation.ServiceMode.TASK

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .statusBarsPadding()
    ) {
        Spacer(Modifier.height(20.dp))

        // ── Header: trạng thái · dừng ──
        Row(
            modifier          = Modifier.fillMaxWidth().height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState    = state.isPaused,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                label          = "pause_label",
            ) { paused ->
                AtStatus(
                    text    = if (paused) "Đang tạm dừng" else "Đang chạy",
                    color   = if (paused) Amber else Green,
                    pulsing = !paused,
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

        CurrentAccountCard(stats, taskMode)

        Spacer(Modifier.height(12.dp))

        AnimatedVisibility(
            visible = stats.account.isNotEmpty(),
            enter   = fadeIn(tween(400)) + expandVertically(tween(400)),
        ) {
            StatsGrid(stats, taskMode)
        }

        // v1.9.0: dòng trạng thái hiện thời (thay cho hộp thoại "Đang khởi động" che màn hình).
        AnimatedVisibility(
            visible = state.startupStatus.isNotBlank(),
            enter   = fadeIn(tween(250)) + expandVertically(tween(250)),
            exit    = fadeOut(tween(150)) + shrinkVertically(tween(150)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AtColors.SurfaceHigh)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiveDot(color = if (state.isPaused) Amber else AtColors.Sky, pulsing = !state.isPaused)
                Spacer(Modifier.width(8.dp))
                Text(
                    state.startupStatus,
                    color    = TextSec,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        AtButton(
            text    = if (state.isPaused) "Tiếp tục" else "Tạm dừng",
            onClick = { if (state.isPaused) vm.resume() else vm.pause() },
            icon    = if (state.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
            style   = AtButtonStyle.Secondary,
        )

        Spacer(Modifier.height(32.dp))
    }
}

// ─────────────────────────────────────────────────────────────
//  Account list input (SELECTED_LIST mode)
// ─────────────────────────────────────────────────────────────

@Composable
private fun AccountListInput(value: String, onChanged: (String) -> Unit) {
    Column {
        OutlinedTextField(
            value         = value,
            onValueChange = onChanged,
            modifier      = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp, max = 200.dp),
            placeholder = {
                Text(
                    "username1\nusername2\n@username3",
                    color    = TextMuted,
                    fontSize = 13.sp,
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            shape  = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor   = CardDark,
                unfocusedContainerColor = CardDark,
                focusedBorderColor      = AtColors.Blue,
                unfocusedBorderColor    = AtColors.HairlineHi,
                focusedTextColor        = TextPrim,
                unfocusedTextColor      = TextPrim,
                cursorColor             = AtColors.Blue,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Mỗi dòng một tài khoản.",
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
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun CurrentAccountCard(stats: LiveFarmStats, taskMode: Boolean) {
    // v1.9.0: chế độ nhiệm vụ đo tiến độ theo số job đã xong của acc hiện tại (✓ n / N), không theo số acc.
    val progress = when {
        taskMode -> if (stats.jobsTarget > 0) (stats.jobsDone.toFloat() / stats.jobsTarget).coerceIn(0f, 1f) else 0f
        else     -> if (stats.total > 0) stats.index.toFloat() / stats.total else 0f
    }
    val animProgress by animateFloatAsState(
        targetValue   = progress,
        animationSpec = tween(500, easing = EaseOut),
        label         = "progress",
    )

    AtHeroCard(
        modifier       = Modifier.fillMaxWidth().staggerIn(0),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier.fillMaxWidth(),
        ) {
            AnimatedContent(
                targetState    = stats.account,
                transitionSpec = {
                    (fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 }) togetherWith
                        (fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 4 })
                },
                label = "account_name",
            ) { acc ->
                Text(
                    "@${acc.ifEmpty { "..." }}",
                    style = AtType.Title,
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (taskMode) {
                    "Job ${stats.jobsDone} / ${stats.jobsTarget}" +
                        (if (stats.total > 0) "  ·  acc ${stats.index}/${stats.total}" else "") +
                        (if (stats.reward > 0.0) "  ·  +${if (stats.reward % 1.0 == 0.0) stats.reward.toLong().toString() else "%.2f".format(stats.reward)}" else "")
                } else "${stats.index} / ${stats.total}",
                color    = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))
            LinearProgressIndicator(
                progress   = { animProgress },
                modifier   = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50)),
                color      = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
            )
        }
    }
}

@Composable
private fun StatsGrid(stats: LiveFarmStats, taskMode: Boolean) {
    val mins = stats.remainingSecs / 60
    val secs = stats.remainingSecs % 60
    AtCard(
        modifier       = Modifier.fillMaxWidth().staggerIn(1),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            if (taskMode) {
                // v1.9.0: nhiệm vụ không có "Video"/"Còn lại" → Job · Thích · Theo · Lỗi liên tiếp.
                StatCell(Modifier.weight(1f), "Job")   { AnimatedNumber(stats.jobsDone, AtType.Number) }
                StatDivider()
                StatCell(Modifier.weight(1f), "Thích") { AnimatedNumber(stats.likes,    AtType.Number) }
                StatDivider()
                StatCell(Modifier.weight(1f), "Theo")  { AnimatedNumber(stats.follows,  AtType.Number) }
                StatDivider()
                StatCell(Modifier.weight(1f), "Lỗi") {
                    Text("${stats.consecErrors}", style = AtType.Number, color = if (stats.consecErrors > 0) Amber else TextPrim)
                }
            } else {
                StatCell(Modifier.weight(1f), "Video") { AnimatedNumber(stats.videos,  AtType.Number) }
                StatDivider()
                StatCell(Modifier.weight(1f), "Thích") { AnimatedNumber(stats.likes,   AtType.Number) }
                StatDivider()
                StatCell(Modifier.weight(1f), "Theo")  { AnimatedNumber(stats.follows, AtType.Number) }
                StatDivider()
                StatCell(Modifier.weight(1f), "Còn lại") {
                    Text("$mins:${secs.toString().padStart(2, '0')}", style = AtType.Number, color = TextPrim)
                }
            }
        }
    }
}

@Composable
private fun StatCell(modifier: Modifier, label: String, value: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        value()
        Spacer(Modifier.height(4.dp))
        Text(label, color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun StatDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(BorderDark))
}

// ─────────────────────────────────────────────────────────────
//  Markdown Changelog Renderer
//  Hỗ trợ: # ## ###, **bold**, - bullet, dòng trống
// ─────────────────────────────────────────────────────────────

private val ChangelogPurple get() = AtColors.Accent
private val ChangelogWhite get() = AtColors.TextPrim
private val ChangelogSec get() = AtColors.TextSec
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
