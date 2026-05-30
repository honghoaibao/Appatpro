package com.atpro.ui.config

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.BorderStroke
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import com.atpro.data.AccessibilitySettingsHelper
import com.atpro.data.TikTokDeepLinks
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

// ── Palette ────────────────────────────────────────────────────────────────
private val BgDark     = Color(0xFF0D0D14)
private val BgDeeper   = Color(0xFF08080F)
private val BgCard     = Color(0xFF141420)
private val BgCardAlt  = Color(0xFF181826)
private val BorderDark = Color(0xFF252538)
private val BorderHi   = Color(0xFF323250)
private val Purple     = Color(0xFF7C6FE0)
private val PurpleHi   = Color(0xFF9D92F5)
private val Pink       = Color(0xFFEC4899)
private val Green      = Color(0xFF10B981)
private val Amber      = Color(0xFFF59E0B)
private val Cyan       = Color(0xFF22D3EE)
private val Red        = Color(0xFFEF4444)
private val TextPrim   = Color(0xFFEEEEF5)
private val TextSec    = Color(0xFF9CA3AF)
private val TextMuted  = Color(0xFF5C5C78)

// ── Section definitions ────────────────────────────────────────────────────
private enum class Section(val label: String, val icon: ImageVector, val accent: Color) {
    TIMING       ("Thời gian",  Icons.Rounded.Timer,                Purple),
    ACTIONS      ("Hành động",  Icons.Rounded.TouchApp,             Pink),
    NOTIFICATIONS("Thông báo",  Icons.Rounded.NotificationsNone,    Cyan),
    TIKTOK       ("TikTok",     Icons.Rounded.PhoneAndroid,         Amber),
    PERMISSIONS  ("Quyền",      Icons.Rounded.AdminPanelSettings,   Green),
}

// ─────────────────────────────────────────────────────────────────────────────
//  Root
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ConfigScreen(vm: ConfigViewModel) {
    val state   by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.loadTikTokVersion(context)
    }

    // Refresh permission state every time the screen resumes — covers the case
    // where the user left to grant accessibility/overlay and came back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshPermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        vm.saved.collectLatest { snackbar.showSnackbar("Đã lưu") }
    }

    if (state.isLoading) {
        Box(Modifier.fillMaxSize().background(BgDark), Alignment.Center) {
            CircularProgressIndicator(color = Purple, strokeWidth = 2.dp)
        }
        return
    }

    Scaffold(
        containerColor = BgDark,
        snackbarHost = { SnackbarHost(snackbar) { data ->
            Snackbar(
                snackbarData   = data,
                containerColor = Green.copy(alpha = 0.92f),
                contentColor   = Color.White,
                modifier       = Modifier.padding(16.dp),
            )
        }},
        topBar = {
            val activity = context as? Activity
            SettingsTopBar(state.isDirty, state.isSaving, vm::save, onBack = { activity?.finish() })
        },
    ) { padding ->
        SettingsLayout(
            state               = state,
            onSet               = vm::set,
            onOpenAccessibility = { AccessibilitySettingsHelper.openAccessibilitySettings(context) },
            onOpenOverlay       = { AccessibilitySettingsHelper.openOverlaySettings(context) },
            onOpenNotification  = { AccessibilitySettingsHelper.openAppSettings(context) },
            modifier            = Modifier.padding(padding),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Top bar
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(isDirty: Boolean, isSaving: Boolean, onSave: () -> Unit, onBack: () -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "Quay lại", tint = TextPrim)
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Purple.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Settings, null, tint = PurpleHi, modifier = Modifier.size(15.dp))
                }
                Column {
                    Text("Cài đặt", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrim)
                    AnimatedVisibility(
                        visible = isDirty,
                        enter   = fadeIn(tween(150)) + expandVertically(tween(180)),
                        exit    = fadeOut(tween(100)) + shrinkVertically(tween(130)),
                    ) {
                        Text("Có thay đổi chưa lưu", color = Amber.copy(alpha = 0.85f), fontSize = 10.sp)
                    }
                }
            }
        },
        colors  = TopAppBarDefaults.topAppBarColors(containerColor = BgDark),
        actions = {
            AnimatedContent(
                targetState   = isDirty,
                transitionSpec = {
                    (fadeIn(tween(200)) + scaleIn(tween(200), 0.88f)) togetherWith
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
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(13.dp),
                                strokeWidth = 2.dp,
                                color       = Color.White,
                            )
                        } else {
                            Icon(Icons.Rounded.Check, null, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Lưu", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Main layout — vertical sidebar nav (replaces tiny pill tabs)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsLayout(
    state:               ConfigUiState,
    onSet:               (ConfigUiState.() -> ConfigUiState) -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay:       () -> Unit,
    onOpenNotification:  () -> Unit,
    modifier:            Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(Section.TIMING) }

    Row(modifier.fillMaxSize()) {

        // ── Left sidebar ─────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .width(72.dp)
                .fillMaxHeight()
                .background(BgCard)
                .border(
                    width = 0.5.dp,
                    color = BorderDark,
                    shape = RoundedCornerShape(topEnd = 0.dp, bottomEnd = 0.dp),
                )
                .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Permission badge on sidebar
            val missingPerm = !state.accessibilityGranted
            Section.entries.forEach { sec ->
                val active = selected == sec
                val bgAnim by animateColorAsState(
                    targetValue   = if (active) sec.accent.copy(alpha = 0.15f) else Color.Transparent,
                    animationSpec = tween(200), label = "nav_bg_${sec.name}",
                )
                val fgAnim by animateColorAsState(
                    targetValue   = if (active) sec.accent else TextMuted,
                    animationSpec = tween(200), label = "nav_fg_${sec.name}",
                )

                Box(
                    modifier = Modifier
                        .width(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(bgAnim)
                        .clickable { selected = sec }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Badge for permissions warning
                        Box {
                            Icon(sec.icon, null, tint = fgAnim, modifier = Modifier.size(20.dp))
                            if (sec == Section.PERMISSIONS && missingPerm) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(Red)
                                        .align(Alignment.TopEnd),
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            sec.label,
                            color      = fgAnim,
                            fontSize   = 8.5.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines   = 1,
                        )
                    }
                }

                // Active indicator line on right edge
                if (active) {
                    // Just spacing — the background highlight is enough
                }
            }
        }

        // ── Content area ──────────────────────────────────────────────────────
        AnimatedContent(
            targetState  = selected,
            transitionSpec = {
                val down = targetState.ordinal > initialState.ordinal
                (fadeIn(tween(200)) + slideInVertically(tween(240)) { if (down) it / 10 else -it / 10 }) togetherWith
                    (fadeOut(tween(150)) + slideOutVertically(tween(200)) { if (down) -it / 10 else it / 10 })
            },
            label = "section_content",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) { sec ->
            when (sec) {
                Section.TIMING        -> TimingSection(state, onSet)
                Section.ACTIONS       -> ActionsSection(state, onSet)
                Section.NOTIFICATIONS -> NotificationsSection(state, onSet)
                Section.TIKTOK        -> TikTokSection(state)
                Section.PERMISSIONS   -> PermissionsSection(state, onOpenAccessibility, onOpenOverlay, onOpenNotification)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Section: Timing
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TimingSection(state: ConfigUiState, onSet: (ConfigUiState.() -> ConfigUiState) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Thời gian farm", Icons.Rounded.Timer, Purple)

        // Farm duration card
        SettingCard {
            CardLabel("Thời lượng mỗi tài khoản", Icons.Rounded.AccountCircle, Purple)
            Spacer(Modifier.height(10.dp))
            CfgSlider(
                label    = "Phút / tài khoản",
                display  = "${state.minutesPerAccount} phút",
                value    = state.minutesPerAccount.toFloat(),
                range    = 1f..60f, steps = 58,
                accent   = Purple,
                onChanged = { onSet { copy(minutesPerAccount = it.toInt()) } },
            )
        }

        // Watch time card
        SettingCard {
            CardLabel("Thời lượng xem video", Icons.Rounded.Videocam, Cyan)
            Spacer(Modifier.height(10.dp))

            val safeMin = state.watchMin.toFloat()
            val safeMax = state.watchMax.toFloat()
            val rangeOk = safeMin < safeMax

            CfgSlider(
                label    = "Tối thiểu",
                display  = "${"%.1f".format(state.watchMin)}s",
                value    = state.watchMin.toFloat(),
                range    = if (rangeOk) 1f..safeMax else 1f..15f,
                steps    = 27,
                accent   = Cyan,
                onChanged = { v -> onSet { copy(watchMin = v.toDouble().coerceAtMost(watchMax - 0.5)) } },
            )
            Spacer(Modifier.height(8.dp))
            CfgSlider(
                label    = "Tối đa",
                display  = "${"%.1f".format(state.watchMax)}s",
                value    = state.watchMax.toFloat(),
                range    = if (rangeOk) safeMin..30f else 2f..30f,
                steps    = 55,
                accent   = Cyan,
                onChanged = { v -> onSet { copy(watchMax = v.toDouble().coerceAtLeast(watchMin + 0.5)) } },
            )

            Spacer(Modifier.height(10.dp))
            // Visual range preview
            RangePreview(
                min   = state.watchMin.toFloat(),
                max   = state.watchMax.toFloat(),
                total = 30f,
                color = Cyan,
            )
        }

        // Rest card
        SettingCard {
            CardLabel("Nghỉ giữa tài khoản", Icons.Rounded.Coffee, Amber)
            Spacer(Modifier.height(10.dp))
            CfgSwitch(
                label    = "Bật thời gian nghỉ",
                value    = state.enableRest,
                accent   = Amber,
                onChanged = { onSet { copy(enableRest = it) } },
            )
            AnimatedVisibility(
                visible = state.enableRest,
                enter   = fadeIn(tween(250)) + expandVertically(tween(280)),
                exit    = fadeOut(tween(180)) + shrinkVertically(tween(220)),
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    ThinDivider()
                    Spacer(Modifier.height(12.dp))
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

        // Recovery card
        SettingCard {
            CardLabel("Khôi phục", Icons.Rounded.Refresh, Pink)
            Spacer(Modifier.height(10.dp))
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

// ─────────────────────────────────────────────────────────────────────────────
//  Section: Actions
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ActionsSection(state: ConfigUiState, onSet: (ConfigUiState.() -> ConfigUiState) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Hành động tự động", Icons.Rounded.TouchApp, Pink)

        // Interaction rates card
        SettingCard {
            CardLabel("Tỉ lệ tương tác", Icons.Rounded.Favorite, Pink)
            Spacer(Modifier.height(10.dp))

            // Like rate with inline mini bar
            RateRow(
                icon    = Icons.Rounded.Favorite,
                label   = "Tỉ lệ thích",
                value   = state.likeRate,
                color   = Pink,
            )
            Spacer(Modifier.height(6.dp))
            CfgSlider(
                label    = "",
                display  = "${(state.likeRate * 100).toInt()}%",
                value    = state.likeRate,
                range    = 0f..1f, steps = 19,
                accent   = Pink,
                onChanged = { onSet { copy(likeRate = it) } },
            )

            Spacer(Modifier.height(12.dp))
            ThinDivider()
            Spacer(Modifier.height(12.dp))

            RateRow(
                icon    = Icons.Rounded.PersonAdd,
                label   = "Tỉ lệ theo dõi",
                value   = state.followRate,
                color   = Green,
            )
            Spacer(Modifier.height(6.dp))
            CfgSlider(
                label    = "",
                display  = "${(state.followRate * 100).toInt()}%",
                value    = state.followRate,
                range    = 0f..1f, steps = 19,
                accent   = Green,
                onChanged = { onSet { copy(followRate = it) } },
            )
        }

        // Behavior card
        SettingCard {
            CardLabel("Hành vi", Icons.Rounded.SmartToy, Purple)
            Spacer(Modifier.height(10.dp))
            CfgSwitch(
                label    = "Bỏ qua video trực tiếp",
                subtitle = "Tự động vuốt qua khi gặp livestream",
                value    = state.skipLive,
                accent   = Purple,
                onChanged = { onSet { copy(skipLive = it) } },
            )
            Spacer(Modifier.height(4.dp))
            ThinDivider()
            Spacer(Modifier.height(4.dp))
            CfgSwitch(
                label    = "Xác nhận sau khi chuyển",
                subtitle = "Vào profile kiểm tra đúng tài khoản",
                value    = state.verifyAccount,
                accent   = Purple,
                onChanged = { onSet { copy(verifyAccount = it) } },
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Section: Notifications
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NotificationsSection(state: ConfigUiState, onSet: (ConfigUiState.() -> ConfigUiState) -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Thông báo", Icons.Rounded.NotificationsNone, Cyan)

        // System notification toggle card
        SettingCard {
            CardLabel("Thông báo hệ thống", Icons.Rounded.NotificationsNone, Cyan)
            Spacer(Modifier.height(12.dp))

            // Toggle row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BgDeeper)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = "Bật thông báo",
                        color      = TextPrim,
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text     = "Nhận thông báo khi bắt đầu, kết thúc hoặc có sự cố",
                        color    = TextSec,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked         = state.enableSystemNotifications,
                    onCheckedChange = { onSet { copy(enableSystemNotifications = it) } },
                    colors          = SwitchDefaults.colors(
                        checkedThumbColor       = Color.White,
                        checkedTrackColor       = Cyan,
                        uncheckedThumbColor     = TextMuted,
                        uncheckedTrackColor     = BgCard,
                        uncheckedBorderColor    = BorderDark,
                    ),
                )
            }

            // Open notification settings button — chỉ hiện khi bật
            AnimatedVisibility(
                visible = state.enableSystemNotifications,
                enter   = fadeIn(tween(200)) + expandVertically(tween(220)),
                exit    = fadeOut(tween(150)) + shrinkVertically(tween(170)),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { com.atpro.data.AccessibilitySettingsHelper.openAppSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(12.dp),
                        border   = BorderStroke(1.dp, Cyan.copy(alpha = 0.4f)),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                    ) {
                        Icon(Icons.Rounded.NotificationsActive, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Cài đặt thông báo", fontSize = 13.sp)
                    }
                }
            }
        }

        // Status banner: cảnh báo nếu quyền thông báo chưa cấp
        if (!state.notificationGranted) {
            InfoBanner(
                text  = "Chưa cấp quyền thông báo. Bấm \"Cài đặt thông báo\" để cấp quyền.",
                color = Amber,
            )
        } else {
            InfoBanner(
                text  = "Thông báo được gửi khi bắt đầu farm, hoàn thành phiên, hoặc phát hiện checkpoint.",
                color = Cyan,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Section: TikTok
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TikTokSection(state: ConfigUiState) {
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("TikTok", Icons.Rounded.PhoneAndroid, Amber)

        // App info card
        SettingCard {
            CardLabel("Ứng dụng được cài đặt", Icons.Rounded.PhoneAndroid, Amber)
            Spacer(Modifier.height(12.dp))

            val parts     = state.tikTokVersion.split("\n")
            val hasPackage = parts.size > 1

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BgDeeper)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFF2A2A3A), Color(0xFF0D0D18)),
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("TikTok", color = TextPrim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (hasPackage) {
                        Text(parts[0], color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("v${parts[1]}", color = TextSec, fontSize = 11.sp)
                    } else {
                        Text(state.tikTokVersion, color = TextMuted, fontSize = 11.sp)
                    }
                }
                StatusBadge(
                    text    = if (hasPackage) "Đang dùng" else "Không tìm thấy",
                    color   = if (hasPackage) Green else TextMuted,
                )
            }
        }

        // Quick actions card
        SettingCard {
            CardLabel("Thao tác nhanh", Icons.Rounded.OpenInNew, Amber)
            Spacer(Modifier.height(12.dp))
            QuickAction(
                icon    = Icons.Rounded.Settings,
                label   = "Mở Settings TikTok",
                scheme  = "snssdk1233://setting",
                color   = Purple,
                onClick = { TikTokDeepLinks.openSettings(context) },
            )
            Spacer(Modifier.height(8.dp))
            QuickAction(
                icon    = Icons.Rounded.PrivacyTip,
                label   = "Mở Quyền riêng tư",
                scheme  = "snssdk1233://privacy",
                color   = Cyan,
                onClick = { TikTokDeepLinks.openPrivacySettings(context) },
            )
        }

        // Deeplink reference card
        SettingCard {
            CardLabel("Deeplink Reference", Icons.Rounded.Code, TextMuted)
            Spacer(Modifier.height(10.dp))
            listOf(
                "Settings" to "snssdk1233://setting",
                "Privacy"  to "snssdk1233://privacy",
                "Flags"    to "NEW_TASK | CLEAR_TOP",
                "Encoding" to "Base64 (anti-scan)",
            ).forEachIndexed { i, (k, v) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
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
                if (i < 3) ThinDivider()
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Section: Permissions
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PermissionsSection(
    state:               ConfigUiState,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay:       () -> Unit,
    onOpenNotification:  () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Quyền hệ thống", Icons.Rounded.AdminPanelSettings, Green)

        // Status summary banner at top
        val allOk = state.accessibilityGranted
        AnimatedContent(
            targetState   = allOk,
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
            label = "perm_banner",
        ) { ok ->
            if (ok) {
                InfoBanner("Quyền bắt buộc đã cấp — sẵn sàng farm.", Green)
            } else {
                InfoBanner("Cần cấp Accessibility Service để farm.", Red)
            }
        }

        // Permission items
        PermissionCard(
            icon        = Icons.Rounded.Accessibility,
            iconColor   = Purple,
            title       = "Accessibility Service",
            description = "Bắt buộc. Điều khiển TikTok tự động.",
            granted     = state.accessibilityGranted,
            required    = true,
            onAction    = onOpenAccessibility,
        )

        PermissionCard(
            icon        = Icons.Rounded.Layers,
            iconColor   = Amber,
            title       = "Hiển thị trên ứng dụng khác",
            description = "Hiện popup theo dõi farm khi TikTok đang chạy.",
            granted     = state.overlayGranted,
            required    = false,
            onAction    = onOpenOverlay,
        )

        PermissionCard(
            icon        = Icons.Rounded.NotificationsActive,
            iconColor   = Cyan,
            title       = "Thông báo",
            description = "Nhận thông báo khi farm xong hoặc có lỗi.",
            granted     = state.notificationGranted,
            required    = false,
            onAction    = onOpenNotification,
        )

        Spacer(Modifier.height(24.dp))
    }
}

// Each permission is now its own card instead of being rows in one shared card
@Composable
private fun PermissionCard(
    icon:        ImageVector,
    iconColor:   Color,
    title:       String,
    description: String,
    granted:     Boolean,
    required:    Boolean,
    onAction:    () -> Unit,
) {
    val borderColor by animateColorAsState(
        targetValue   = if (granted) iconColor.copy(alpha = 0.30f) else BorderDark,
        animationSpec = tween(400), label = "pc_border_$title",
    )
    val bgColor by animateColorAsState(
        targetValue   = if (granted) iconColor.copy(alpha = 0.05f) else BgCard,
        animationSpec = tween(400), label = "pc_bg_$title",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = iconColor, modifier = Modifier.size(20.dp))
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, color = TextPrim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (required) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Red.copy(alpha = 0.14f))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        Text("Bắt buộc", color = Red, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(description, color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
        }

        Spacer(Modifier.width(12.dp))

        AnimatedContent(
            targetState  = granted,
            transitionSpec = {
                (fadeIn(tween(200)) + scaleIn(tween(200))) togetherWith
                    (fadeOut(tween(150)) + scaleOut(tween(150)))
            },
            label = "pc_action_$title",
        ) { isGranted ->
            if (isGranted) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Green.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, null, tint = Green, modifier = Modifier.size(18.dp))
                }
            } else {
                OutlinedButton(
                    onClick  = onAction,
                    shape    = RoundedCornerShape(9.dp),
                    modifier = Modifier.height(34.dp),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = iconColor),
                    border   = BorderStroke(1.dp, SolidColor(iconColor.copy(alpha = 0.45f))),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text("Cấp quyền", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Shared widget: QuickAction
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun QuickAction(icon: ImageVector, label: String, scheme: String, color: Color, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue   = if (pressed) 0.97f else 1f,
        animationSpec = tween(80), label = "qa_scale",
    )
    LaunchedEffect(pressed) { if (pressed) { delay(140); pressed = false } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.06f))
            .border(1.dp, color.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
            .clickable { pressed = true; onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(scheme, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = color.copy(alpha = 0.45f), modifier = Modifier.size(17.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Reusable layout helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionTitle(text: String, icon: ImageVector, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(15.dp))
        }
        Text(text, color = TextPrim, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BgCard)
            .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        content = content,
    )
}

@Composable
private fun CardLabel(text: String, icon: ImageVector, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = accent.copy(alpha = 0.75f), modifier = Modifier.size(13.dp))
        Text(text, color = accent.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ThinDivider() {
    HorizontalDivider(color = BorderDark.copy(alpha = 0.5f), thickness = 0.5.dp)
}

@Composable
private fun InfoBanner(text: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.07f))
            .border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.Info, null, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(14.dp).padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RateRow(icon: ImageVector, label: String, value: Float, color: Color) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Text(label, color = TextSec, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            "${(value * 100).toInt()}%",
            color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        )
    }
}

/** Mini visual bar showing the watch time range relative to max 30s */
@Composable
private fun RangePreview(min: Float, max: Float, total: Float, color: Color) {
    val startFrac = (min / total).coerceIn(0f, 1f)
    val endFrac   = (max / total).coerceIn(0f, 1f)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0s", color = TextMuted, fontSize = 9.sp)
            Text("${total.toInt()}s", color = TextMuted, fontSize = 9.sp)
        }
        Spacer(Modifier.height(3.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val totalPx = maxWidth
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(BorderDark),
            )
            Box(
                modifier = Modifier
                    .offset(x = totalPx * startFrac)
                    .width(totalPx * (endFrac - startFrac))
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(listOf(color.copy(0.6f), color))
                    ),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Form controls
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
        if (label.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(label, color = TextSec, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(accent.copy(alpha = 0.12f))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                ) {
                    Text(display, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(2.dp))
        } else {
            // label hidden — still show value badge aligned right
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(accent.copy(alpha = 0.12f))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                ) {
                    Text(display, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value         = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onChanged,
                valueRange    = range,
                steps         = steps,
                modifier      = Modifier.fillMaxWidth(),
                colors        = SliderDefaults.colors(
                    thumbColor         = accent,
                    activeTrackColor   = accent,
                    inactiveTrackColor = BorderHi,
                    activeTickColor    = Color.Transparent,
                    inactiveTickColor  = Color.Transparent,
                ),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(accent)
                            .border(2.dp, accent.copy(alpha = 0.3f), CircleShape),
                    )
                },
            )
        }
    }
}

@Composable
private fun CfgSwitch(
    label:     String,
    subtitle:  String? = null,
    value:     Boolean,
    accent:    Color   = Purple,
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
    label:     String,
    value:     Int,
    range:     IntRange,
    accent:    Color = Purple,
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
            modifier = Modifier.padding(horizontal = 14.dp),
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
            .background(if (enabled) accent.copy(alpha = 0.13f) else BorderDark.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) accent else TextMuted, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun CfgTextField(
    label:     String,
    value:     String,
    hint:      String  = "",
    obscure:   Boolean = false,
    isLast:    Boolean = false,
    onChanged: (String) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = if (isLast) 0.dp else 12.dp)) {
        Text(label, color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 5.dp))
        OutlinedTextField(
            value         = value,
            onValueChange = onChanged,
            modifier      = Modifier.fillMaxWidth(),
            placeholder   = { Text(hint, color = Color(0xFF3A3A52), fontSize = 12.sp) },
            singleLine    = true,
            visualTransformation = if (obscure && !show) PasswordVisualTransformation()
                                   else VisualTransformation.None,
            trailingIcon  = if (obscure) {{ IconButton(onClick = { show = !show }) {
                Icon(
                    if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    null, tint = TextMuted, modifier = Modifier.size(17.dp),
                )
            }}} else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            shape  = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor   = BgDeeper,
                unfocusedContainerColor = BgDeeper,
                focusedBorderColor      = Purple,
                unfocusedBorderColor    = BorderDark,
                focusedTextColor        = Color.White,
                unfocusedTextColor      = Color.White,
            ),
        )
    }
}
