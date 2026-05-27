package com.atpro.ui.config

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atpro.data.AccessibilitySettingsHelper
import com.atpro.data.TikTokDeepLinks
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

// ── Palette ───────────────────────────────────────────────────
private val BgDark      = Color(0xFF0D0D14)
private val BgDeeper    = Color(0xFF08080F)
private val CardDark    = Color(0xFF1A1A2E)
private val BorderDark  = Color(0xFF2D2D44)
private val Purple      = Color(0xFF6C63FF)
private val Pink        = Color(0xFFEC4899)
private val Green       = Color(0xFF10B981)
private val Amber       = Color(0xFFF59E0B)
private val Cyan        = Color(0xFF06B6D4)
private val TextPrim    = Color(0xFFE5E7EB)
private val TextSec     = Color(0xFF9CA3AF)
private val TextMuted   = Color(0xFF6B7280)

// ─────────────────────────────────────────────────────────────
//  Root
// ─────────────────────────────────────────────────────────────

@Composable
fun ConfigScreen(vm: ConfigViewModel) {
    val state   by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.loadTikTokVersion(context)
        vm.refreshPermissions(context)
    }
    LaunchedEffect(vm.saved) {
        vm.saved.collectLatest { snackbar.showSnackbar("Đã lưu ✓") }
    }

    if (state.isLoading) {
        Box(Modifier.fillMaxSize().background(BgDark), Alignment.Center) {
            CircularProgressIndicator(color = Purple)
        }
        return
    }

    Scaffold(
        containerColor = BgDark,
        snackbarHost   = { SnackbarHost(snackbar) { data ->
            Snackbar(
                snackbarData   = data,
                containerColor = Green,
                contentColor   = Color.White,
                modifier       = Modifier.padding(16.dp),
            )
        }},
        topBar = { ConfigTopBar(state.isDirty, state.isSaving, vm::save) },
    ) { padding ->
        ConfigTabLayout(
            state    = state,
            onSet    = vm::set,
            onOpenAccessibility = { AccessibilitySettingsHelper.openAccessibilitySettings(context) },
            onOpenOverlay       = { AccessibilitySettingsHelper.openOverlaySettings(context) },
            onOpenNotification  = { AccessibilitySettingsHelper.openAppSettings(context) },
            modifier = Modifier.padding(padding).statusBarsPadding(),
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  Top bar
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigTopBar(isDirty: Boolean, isSaving: Boolean, onSave: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text("Cài đặt", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = TextPrim)
                AnimatedVisibility(
                    visible = isDirty,
                    enter   = fadeIn(tween(150)) + expandVertically(tween(200)),
                    exit    = fadeOut(tween(100)) + shrinkVertically(tween(150)),
                ) {
                    Text("● Chưa lưu", color = Amber.copy(alpha = 0.9f), fontSize = 10.sp)
                }
            }
        },
        colors  = TopAppBarDefaults.topAppBarColors(containerColor = BgDark),
        actions = {
            AnimatedContent(
                targetState = isDirty,
                transitionSpec = {
                    (fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.85f)) togetherWith
                        (fadeOut(tween(150)) + scaleOut(tween(150)))
                },
                label = "save_btn",
            ) { dirty ->
                if (dirty) {
                    Button(
                        onClick  = onSave,
                        enabled  = !isSaving,
                        modifier = Modifier.height(34.dp).padding(end = 12.dp),
                        shape    = RoundedCornerShape(10.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = Purple),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        } else {
                            Icon(Icons.Rounded.Check, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Lưu", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────
//  Tab layout  (5 tabs — v1.0.4 thêm tab Quyền)
// ─────────────────────────────────────────────────────────────

@Composable
private fun ConfigTabLayout(
    state:    ConfigUiState,
    onSet:    (ConfigUiState.() -> ConfigUiState) -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay:       () -> Unit,
    onOpenNotification:  () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableIntStateOf(0) }

    data class Tab(val label: String, val icon: ImageVector)
    val tabs = listOf(
        Tab("Thời gian",  Icons.Rounded.Timer),
        Tab("Hành động",  Icons.Rounded.TouchApp),
        Tab("Thông báo",  Icons.Rounded.NotificationsNone),
        Tab("TikTok",     Icons.Rounded.Link),
        Tab("Quyền",      Icons.Rounded.Security),       // [v1.0.4] tab mới
    )

    Column(modifier.fillMaxSize()) {
        // ── Pill tab strip ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .padding(4.dp),
        ) {
            tabs.forEachIndexed { i, tab ->
                val active = selected == i
                val bg by animateColorAsState(
                    targetValue   = if (active) Purple else Color.Transparent,
                    animationSpec = tween(200), label = "tb$i",
                )
                val fg by animateColorAsState(
                    targetValue   = if (active) Color.White else TextMuted,
                    animationSpec = tween(200), label = "tf$i",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(bg)
                        .clickable { selected = i }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(tab.icon, null, tint = fg, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.height(3.dp))
                        Text(
                            tab.label,
                            color      = fg,
                            fontSize   = 9.5.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines   = 1,
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = BorderDark, thickness = 0.5.dp)

        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (fadeIn(tween(220)) + slideInHorizontally(tween(250)) { it / 8 * dir }) togetherWith
                    (fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { -it / 8 * dir })
            },
            label = "tab_content",
        ) { tab ->
            when (tab) {
                0 -> TimingTab(state, onSet)
                1 -> ActionsTab(state, onSet)
                2 -> NotificationsTab(state, onSet)
                3 -> TikTokTab(state)
                4 -> PermissionsTab(
                    state               = state,
                    onOpenAccessibility = onOpenAccessibility,
                    onOpenOverlay       = onOpenOverlay,
                    onOpenNotification  = onOpenNotification,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Tab 1: Timing
// ─────────────────────────────────────────────────────────────

@Composable
private fun TimingTab(state: ConfigUiState, onSet: (ConfigUiState.() -> ConfigUiState) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CfgCard {
            CfgSectionHeader("Thời lượng", Icons.Rounded.Schedule, Purple)
            CfgSlider(
                label    = "Farm mỗi tài khoản",
                display  = "${state.minutesPerAccount} phút",
                value    = state.minutesPerAccount.toFloat(),
                range    = 1f..60f, steps = 58,
                accent   = Purple,
                onChanged = { onSet { copy(minutesPerAccount = it.toInt()) } },
            )
            CfgDividerThin()
            CfgSlider(
                label    = "Xem video tối thiểu",
                display  = "${"%.1f".format(state.watchMin)}s",
                value    = state.watchMin.toFloat(),
                range    = 1f..15f, steps = 27,
                accent   = Cyan,
                onChanged = { onSet { copy(watchMin = it.toDouble()) } },
            )
            CfgSlider(
                label    = "Xem video tối đa",
                display  = "${"%.1f".format(state.watchMax)}s",
                value    = state.watchMax.toFloat(),
                range    = 2f..30f, steps = 55,
                accent   = Cyan,
                onChanged = { v ->
                    onSet { copy(watchMax = v.toDouble().coerceAtLeast(watchMin + 1.0)) }
                },
            )
        }

        CfgCard {
            CfgSectionHeader("Nghỉ giữa tài khoản", Icons.Rounded.Coffee, Amber)
            CfgSwitch(
                label    = "Bật thời gian nghỉ",
                value    = state.enableRest,
                accent   = Amber,
                onChanged = { onSet { copy(enableRest = it) } },
            )
            AnimatedVisibility(
                visible = state.enableRest,
                enter   = fadeIn(tween(250)) + expandVertically(tween(300, easing = EaseOut)),
                exit    = fadeOut(tween(200)) + shrinkVertically(tween(250, easing = EaseIn)),
            ) {
                Column {
                    CfgDividerThin()
                    CfgSlider(
                        label    = "Thời gian nghỉ",
                        display  = "${state.restMinutes} phút",
                        value    = state.restMinutes.toFloat(),
                        range    = 1f..30f, steps = 28,
                        accent   = Amber,
                        onChanged = { onSet { copy(restMinutes = it.toInt()) } },
                    )
                }
            }
        }

        CfgCard {
            CfgSectionHeader("Khôi phục", Icons.Rounded.Refresh, Pink)
            CfgStepper(
                label    = "Số lần nhấn Back tối đa",
                value    = state.maxBackAttempts,
                range    = 1..20,
                accent   = Pink,
                onChanged = { onSet { copy(maxBackAttempts = it) } },
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─────────────────────────────────────────────────────────────
//  Tab 2: Actions
// ─────────────────────────────────────────────────────────────

@Composable
private fun ActionsTab(state: ConfigUiState, onSet: (ConfigUiState.() -> ConfigUiState) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CfgCard {
            CfgSectionHeader("Tương tác", Icons.Rounded.Favorite, Pink)
            CfgSlider(
                label    = "Tỉ lệ thích video",
                display  = "${(state.likeRate * 100).toInt()}%",
                value    = state.likeRate,
                range    = 0f..1f, steps = 19,
                accent   = Pink,
                onChanged = { onSet { copy(likeRate = it) } },
            )
            CfgDividerThin()
            CfgSlider(
                label    = "Tỉ lệ theo dõi",
                display  = "${(state.followRate * 100).toInt()}%",
                value    = state.followRate,
                range    = 0f..1f, steps = 19,
                accent   = Green,
                onChanged = { onSet { copy(followRate = it) } },
            )
        }

        CfgCard {
            CfgSectionHeader("Hành vi tự động", Icons.Rounded.SmartToy, Purple)
            CfgSwitch(
                label    = "Bỏ qua video trực tiếp",
                subtitle = "Tự động vuốt qua khi gặp livestream",
                value    = state.skipLive,
                accent   = Purple,
                onChanged = { onSet { copy(skipLive = it) } },
            )
            CfgDividerThin()
            CfgSwitch(
                label    = "Xác nhận tài khoản sau khi chuyển",
                subtitle = "Vào profile kiểm tra đúng tài khoản",
                value    = state.verifyAccount,
                accent   = Purple,
                onChanged = { onSet { copy(verifyAccount = it) } },
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─────────────────────────────────────────────────────────────
//  Tab 3: Notifications
// ─────────────────────────────────────────────────────────────

@Composable
private fun NotificationsTab(
    state: ConfigUiState,
    onSet: (ConfigUiState.() -> ConfigUiState) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CfgCard {
            CfgSectionHeader("Telegram", Icons.Rounded.Send, Cyan)
            CfgTextField(
                label    = "Bot Token",
                value    = state.telegramToken,
                hint     = "123456789:ABCdef...",
                obscure  = true,
                onChanged = { onSet { copy(telegramToken = it) } },
            )
            CfgTextField(
                label    = "Chat ID",
                value    = state.telegramChatId,
                hint     = "-100...",
                isLast   = true,
                onChanged = { onSet { copy(telegramChatId = it) } },
            )
        }

        CfgCard {
            CfgSectionHeader("Discord", Icons.Rounded.Forum, Purple)
            CfgTextField(
                label    = "Webhook URL",
                value    = state.discordWebhook,
                hint     = "https://discord.com/api/webhooks/...",
                obscure  = true,
                isLast   = true,
                onChanged = { onSet { copy(discordWebhook = it) } },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Rounded.Info, null, tint = TextMuted, modifier = Modifier.size(15.dp).padding(top = 1.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Thông báo được gửi khi bắt đầu farm, hoàn thành phiên, hoặc phát hiện checkpoint.",
                color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─────────────────────────────────────────────────────────────
//  Tab 4: TikTok  (v1.0.4 — hiển thị package + version)
// ─────────────────────────────────────────────────────────────

@Composable
private fun TikTokTab(state: ConfigUiState) {
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── App info ──
        CfgCard {
            CfgSectionHeader("Ứng dụng", Icons.Rounded.PhoneAndroid, Purple)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BgDeeper)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("♪", color = Color.White, fontSize = 20.sp)
                }
                // [v1.0.4] Hiển thị package + version riêng biệt
                val parts = state.tikTokVersion.split("\n")
                val hasPackage = parts.size > 1
                Column(Modifier.weight(1f)) {
                    Text(
                        "TikTok",
                        color = TextPrim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                    if (hasPackage) {
                        Text(
                            parts[0],  // package name
                            color = TextMuted, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "v${parts[1]}",  // version
                            color = TextSec, fontSize = 11.sp,
                        )
                    } else {
                        Text(state.tikTokVersion, color = TextMuted, fontSize = 11.sp)
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            if (hasPackage) Green.copy(alpha = 0.12f)
                            else            TextMuted.copy(alpha = 0.10f)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        if (hasPackage) "Đang dùng" else "Không tìm thấy",
                        color = if (hasPackage) Green else TextMuted,
                        fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // ── Quick actions ──
        CfgCard {
            CfgSectionHeader("Thao tác nhanh", Icons.Rounded.OpenInNew, Amber)
            QuickAction(
                icon     = Icons.Rounded.Settings,
                label    = "Mở Settings TikTok",
                scheme   = "snssdk1233://setting",
                color    = Purple,
                onClick  = { TikTokDeepLinks.openSettings(context) },
            )
            Spacer(Modifier.height(10.dp))
            QuickAction(
                icon     = Icons.Rounded.PrivacyTip,
                label    = "Mở Quyền riêng tư",
                scheme   = "snssdk1233://privacy",
                color    = Cyan,
                onClick  = { TikTokDeepLinks.openPrivacySettings(context) },
            )
        }

        // ── Deeplink reference ──
        CfgCard {
            CfgSectionHeader("Deeplink (patch2)", Icons.Rounded.Code, TextMuted)
            val rows = listOf(
                "Settings" to "snssdk1233://setting",
                "Privacy"  to "snssdk1233://privacy",
                "Flags"    to "NEW_TASK | CLEAR_TOP",
                "Encoding" to "Base64 (anti-scan)",
            )
            rows.forEachIndexed { i, (k, v) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(k, color = TextMuted, fontSize = 12.sp)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(BgDeeper)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(v, color = TextSec, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                if (i < rows.lastIndex) CfgDividerThin()
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─────────────────────────────────────────────────────────────
//  Tab 5: Permissions  [v1.0.4 — mới]
// ─────────────────────────────────────────────────────────────

@Composable
private fun PermissionsTab(
    state:               ConfigUiState,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay:       () -> Unit,
    onOpenNotification:  () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Header info ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Purple.copy(alpha = 0.08f))
                .border(1.dp, Purple.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Rounded.Info, null, tint = Purple, modifier = Modifier.size(15.dp).padding(top = 1.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Cấp quyền để AT PRO hoạt động đúng. Nhấn vào mục bên dưới để mở trang cài đặt hệ thống.",
                color = TextSec, fontSize = 12.sp, lineHeight = 18.sp,
            )
        }

        // ── Permission items ──
        CfgCard {
            CfgSectionHeader("Quyền hệ thống", Icons.Rounded.AdminPanelSettings, Purple)

            SettingsPermissionItem(
                icon        = Icons.Rounded.Accessibility,
                iconColor   = Purple,
                title       = "Accessibility Service",
                description = "Bắt buộc. Điều khiển TikTok tự động.",
                granted     = state.accessibilityGranted,
                required    = true,
                onAction    = onOpenAccessibility,
            )

            CfgDividerThin()

            SettingsPermissionItem(
                icon        = Icons.Rounded.Layers,
                iconColor   = Amber,
                title       = "Hiển thị trên ứng dụng khác",
                description = "Hiện popup theo dõi farm khi TikTok đang chạy.",
                granted     = state.overlayGranted,
                required    = false,
                onAction    = onOpenOverlay,
            )

            CfgDividerThin()

            SettingsPermissionItem(
                icon        = Icons.Rounded.NotificationsActive,
                iconColor   = Green,
                title       = "Thông báo",
                description = "Nhận thông báo khi farm xong hoặc có lỗi.",
                granted     = state.notificationGranted,
                required    = false,
                onAction    = onOpenNotification,
            )
        }

        // ── Status summary ──
        val allRequired = state.accessibilityGranted
        AnimatedVisibility(
            visible = allRequired,
            enter   = fadeIn(tween(300)) + expandVertically(tween(350)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Green.copy(alpha = 0.06f))
                    .border(1.dp, Green.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Green, modifier = Modifier.size(16.dp))
                Text(
                    "Quyền bắt buộc đã cấp — sẵn sàng farm.",
                    color = Green, fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsPermissionItem(
    icon:        ImageVector,
    iconColor:   Color,
    title:       String,
    description: String,
    granted:     Boolean,
    required:    Boolean,
    onAction:    () -> Unit,
) {
    val bgColor by animateColorAsState(
        targetValue  = if (granted) Green.copy(alpha = 0.04f) else Color.Transparent,
        animationSpec = tween(300), label = "sp_bg_$title",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon badge
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(iconColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = iconColor, modifier = Modifier.size(18.dp))
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(title, color = TextPrim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (required) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Purple.copy(alpha = 0.15f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text("Bắt buộc", color = Purple, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(description, color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
        }

        Spacer(Modifier.width(10.dp))

        AnimatedContent(
            targetState = granted,
            transitionSpec = {
                (fadeIn(tween(200)) + scaleIn(tween(200))) togetherWith
                    (fadeOut(tween(150)) + scaleOut(tween(150)))
            },
            label = "sp_status_$title",
        ) { isGranted ->
            if (isGranted) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Green, modifier = Modifier.size(22.dp))
            } else {
                OutlinedButton(
                    onClick   = onAction,
                    shape     = RoundedCornerShape(8.dp),
                    modifier  = Modifier.height(32.dp),
                    colors    = ButtonDefaults.outlinedButtonColors(contentColor = iconColor),
                    border    = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = SolidColor(iconColor.copy(alpha = 0.4f)),
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Text("Cấp quyền", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  QuickAction button
// ─────────────────────────────────────────────────────────────

@Composable
private fun QuickAction(
    icon:    ImageVector,
    label:   String,
    scheme:  String,
    color:   Color,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue   = if (pressed) 0.97f else 1f,
        animationSpec = tween(80), label = "qa",
    )
    LaunchedEffect(pressed) { if (pressed) { delay(150); pressed = false } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.07f))
            .border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .clickable { pressed = true; onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(scheme, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = color.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
    }
}

// ─────────────────────────────────────────────────────────────
//  Reusable widgets
// ─────────────────────────────────────────────────────────────

@Composable
private fun CfgCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardDark)
            .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

@Composable
private fun CfgSectionHeader(text: String, icon: ImageVector, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CfgDividerThin() {
    HorizontalDivider(
        color     = BorderDark.copy(alpha = 0.6f),
        thickness = 0.5.dp,
        modifier  = Modifier.padding(vertical = 10.dp),
    )
}

/**
 * [FIX v1.0.4] Cải thiện UI slider:
 *  - Custom thumb nhỏ (18dp circle) thay thumb mặc định Material3 (20dp + ripple lớn)
 *  - Ẩn tick marks (activeTickColor / inactiveTickColor = Transparent) cho gọn
 *  - Bù padding ngang mặc định 10dp của Material3 Slider bằng padding(-10dp) trên Column
 *    thay vì dùng offset/requiredWidth (tránh clip issue trên Compose 1.6+)
 */
@Composable
private fun CfgSlider(
    label:    String,
    display:  String,
    value:    Float,
    range:    ClosedFloatingPointRange<Float>,
    steps:    Int,
    accent:   Color = Purple,
    onChanged: (Float) -> Unit,
) {
    Column(Modifier.padding(bottom = 2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(label, color = TextPrim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(display, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        // [FIX] Material3 Slider thêm 10dp padding ngang nội bộ để chứa thumb ripple.
        // Bù bằng cách bọc trong Box với padding(-10dp) + clip = false để track
        // căn đều với viền card, không dùng offset() (có thể gây clip trên API < 33).
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = (-10).dp)) {
            Slider(
                value         = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onChanged,
                valueRange    = range,
                steps         = steps,
                modifier      = Modifier.fillMaxWidth(),
                colors        = SliderDefaults.colors(
                    thumbColor          = accent,
                    activeTrackColor    = accent,
                    inactiveTrackColor  = BorderDark,
                    activeTickColor     = Color.Transparent,
                    inactiveTickColor   = Color.Transparent,
                ),
                thumb = {
                    // Thumb gọn: hình tròn 18dp, không có ripple zone lớn
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(accent)
                            .border(2.dp, accent.copy(alpha = 0.35f), CircleShape),
                    )
                },
            )
        }
    }
}

@Composable
private fun CfgSwitch(
    label:    String,
    subtitle: String?  = null,
    value:    Boolean,
    accent:   Color    = Purple,
    onChanged: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrim, fontSize = 13.sp)
            if (subtitle != null) Text(
                subtitle, color = TextMuted, fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked         = value,
            onCheckedChange = onChanged,
            colors          = SwitchDefaults.colors(
                checkedThumbColor   = Color.White,
                checkedTrackColor   = accent,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = BorderDark,
            ),
        )
    }
}

@Composable
private fun CfgStepper(
    label:    String,
    value:    Int,
    range:    IntRange,
    accent:   Color = Purple,
    onChanged: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextPrim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        StepBtn(Icons.Rounded.Remove, value > range.first, accent) {
            onChanged((value - 1).coerceAtLeast(range.first))
        }
        Text(
            "$value",
            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        StepBtn(Icons.Rounded.Add, value < range.last, accent) {
            onChanged((value + 1).coerceAtMost(range.last))
        }
    }
}

@Composable
private fun StepBtn(icon: ImageVector, enabled: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) accent.copy(alpha = 0.14f) else BorderDark.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) accent else TextMuted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun CfgTextField(
    label:    String,
    value:    String,
    hint:     String  = "",
    obscure:  Boolean = false,
    isLast:   Boolean = false,
    onChanged: (String) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = if (isLast) 0.dp else 12.dp)) {
        Text(label, color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 5.dp))
        OutlinedTextField(
            value         = value,
            onValueChange = onChanged,
            modifier      = Modifier.fillMaxWidth(),
            placeholder   = { Text(hint, color = Color(0xFF4B5563), fontSize = 12.sp) },
            singleLine    = true,
            visualTransformation = if (obscure && !show) PasswordVisualTransformation()
                                   else VisualTransformation.None,
            trailingIcon  = if (obscure) {{
                IconButton(onClick = { show = !show }) {
                    Icon(
                        if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        null, tint = TextMuted, modifier = Modifier.size(17.dp),
                    )
                }
            }} else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            shape  = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor   = BgDeeper,
                unfocusedContainerColor = BgDeeper,
                focusedBorderColor      = Purple,
                unfocusedBorderColor    = Color.Transparent,
                focusedTextColor        = Color.White,
                unfocusedTextColor      = Color.White,
            ),
        )
    }
}
