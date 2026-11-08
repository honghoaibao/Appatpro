package com.atpro.ui.config

import androidx.compose.animation.*
import com.atpro.ui.theme.AtButton
import com.atpro.ui.theme.AtButtonStyle
import com.atpro.accessibility.CountParser
import com.atpro.tasks.TaskAccountMode
import com.atpro.tasks.TaskAccountSelector
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AtRangeSliderField
import com.atpro.ui.theme.AtSliderField
import com.atpro.ui.theme.pressClickable
import com.atpro.ui.theme.staggerIn
import kotlin.math.roundToInt
import com.atpro.ui.theme.AtTopBar
import com.atpro.ui.theme.AtDialog
import com.atpro.ui.theme.AtSegmented
import com.atpro.ui.theme.AtThemeMode
import com.atpro.ui.theme.AtThemeState
import com.atpro.ui.theme.AtCard
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.atpro.R
import com.atpro.data.AccessibilitySettingsHelper
import com.atpro.ui.tasks.F4fAccountCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

// ── Palette ────────────────────────────────────────────────────────────────
private val BgDark get() = AtColors.Bg
private val BgDeeper get() = AtColors.Bg
private val BgCard get() = AtColors.Surface
private val BgCardAlt get() = AtColors.SurfaceHigh
private val BorderDark get() = AtColors.Hairline
private val BorderHi get() = AtColors.HairlineHi
private val Purple get() = AtColors.Accent
private val PurpleHi get() = AtColors.AccentHi
private val Pink get() = AtColors.Rose
private val Green get() = AtColors.Success
private val Amber get() = AtColors.Warning
private val Cyan get() = AtColors.Info
private val Red get() = AtColors.Danger
private val TextPrim get() = AtColors.TextPrim
private val TextSec get() = AtColors.TextSec
private val TextMuted get() = AtColors.TextMuted // ── Section definitions ────────────────────────────────────────────────────

/** Một mục setting con trong tab ngang */
private enum class Section(val label: String, val icon: ImageVector, private val accentOf: () -> Color) {
    PERMISSIONS  ("Quyền",      Icons.Rounded.AdminPanelSettings,   { AtColors.Accent }),
    NOTIFICATIONS("Thông báo",  Icons.Rounded.NotificationsNone,    { AtColors.Sky }),
    TIMING       ("Thời gian",  Icons.Rounded.Timer,                { AtColors.Accent }),
    ACTIONS      ("Hành động",  Icons.Rounded.TouchApp,             { AtColors.Sky }),
    TASK_EARN    ("Nhiệm vụ",  Icons.Rounded.MonetizationOn,       { AtColors.Blue }),
    // v1.11.0: cơ chế nuôi acc dùng trong lúc kiếm tiền — chỉnh riêng, không đụng cài đặt Nuôi TikTok.
    // v1.12.0: chọn tài khoản TikTok nhận nhiệm vụ (trực tiếp từ danh sách acc đã liên kết Follow4Follow).
    TASK_ACCOUNTS("Tài khoản", Icons.Rounded.ManageAccounts,       { AtColors.Blue }),
    TASK_FARM    ("Nuôi acc",  Icons.Rounded.Spa,                  { AtColors.Sky }),
    // v1.2.9: Facebook lên chính thức — tách Timing/Actions giống TikTok,
    // không còn chung nhóm "Demo nuôi acc".
    FACEBOOK_TIMING ("Thời gian", Icons.Rounded.Timer,     { Color(0xFF1877F2) }),
    FACEBOOK_ACTIONS("Hành động", Icons.Rounded.TouchApp,  { Color(0xFF1877F2) }),
    DEMO_X        ("X",         Icons.Rounded.Tag,       { AtColors.TextPrim }),
    DEMO_INSTAGRAM("Instagram", Icons.Rounded.CameraAlt, { Color(0xFFC13584) }),
    DEMO_THREADS  ("Threads",   Icons.Rounded.Forum,     { AtColors.TextPrim }),
    DEMO_SNAPCHAT ("Snapchat",  Icons.Rounded.Mood,      { Color(0xFFE6C700) });

    /** v1.7.0: màu nhấn tính khi vẽ (đổi theo chế độ Sáng/Tối), không đóng băng lúc khởi tạo enum. */
    val accent: Color get() = accentOf()
}

/**
 * Nhóm setting — mỗi nhóm có thể mở rộng / thu gọn trong sidebar.
 * Thiết kế mở rộng: thêm Section vào [sections] khi app có thêm loại
 * nuôi tài khoản hoặc kiếm tiền.
 */
private data class SettingsGroup(
    val id:       String,
    val label:    String,
    val icon:     ImageVector,
    private val accentOf: () -> Color,
    val sections: List<Section>,
) {
    val accent: Color get() = accentOf()
}

private val SETTINGS_GROUPS: List<SettingsGroup> by lazy {
    buildList {
        add(SettingsGroup(
            id = "permissions", label = "Quyền & trợ năng",
            icon = Icons.Rounded.AdminPanelSettings, accentOf = { AtColors.Accent },
            sections = listOf(Section.PERMISSIONS),
        ))
        add(SettingsGroup(
            id = "notifications", label = "Thông báo",
            icon = Icons.Rounded.NotificationsNone, accentOf = { AtColors.Sky },
            sections = listOf(Section.NOTIFICATIONS),
        ))
        add(SettingsGroup(
            id = "farm", label = "Nuôi TikTok",
            icon = Icons.Rounded.AccountCircle, accentOf = { AtColors.Sky },
            sections = listOf(Section.TIMING, Section.ACTIONS),
        ))
        // v1.2.9: Facebook lên dịch vụ chính thức — nhóm riêng giống TikTok
        add(SettingsGroup(
            id = "facebook", label = "Nuôi Facebook",
            icon = Icons.Rounded.ThumbUp, accentOf = { Color(0xFF1877F2) },
            sections = listOf(Section.FACEBOOK_TIMING, Section.FACEBOOK_ACTIONS),
        ))
        if (com.atpro.security.AppConstants.TASKS_ENABLED) {
            add(SettingsGroup(
                id = "tasks", label = "Nhiệm vụ kiếm tiền",
                icon = Icons.Rounded.MonetizationOn, accentOf = { AtColors.Blue },
                sections = listOf(Section.TASK_EARN, Section.TASK_ACCOUNTS, Section.TASK_FARM),
            ))
        }
        add(SettingsGroup(
            id = "demo", label = "Dịch vụ khác",
            icon = Icons.Rounded.Apps, accentOf = { AtColors.TextSec },
            sections = listOf(
                Section.DEMO_X, Section.DEMO_INSTAGRAM,
                Section.DEMO_THREADS, Section.DEMO_SNAPCHAT,
            ),
        ))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Root
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ConfigScreen(
    vm:             ConfigViewModel,
    /** v1.8.0: tên [Section] mở sẵn (từ tab Cài đặt) — có giá trị thì chỉ hiện các mục cùng nhóm, bỏ tầng tab nhóm. */
    initialSection: String? = null,
) {
    val state   by vm.state.collectAsStateWithLifecycle()
    val taskAccounts by vm.taskAccounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        // [v1.1.4.1 FIX] Gọi refreshPermissions khi màn hình mở lần đầu.
        // DisposableEffect ON_RESUME bên dưới chỉ bắt được lần resume tiếp theo
        // (sau khi user rời sang màn hình khác rồi quay lại). LaunchedEffect(Unit)
        // này đảm bảo permissions được đọc ngay lập tức, kể cả khi load() từ DB
        // đang chạy song song và có thể ghi đè state sau đó.
        vm.refreshPermissions(context)
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
            SettingsTopBar(
                title    = if (initialSection != null) (SETTINGS_GROUPS.firstOrNull { g -> g.sections.any { it.name == initialSection } }?.label ?: "Cài đặt") else "Cài đặt",
                isDirty  = state.isDirty, isSaving = state.isSaving, onSave = vm::save,
                onBack   = { activity?.finish() },
            )
        },
    ) { padding ->
        SettingsLayout(
            state               = state,
            onSet               = vm::set,
            onOpenAccessibility = { AccessibilitySettingsHelper.openAccessibilitySettings(context) },
            onOpenOverlay       = { AccessibilitySettingsHelper.openOverlaySettings(context) },
            onOpenNotification  = { AccessibilitySettingsHelper.openAppSettings(context) },
            taskAccounts        = taskAccounts,
            onRefreshTaskAccounts = vm::refreshTaskAccounts,
            initialSection      = initialSection,
            modifier            = Modifier.padding(padding),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Top bar
// ─────────────────────────────────────────────────────────────────────────────

/**
 * v1.9.0: dùng [AtTopBar] như mọi màn hình khác (trước đây là TopAppBar Material + ô icon bánh răng riêng).
 * Nút Lưu chỉ hiện khi có thay đổi; trạng thái "chưa lưu" là dòng phụ dưới tiêu đề.
 */
@Composable
private fun SettingsTopBar(title: String, isDirty: Boolean, isSaving: Boolean, onSave: () -> Unit, onBack: () -> Unit) {
    AtTopBar(
        title    = title,
        subtitle = if (isDirty) "Có thay đổi chưa lưu" else null,
        onBack   = onBack,
        modifier = Modifier.statusBarsPadding(),
        actions  = {
            AnimatedVisibility(
                visible = isDirty,
                enter   = fadeIn(tween(150)),
                exit    = fadeOut(tween(100)),
            ) {
                AtButton(
                    text     = if (isSaving) "Đang lưu" else "Lưu",
                    onClick  = onSave,
                    enabled  = !isSaving,
                    icon     = Icons.Rounded.Check,
                    modifier = Modifier.padding(end = 10.dp).width(100.dp),
                    height   = 38.dp,
                )
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Main layout — v1.2.6: tab NGANG ở trên (thay sidebar dọc cũ) để dành
//  toàn bộ chiều ngang cho phần nội dung/tinh chỉnh bên dưới.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsLayout(
    state:               ConfigUiState,
    onSet:               (ConfigUiState.() -> ConfigUiState) -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay:       () -> Unit,
    onOpenNotification:  () -> Unit,
    /** v1.12.0: danh sách tài khoản nhận nhiệm vụ + nút tải lại — do `ConfigScreen` (giữ ViewModel) truyền xuống. */
    taskAccounts:        TaskAccountsUi,
    onRefreshTaskAccounts: () -> Unit,
    initialSection:      String? = null,
    modifier:            Modifier = Modifier,
) {
    // Mở thẳng mục được chọn từ tab Cài đặt. v1.10.0: Quyền và Thông báo là hai màn hình RIÊNG (mỗi nhóm một mục).
    val startSection = remember { Section.values().firstOrNull { it.name == initialSection } ?: Section.PERMISSIONS }
    val group = remember { SETTINGS_GROUPS.firstOrNull { startSection in it.sections } ?: SETTINGS_GROUPS.first() }
    var selected by remember { mutableStateOf(if (startSection in group.sections) startSection else group.sections.first()) }

    Column(modifier.fillMaxSize()) {
        // Nhóm có nhiều mục (Nuôi TikTok, Nuôi Facebook, Dịch vụ khác) → bộ chọn phân đoạn; nhóm một mục → không có gì thừa.
        if (group.sections.size > 1) {
            AtSegmented(
                options       = group.sections.map { it.label },
                selectedIndex = group.sections.indexOf(selected).coerceAtLeast(0),
                onSelect      = { selected = group.sections[it] },
                modifier      = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        AnimatedContent(
            targetState = selected,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label    = "section_content",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { sec ->
            when (sec) {
                Section.TIMING         -> TimingSection(state, onSet)
                Section.ACTIONS        -> ActionsSection(state, onSet)
                Section.NOTIFICATIONS  -> NotificationsSection(state, onSet)
                Section.PERMISSIONS    -> PermissionsSection(state, onOpenAccessibility, onOpenOverlay, onOpenNotification)
                Section.TASK_EARN      -> TaskEarnSection(state, onSet)
                Section.TASK_ACCOUNTS  -> TaskAccountsSection(state, onSet, taskAccounts, onRefresh = onRefreshTaskAccounts)
                Section.TASK_FARM      -> TaskFarmSection(state, onSet)
                Section.FACEBOOK_TIMING  -> FacebookTimingSection(state, onSet)
                Section.FACEBOOK_ACTIONS -> FacebookActionsSection(state, onSet)
                Section.DEMO_X         -> XDemoSection(state, onSet)
                Section.DEMO_INSTAGRAM -> InstagramDemoSection(state, onSet)
                Section.DEMO_THREADS   -> ThreadsDemoSection(state, onSet)
                Section.DEMO_SNAPCHAT  -> SnapchatDemoSection(state, onSet)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  v1.2.6 — Logo platform thật (badge đơn giản theo màu/ký hiệu thương hiệu),
//  thay cho icon Material chung (ThumbUp/CameraAlt/MoreVert... trùng lặp & sai).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * v1.2.8 — Đồng bộ icon logo thật với ServicesScreen.
 * Facebook / X / Instagram / Snapchat dùng drawable ic_logo_* giống ServicesScreen.
 * Threads giữ "@" text (không có ic_logo_threads trong drawable).
 */
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
        }

        // Rest card
        SettingCard {
            CardLabel("Nghỉ giữa tài khoản", Icons.Rounded.Coffee, Amber)
            Spacer(Modifier.height(10.dp))
            CfgSwitch(
                label    = "Bật thời gian nghỉ",
                value    = state.enableRest,
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

@Composable
private fun ActionsSection(state: ConfigUiState, onSet: (ConfigUiState.() -> ConfigUiState) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        // Interaction rates card
        SettingCard {
            CardLabel("Tỉ lệ tương tác", Icons.Rounded.Favorite, Pink)
            Spacer(Modifier.height(10.dp))

            // v1.3.0: Bỏ khoá — luôn chỉnh tay được, không còn Experiment Mode.

            CfgSlider(
                label    = "Tỉ lệ thích",
                display  = "${(state.likeRate * 100).toInt()}%",
                value    = state.likeRate,
                range    = 0f..1f, steps = 99,
                accent   = Pink,
                onChanged = { onSet { copy(likeRate = it) } },
            )

            // v1.11.0: chỉ tim video có số tim >= ngưỡng (0 = tắt)
            Spacer(Modifier.height(8.dp))
            MinLikesSlider(
                label    = "Chỉ tim video có từ",
                value    = state.minLikesToLike,
                hint     = "Video ít tim hơn mức này sẽ không được tim, kể cả khi khớp hashtag.",
                onChange = { v -> onSet { copy(minLikesToLike = v) } },
            )

            Spacer(Modifier.height(12.dp))
            ThinDivider()
            Spacer(Modifier.height(12.dp))


            // v1.2.9: dùng range hẹp (0–5%) với step 0.1% — đủ độ chính xác để
            // đạt chuẩn 0.2% mà slider 0–100% (step 1%) trước đây không làm được.
            CfgSlider(
                label    = "Tỉ lệ theo dõi",
                display  = "${"%.1f".format(state.followRate * 100)}%",
                value    = state.followRate,
                range    = 0f..0.05f, steps = 49,
                accent   = Green,
                onChanged = { onSet { copy(followRate = it) } },
            )
        }

        // ── v1.2.9: Comment ngẫu nhiên ────────────────────────────────────
        SettingCard {
            CardLabel("Comment ngẫu nhiên", Icons.Rounded.ChatBubbleOutline, Cyan)
            Spacer(Modifier.height(10.dp))


            CfgSlider(
                label    = "Tỉ lệ comment",
                display  = "${(state.commentRate * 100).toInt()}%",
                value    = state.commentRate,
                range    = 0f..1f, steps = 99,
                accent   = Cyan,
                onChanged = { onSet { copy(commentRate = it) } },
            )

            Spacer(Modifier.height(12.dp))
            ThinDivider()
            Spacer(Modifier.height(12.dp))

            CfgMultilineTextField(
                label     = "Danh sách comment (mỗi dòng 1 câu)",
                value     = state.commentTexts.joinToString("\n"),
                hint      = "hay quá 🔥\nvideo đỉnh thế\nxem hoài không chán\n👍👍👍",
                onChanged = { raw ->
                    onSet {
                        copy(commentTexts = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() })
                    }
                },
            )

            Spacer(Modifier.height(12.dp))
            CfgSlider(
                label    = "Giới hạn comment / giờ",
                display  = if (state.maxCommentsPerHour == 0) "Không giới hạn"
                           else "${state.maxCommentsPerHour} lần",
                value    = state.maxCommentsPerHour.toFloat(),
                range    = 0f..60f, steps = 59,
                accent   = Cyan,
                onChanged = { onSet { copy(maxCommentsPerHour = it.toInt()) } },
            )
        }

        // ── v1.2.9: Xem bình luận (thụ động — chỉ mở lên xem, không gõ gì) ──
        SettingCard {
            CardLabel("Xem bình luận", Icons.Rounded.Visibility, Cyan)
            Spacer(Modifier.height(10.dp))


            CfgSlider(
                label    = "Tỉ lệ xem bình luận",
                display  = "${(state.commentViewRate * 100).toInt()}%",
                value    = state.commentViewRate,
                range    = 0f..0.5f, steps = 49,
                accent   = Cyan,
                onChanged = { onSet { copy(commentViewRate = it) } },
            )

            Spacer(Modifier.height(10.dp))
            ThinDivider()
            Spacer(Modifier.height(10.dp))

            RangeSliderPair(
                label    = "Số lần cuộn xem",
                minValue = state.commentViewScrollMin,
                maxValue = state.commentViewScrollMax,
                range    = 1..10,
                accent   = Cyan,
                summaryPrefix = "Mỗi lần xem: ",
                summarySuffix = " lần cuộn",
                onMinChanged = { onSet { copy(commentViewScrollMin = it) } },
                onMaxChanged = { onSet { copy(commentViewScrollMax = it) } },
            )
        }

        // Behavior card
        SettingCard {
            CardLabel("Hành vi", Icons.Rounded.SmartToy, Purple)
            Spacer(Modifier.height(10.dp))
            CfgSwitch(
                label    = "Bỏ qua video trực tiếp",
                subtitle = "Vuốt qua livestream",
                value    = state.skipLive,
                onChanged = { onSet { copy(skipLive = it) } },
            )
            Spacer(Modifier.height(4.dp))
            ThinDivider()
            Spacer(Modifier.height(4.dp))
            CfgSwitch(
                label    = "Bỏ qua quảng cáo",
                subtitle = "Vuốt qua quảng cáo",
                value    = state.skipAds,
                onChanged = { onSet { copy(skipAds = it) } },
            )
            Spacer(Modifier.height(4.dp))
            ThinDivider()
            Spacer(Modifier.height(4.dp))
            // [v1.1.8] Like quảng cáo — chỉ có tác dụng khi skipAds = false
            CfgSwitch(
                label    = "Like quảng cáo",
                subtitle = if (state.skipAds) "Cần tắt «Bỏ qua quảng cáo»" else "Thích quảng cáo khi dừng xem",
                value    = state.likeAds && !state.skipAds,
                onChanged = { onSet { copy(likeAds = it) } },
                enabled  = !state.skipAds,
            )
            Spacer(Modifier.height(4.dp))
            ThinDivider()
            Spacer(Modifier.height(4.dp))
            CfgSwitch(
                label    = "Xác nhận sau khi chuyển",
                subtitle = "Kiểm tra đúng tài khoản",
                value    = state.verifyAccount,
                onChanged = { onSet { copy(verifyAccount = it) } },
            )
            Spacer(Modifier.height(4.dp))
            ThinDivider()
            Spacer(Modifier.height(4.dp))
            // v1.2.6 — Toggle chuẩn hoá ID acc
            CfgSwitch(
                label    = "Chuẩn hoá ID tài khoản",
                subtitle = if (state.normalizeEnabled) "Đổi tên hiển thị thành @username" else "Bỏ qua bước chuẩn hoá",
                value    = state.normalizeEnabled,
                onChanged = { onSet { copy(normalizeEnabled = it) } },
            )
        }

        // ── [v1.1.9+] Ghé qua tab khác ──────────────────────────────────
        Spacer(Modifier.height(16.dp))
        SettingCard {
            CardLabel("Ghé qua tab khác", Icons.Rounded.Explore, Cyan)
            Spacer(Modifier.height(10.dp))

            // Hộp thư

            CfgSlider(
                label    = "Tỉ lệ ghé Hộp thư",
                display  = if (state.inboxViewRate <= 0f) "Tắt"
                           else "${(state.inboxViewRate * 100).toInt()}%",
                value    = state.inboxViewRate,
                range    = 0f..0.5f, steps = 49,
                accent   = Cyan,
                onChanged = { onSet { copy(inboxViewRate = it) } },
            )
            Spacer(Modifier.height(4.dp))
            CfgSlider(
                label    = "Thời gian xem (giây)",
                display  = "${state.inboxViewDurationSecs}s",
                value    = state.inboxViewDurationSecs.toFloat(),
                range    = 5f..60f, steps = 10,
                accent   = Cyan,
                enabled  = state.inboxViewRate > 0f,
                onChanged = { onSet { copy(inboxViewDurationSecs = it.toInt()) } },
            )

            Spacer(Modifier.height(10.dp))
            ThinDivider()
            Spacer(Modifier.height(10.dp))

            // Cửa hàng

            CfgSlider(
                label    = "Tỉ lệ ghé Cửa hàng",
                display  = if (state.shopViewRate <= 0f) "Tắt"
                           else "${(state.shopViewRate * 100).toInt()}%",
                value    = state.shopViewRate,
                range    = 0f..0.5f, steps = 49,
                accent   = Amber,
                onChanged = { onSet { copy(shopViewRate = it) } },
            )
            Spacer(Modifier.height(4.dp))
            CfgSlider(
                label    = "Số lần cuộn Cửa hàng",
                display  = "${state.shopScrollCount} lần",
                value    = state.shopScrollCount.toFloat(),
                range    = 1f..10f, steps = 8,
                accent   = Amber,
                enabled  = state.shopViewRate > 0f,
                onChanged = { onSet { copy(shopScrollCount = it.toInt()) } },
            )
        }


        // ── [v1.2.0] Tim video theo nội dung ─────────────────────────────────
        Spacer(Modifier.height(16.dp))
        SettingCard {
            CardLabel("Tim theo nội dung", Icons.Rounded.Favorite, Pink)
            Spacer(Modifier.height(10.dp))

            // Caption keywords
            CfgSwitch(
                label    = "Like theo caption",
                subtitle = "Thích khi caption có từ khoá",
                value    = state.likeByCaption,
                onChanged = { onSet { copy(likeByCaption = it) } },
            )
            Spacer(Modifier.height(6.dp))
            CfgTextField(
                label     = "Từ khoá caption (cách nhau bằng dấu phẩy)",
                value     = state.captionKeywords,
                hint      = "review, unboxing, hot — không phân biệt hoa/thường, có dấu hay không",
                onChanged = { onSet { copy(captionKeywords = it) } },
            )

            Spacer(Modifier.height(10.dp))
            ThinDivider()
            Spacer(Modifier.height(10.dp))

            // Hashtag keywords
            CfgSwitch(
                label    = "Like theo hashtag",
                subtitle = "Thích khi có hashtag",
                value    = state.likeByHashtag,
                onChanged = { onSet { copy(likeByHashtag = it) } },
            )
            Spacer(Modifier.height(6.dp))
            CfgTextField(
                label     = "Hashtag (cách nhau dấu phẩy)",
                value     = state.hashtagKeywords,
                hint      = "xuhuong, fyp, viral — không cần dấu #",
                onChanged = { onSet { copy(hashtagKeywords = it) } },
            )
        }

        // ── [v1.2.0] Tìm kiếm theo từ khoá ──────────────────────────────────
        Spacer(Modifier.height(16.dp))
        SettingCard {
            CardLabel("Tìm kiếm từ khoá", Icons.Rounded.Search, Purple)
            Spacer(Modifier.height(10.dp))

            CfgSwitch(
                label    = "Bật tìm kiếm",
                subtitle = "Thỉnh thoảng tìm theo từ khoá",
                value    = state.searchEnabled,
                onChanged = { onSet { copy(searchEnabled = it) } },
            )
            Spacer(Modifier.height(6.dp))
            CfgTextField(
                label     = "Từ khoá (cách nhau dấu phẩy)",
                value     = state.searchKeywords,
                hint      = "dance, cooking, travel, funny...",
                onChanged = { onSet { copy(searchKeywords = it) } },
            )
            Spacer(Modifier.height(4.dp))
            CfgSlider(
                label    = "Số video xem mỗi phiên search",
                display  = "${state.searchVideosPerSession} video",
                value    = state.searchVideosPerSession.toFloat(),
                range    = 1f..10f, steps = 8,
                accent   = Purple,
                enabled  = state.searchEnabled,
                onChanged = { onSet { copy(searchVideosPerSession = it.toInt()) } },
            )
            Spacer(Modifier.height(4.dp))

            CfgSlider(
                label    = "Tỉ lệ tìm kiếm",
                display  = if (state.searchRate <= 0f) "Tắt"
                           else "${(state.searchRate * 100).toInt()}%",
                value    = state.searchRate,
                range    = 0f..0.3f, steps = 29,
                accent   = Purple,
                enabled  = state.searchEnabled,
                onChanged = { onSet { copy(searchRate = it) } },
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Section: Giao diện (v1.7.0) — chuyển Sáng / Tối, áp dụng ngay
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
//  Section: Notifications
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NotificationsSection(state: ConfigUiState, onSet: (ConfigUiState.() -> ConfigUiState) -> Unit) {
    val context = LocalContext.current
    val accent = Green
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingCard(index = 0) {
            CfgSwitch(
                label     = "Thông báo hệ thống",
                subtitle  = "Bắt đầu, kết thúc, kết quả nhiệm vụ, sự cố",
                value     = state.enableSystemNotifications,
                onChanged = { onSet { copy(enableSystemNotifications = it) } },
            )
            if (state.enableSystemNotifications && !state.notificationGranted) {
                Spacer(Modifier.height(10.dp))
                AtButton(
                    text     = "Cấp quyền thông báo",
                    onClick  = { com.atpro.data.AccessibilitySettingsHelper.openAppSettings(context) },
                    style    = AtButtonStyle.Tonal,
                    height   = 44.dp,
                )
            }
            if (state.enableSystemNotifications && state.notificationGranted) {
                Spacer(Modifier.height(10.dp))
                AtButton(
                    text     = "Gửi thông báo thử",
                    onClick  = {
                        val ok = com.atpro.notification.AtProNotificationManager.sendTest(context)
                        android.widget.Toast.makeText(
                            context,
                            if (ok) "Đã gửi — xem ở thanh thông báo" else "Hệ thống đang chặn thông báo của AT PRO (kiểm tra quyền/kênh)",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                    style    = AtButtonStyle.Tonal,
                    height   = 44.dp,
                )
            }
        }

        if (state.enableSystemNotifications) {
            SettingCard(index = 1) {
                CardLabel("Loại thông báo", Icons.Rounded.Notifications, accent)
                Spacer(Modifier.height(6.dp))
                CfgSwitch(
                    label = "Cảnh báo quan trọng",
                    subtitle = "Checkpoint, tài khoản bị khoá, Follow4Follow hết phiên, đạt giới hạn ngày, lỗi",
                    value = state.notifAlerts,
                    onChanged = { onSet { copy(notifAlerts = it) } },
                )
                CfgSwitch(
                    label = "Kết quả phiên nuôi",
                    subtitle = "Bắt đầu và hoàn thành cả phiên",
                    value = state.notifSessions,
                    onChanged = { onSet { copy(notifSessions = it) } },
                )
                CfgSwitch(
                    label = "Từng tài khoản nuôi xong",
                    subtitle = "Một thông báo nhỏ, không phát âm, cho mỗi tài khoản",
                    value = state.notifAccounts,
                    onChanged = { onSet { copy(notifAccounts = it) } },
                )
                CfgSwitch(
                    label = "Nhiệm vụ kiếm tiền",
                    subtitle = "Bắt đầu, từng tài khoản (job, xu) và tổng kết có số dư",
                    value = state.notifTasks,
                    onChanged = { onSet { copy(notifTasks = it) } },
                )
            }

            SettingCard(index = 2) {
                CardLabel("Giờ yên tĩnh", Icons.Rounded.Timer, accent)
                Spacer(Modifier.height(6.dp))
                CfgSwitch(
                    label = "Không phát âm/rung trong khung giờ",
                    subtitle = "Thông báo vẫn hiện, chỉ im lặng",
                    value = state.notifQuietEnabled,
                    onChanged = { onSet { copy(notifQuietEnabled = it) } },
                )
                if (state.notifQuietEnabled) {
                    Spacer(Modifier.height(8.dp))
                    SettingSliderInt("Từ", state.notifQuietStart, 0, 23, 1, unit = ":00") { onSet { copy(notifQuietStart = it) } }
                    SettingSliderInt("Đến", state.notifQuietEnd, 0, 23, 1, unit = ":00") { onSet { copy(notifQuietEnd = it) } }
                }
            }
        }

        SettingCard(index = 3) {
            CardLabel("Thông báo Discord", Icons.Rounded.Notifications, Green)
            Spacer(Modifier.height(10.dp))
            CfgTextField(
                label     = "Webhook URL",
                value     = state.discordWebhookUrl,
                hint      = "https://discord.com/api/webhooks/...",
                isLast    = true,
                onChanged = { onSet { copy(discordWebhookUrl = it) } },
            )
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PermissionCard(
            icon      = Icons.Rounded.Accessibility,
            iconColor = Purple,
            title     = "Trợ năng",
            hint      = "Bắt buộc",
            granted   = state.accessibilityGranted,
            required  = true,
            onAction  = onOpenAccessibility,
        )
        PermissionCard(
            icon      = Icons.Rounded.Layers,
            iconColor = Amber,
            title     = "Hiển thị trên ứng dụng khác",
            hint      = "Popup theo dõi",
            granted   = state.overlayGranted,
            required  = false,
            onAction  = onOpenOverlay,
        )
        PermissionCard(
            icon      = Icons.Rounded.NotificationsActive,
            iconColor = Cyan,
            title     = "Thông báo",
            hint      = "Báo khi xong hoặc lỗi",
            granted   = state.notificationGranted,
            required  = false,
            onAction  = onOpenNotification,
        )
        Spacer(Modifier.height(24.dp))
    }
}

// Each permission is now its own card instead of being rows in one shared card
/**
 * v1.10.0 — bố cục DỌC (hàng trên: biểu tượng + tên + dấu ✓; nút "Cấp quyền" nằm dưới, full chiều ngang).
 * Bản cũ để nút trong một ô cố định 34dp ngay trong hàng: máy có cỡ chữ lớn / màn hình hẹp thì chữ có dấu bị cắt và
 * tên quyền bị ép ra ngoài thẻ ("vỡ UI, phông chữ" khi chưa cấp quyền). Mọi kích thước dùng min/wrap, không cố định cao.
 */
@Composable
private fun PermissionCard(
    icon:      ImageVector,
    iconColor: Color,
    title:     String,
    hint:      String,
    granted:   Boolean,
    required:  Boolean,
    onAction:  () -> Unit,
) {
    val borderColor by animateColorAsState(
        targetValue   = if (granted) iconColor.copy(alpha = 0.30f) else BorderDark,
        animationSpec = tween(400), label = "pc_border",
    )
    val bgColor by animateColorAsState(
        targetValue   = if (granted) iconColor.copy(alpha = 0.05f) else BgCard,
        animationSpec = tween(400), label = "pc_bg",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title, color = TextPrim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    hint,
                    color    = if (required && !granted) Red else TextMuted,
                    fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (granted) {
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(Green.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, null, tint = Green, modifier = Modifier.size(18.dp))
                }
            }
        }
        AnimatedVisibility(visible = !granted) {
            Column {
                Spacer(Modifier.height(12.dp))
                AtButton(
                    text     = "Cấp quyền",
                    onClick  = onAction,
                    style    = if (required) AtButtonStyle.Primary else AtButtonStyle.Tonal,
                    height   = 44.dp,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Reusable layout helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingCard(index: Int = 0, content: @Composable ColumnScope.() -> Unit) {
    // v1.7.0: dùng AtCard chung (viền mảnh + bóng xanh nhẹ ở chế độ Sáng).
    // v1.11.0: thẻ hiện dần lần lượt theo [index] khi mở mục.
    AtCard(
        modifier       = Modifier.fillMaxWidth().staggerIn(index, stepMs = 55),
        contentPadding = PaddingValues(15.dp),
        content        = content,
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

// ─────────────────────────────────────────────────────────────────────────────
//  Form controls
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
/** v1.11.0: dựng trên [AtSliderField] (đường gradient, núm có quầng sáng, rung theo nấc). `accent` giữ lại cho tương thích. */
@Composable
@Suppress("UNUSED_PARAMETER")
private fun CfgSlider(
    label:    String,
    display:  String,
    value:    Float,
    range:    ClosedFloatingPointRange<Float>,
    steps:    Int,
    accent:   Color = Purple,
    enabled:  Boolean = true,
    onChanged: (Float) -> Unit,
) {
    AtSliderField(
        label         = label,
        display       = display,
        value         = value,
        onValueChange = onChanged,
        valueRange    = range,
        steps         = steps,
        enabled       = enabled,
        modifier      = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun CfgSwitch(
    label:     String,
    subtitle:  String? = null,
    value:     Boolean,
    enabled:   Boolean = true,
    onChanged: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(if (!enabled) Modifier.alpha(0.4f) else Modifier),
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
            enabled         = enabled,
            colors          = SwitchDefaults.colors(
                        checkedThumbColor    = Color.White,
                        checkedTrackColor    = AtColors.Blue,
                        checkedBorderColor   = Color.Transparent,
                        uncheckedThumbColor  = Color.White,
                        uncheckedTrackColor  = AtColors.HairlineHi,
                        uncheckedBorderColor = Color.Transparent,
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
            color = AtColors.TextPrim, fontSize = 16.sp, fontWeight = FontWeight.Bold,
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
            placeholder   = { Text(hint, color = AtColors.TextMuted, fontSize = 12.sp) },
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
                focusedTextColor        = AtColors.TextPrim,
                unfocusedTextColor      = AtColors.TextPrim,
            ),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * v1.2.9: TextField nhiều dòng — dùng cho danh sách comment ngẫu nhiên.
 * Mỗi dòng = 1 câu comment riêng (không split theo dấu phẩy như CfgTextField,
 * vì câu comment có thể chứa dấu phẩy tự nhiên).
 */
@Composable
private fun CfgMultilineTextField(
    label:     String,
    value:     String,
    hint:      String = "",
    minLines:  Int    = 3,
    maxLines:  Int    = 6,
    onChanged: (String) -> Unit,
) {
    Column(Modifier.padding(bottom = 0.dp)) {
        Text(label, color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 5.dp))
        OutlinedTextField(
            value         = value,
            onValueChange = onChanged,
            modifier      = Modifier.fillMaxWidth(),
            placeholder   = { Text(hint, color = AtColors.TextMuted, fontSize = 12.sp) },
            singleLine    = false,
            minLines      = minLines,
            maxLines      = maxLines,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            shape  = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor   = BgDeeper,
                unfocusedContainerColor = BgDeeper,
                focusedBorderColor      = Purple,
                unfocusedBorderColor    = BorderDark,
                focusedTextColor        = AtColors.TextPrim,
                unfocusedTextColor      = AtColors.TextPrim,
            ),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Cài đặt nhiệm vụ kiếm tiền TikTok  [v1.2.9 → v1.4.0: bỏ Golike]
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TaskEarnSection(
    state: ConfigUiState,
    onSet: (ConfigUiState.() -> ConfigUiState) -> Unit,
) {
    val accent = AtColors.Blue
    val jobTypes = listOf("LIKE", "FOLLOW", "BOTH")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        F4fAccountCard()

        SettingCard(index = 1) {
            CardLabel("Loại job", Icons.Rounded.WorkOutline, accent)
            Spacer(Modifier.height(10.dp))
            AtSegmented(
                options       = listOf("Tim", "Follow", "Cả hai"),
                selectedIndex = jobTypes.indexOf(state.taskJobType).coerceAtLeast(0),
                onSelect      = { i -> onSet { copy(taskJobType = jobTypes[i]) } },
            )
        }

        SettingCard(index = 2) {
            CardLabel("Thời gian", Icons.Rounded.Timer, accent)
            Spacer(Modifier.height(8.dp))
            SettingSliderInt("Nuôi trước job", state.taskFarmBeforeJobSecs, 10, 300, 10, unit = " giây",
                hint = "Xem feed trước khi nhận job đầu tiên của mỗi tài khoản") {
                onSet { copy(taskFarmBeforeJobSecs = it) }
            }
            SettingSliderInt("Chờ sau khi mở link", state.taskJobDelaySecs, 2, 15, 1, unit = " giây") {
                onSet { copy(taskJobDelaySecs = it) }
            }
        }

        SettingCard(index = 3) {
            CardLabel("Giới hạn", Icons.Rounded.ErrorOutline, accent)
            Spacer(Modifier.height(8.dp))
            SettingSliderInt("Job mỗi tài khoản", state.taskJobsPerAccount, 1, 150, 1) {
                onSet { copy(taskJobsPerAccount = it) }
            }
            SettingSliderInt("Lỗi liên tiếp tối đa", state.taskMaxConsecFailures, 1, 10, 1,
                hint = "Quá số lần này tài khoản sẽ được bỏ qua") {
                onSet { copy(taskMaxConsecFailures = it) }
            }
        }

        SettingCard(index = 4) {
            CardLabel("Nuôi giữa các job", Icons.Rounded.Spa, accent)
            Spacer(Modifier.height(10.dp))
            RangeSliderPair(
                label    = "Nuôi ngắn sau mỗi job",
                minValue = state.taskFarmAfterJobMinSecs,
                maxValue = state.taskFarmAfterJobMaxSecs,
                range    = 3..60,
                accent   = accent,
                summaryPrefix = "",
                summarySuffix = " giây",
                onMinChanged = { onSet { copy(taskFarmAfterJobMinSecs = it) } },
                onMaxChanged = { onSet { copy(taskFarmAfterJobMaxSecs = it) } },
            )
            Spacer(Modifier.height(8.dp))
            SettingSliderInt("Nuôi lâu mỗi N job", state.taskLongFarmEveryNJobs, 0, 50, 1,
                zeroLabel = "Tắt", unit = " job") {
                onSet { copy(taskLongFarmEveryNJobs = it) }
            }
            SettingSliderInt("Thời lượng nuôi lâu", state.taskLongFarmDurationSecs, 30, 900, 30, unit = " giây") {
                onSet { copy(taskLongFarmDurationSecs = it) }
            }
            Text(
                "Cách xem video và tim khi nuôi: tab Nuôi acc.",
                color = AtColors.TextMuted, fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Tài khoản nhận nhiệm vụ  [v1.12.0]
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Chọn tài khoản TikTok nhận nhiệm vụ — chọn thẳng từ danh sách acc đã liên kết Follow4Follow (không còn gõ tay @username).
 *  • Tự động: mọi acc đã liên kết VÀ có trên máy.
 *  • Chọn tay: chỉ những acc được tick (vẫn phải liên kết + có trên máy).
 * Luật chọn nằm ở `TaskAccountSelector` (test được); acc "chưa có trên máy" sẽ không được làm cho tới khi AT PRO quét thấy nó.
 */
@Composable
private fun TaskAccountsSection(
    state:     ConfigUiState,
    onSet:     (ConfigUiState.() -> ConfigUiState) -> Unit,
    ui:        TaskAccountsUi,
    onRefresh: () -> Unit,
) {
    val accent = AtColors.Blue
    val manual = TaskAccountMode.parse(state.taskAccountMode) == TaskAccountMode.SELECTED
    val selectedKeys = remember(state.taskSelectedAccounts) { TaskAccountSelector.parseList(state.taskSelectedAccounts).toSet() }

    LaunchedEffect(Unit) { if (!ui.loaded) onRefresh() }

    fun setSelected(keys: Collection<String>) = onSet { copy(taskSelectedAccounts = keys.distinct().joinToString(",")) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingCard(index = 0) {
            CardLabel("Cách chọn", Icons.Rounded.ManageAccounts, accent)
            Spacer(Modifier.height(10.dp))
            AtSegmented(
                options       = listOf("Tự động", "Chọn tay"),
                selectedIndex = if (manual) 1 else 0,
                onSelect      = { i -> onSet { copy(taskAccountMode = if (i == 1) "SELECTED" else "AUTO") } },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (manual) "Chỉ làm nhiệm vụ với các tài khoản bạn tick bên dưới."
                else "Làm nhiệm vụ với mọi tài khoản đã liên kết Follow4Follow và có trên máy.",
                color = AtColors.TextSec, fontSize = 12.sp,
            )
        }

        SettingCard(index = 1) {
            CardLabel("Tài khoản Follow4Follow", Icons.Rounded.Tag, accent)
            Spacer(Modifier.height(8.dp))
            when {
                ui.loading && ui.rows.isEmpty() ->
                    Text("Đang tải danh sách…", color = AtColors.TextMuted, fontSize = 12.sp)
                ui.needLogin ->
                    Text("Chưa đăng nhập Follow4Follow. Vào tab Dịch vụ để đăng nhập, rồi quay lại tải danh sách.", color = Amber, fontSize = 12.sp)
                ui.error != null ->
                    Text("Không tải được danh sách: ${ui.error}", color = Red, fontSize = 12.sp)
                ui.loaded && ui.rows.isEmpty() ->
                    Text("Tài khoản Follow4Follow chưa có acc TikTok nào. Hãy thêm acc trên follow4follow.net.", color = AtColors.TextMuted, fontSize = 12.sp)
                else -> {
                    val willDo = ui.rows.count { it.onDevice && (!manual || TaskAccountSelector.key(it.username) in selectedKeys) }
                    Text(
                        "Liên kết: ${ui.rows.size} · Có trên máy: ${ui.rows.count { it.onDevice }} · Sẽ làm: $willDo",
                        color = AtColors.TextSec, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    ui.rows.forEach { row ->
                        val key = TaskAccountSelector.key(row.username)
                        TaskAccountItem(
                            name       = row.username,
                            onDevice   = row.onDevice,
                            checked    = if (manual) key in selectedKeys else row.onDevice,
                            selectable = manual,
                            onToggle   = { on -> setSelected(if (on) selectedKeys + key else selectedKeys - key) },
                        )
                    }
                    if (manual) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            AtButton(
                                text = "Chọn hết acc có trên máy",
                                onClick = { setSelected(ui.rows.filter { it.onDevice }.map { TaskAccountSelector.key(it.username) }) },
                                style = AtButtonStyle.Tonal, height = 40.dp, modifier = Modifier.weight(1f),
                            )
                            AtButton(
                                text = "Bỏ chọn hết",
                                onClick = { setSelected(emptyList()) },
                                style = AtButtonStyle.Tonal, height = 40.dp, modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            AtButton(
                text    = if (ui.loading) "Đang tải…" else "Tải lại danh sách",
                onClick = onRefresh,
                style   = AtButtonStyle.Tonal,
                height  = 42.dp,
                enabled = !ui.loading,
            )
            if (ui.loaded && ui.deviceCount == 0 && !ui.needLogin) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Chưa có tài khoản nào được quét trên máy. Chạy Nuôi TikTok một lần (hoặc quét tài khoản ở tab Tài khoản) để AT PRO biết các acc trên máy.",
                    color = AtColors.TextMuted, fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** Một dòng tài khoản: tick (khi chọn tay) hoặc chấm trạng thái (khi tự động) + chip "Có trên máy / Chưa có trên máy". */
@Composable
private fun TaskAccountItem(name: String, onDevice: Boolean, checked: Boolean, selectable: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .then(if (selectable) Modifier.pressClickable { onToggle(!checked) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (checked) AtColors.Blue else AtColors.TextMuted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text("@$name", color = AtColors.TextPrim, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            if (onDevice) "Có trên máy" else "Chưa có trên máy",
            color = if (onDevice) Green else Amber, fontSize = 11.sp,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Nuôi acc trong lúc kiếm tiền  [v1.11.0]
// ─────────────────────────────────────────────────────────────────────────────

/** Ngưỡng số tim chọn được (chỉ tim video có ≥ ngưỡng); 0 = tắt. */
private val MIN_LIKE_STEPS = intArrayOf(0, 100, 500, 1_000, 5_000, 10_000, 50_000, 100_000, 500_000, 1_000_000)

/** Thanh kéo chọn ngưỡng số tim theo các mốc cố định (kéo theo chỉ số mốc, không kéo theo số). */
@Composable
private fun MinLikesSlider(label: String, value: Int, hint: String?, onChange: (Int) -> Unit) {
    val idx = MIN_LIKE_STEPS.indexOfLast { it <= value }.coerceAtLeast(0)
    AtSliderField(
        label         = label,
        display       = if (MIN_LIKE_STEPS[idx] <= 0) "Tắt" else "≥ ${CountParser.format(MIN_LIKE_STEPS[idx].toLong())} tim",
        value         = idx.toFloat(),
        onValueChange = { v -> onChange(MIN_LIKE_STEPS[v.roundToInt().coerceIn(0, MIN_LIKE_STEPS.lastIndex)]) },
        valueRange    = 0f..MIN_LIKE_STEPS.lastIndex.toFloat(),
        steps         = MIN_LIKE_STEPS.size - 2,
        hint          = hint,
        endLabels     = "Tắt" to "1Tr",
        modifier      = Modifier.padding(bottom = 4.dp),
    )
}

/**
 * Cơ chế nuôi acc dùng TRƯỚC/SAU mỗi job (xem video, tỉ lệ tim, ngưỡng số tim, tim theo hashtag/chú thích).
 * Bộ thiết lập riêng — không ảnh hưởng chế độ Nuôi TikTok. Job Tim luôn tim đúng video được giao,
 * không bị ngưỡng số tim chặn.
 */
@Composable
private fun TaskFarmSection(
    state: ConfigUiState,
    onSet: (ConfigUiState.() -> ConfigUiState) -> Unit,
) {
    val accent = AtColors.Sky

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Áp dụng cho lúc nuôi trước và sau mỗi job. Chế độ Nuôi TikTok có cài đặt riêng.",
            color = AtColors.TextSec, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 4.dp).staggerIn(0),
        )

        SettingCard(index = 1) {
            CardLabel("Xem video", Icons.Rounded.Timer, accent)
            Spacer(Modifier.height(10.dp))
            RangeSliderPair(
                label         = "Thời gian xem mỗi video",
                minValue      = state.taskFarmWatchMinSecs,
                maxValue      = state.taskFarmWatchMaxSecs,
                range         = 1..60,
                accent        = accent,
                summaryPrefix = "",
                summarySuffix = " giây",
                minGap        = 1,
                onMinChanged  = { onSet { copy(taskFarmWatchMinSecs = it) } },
                onMaxChanged  = { onSet { copy(taskFarmWatchMaxSecs = it) } },
            )
        }

        SettingCard(index = 2) {
            CardLabel("Tim video", Icons.Rounded.Favorite, accent)
            Spacer(Modifier.height(10.dp))
            CfgSlider(
                label     = "Tỉ lệ tim",
                display   = "${(state.taskFarmLikeRate * 100).roundToInt()}%",
                value     = state.taskFarmLikeRate,
                range     = 0f..1f,
                steps     = 99,
                accent    = accent,
                onChanged = { v -> onSet { copy(taskFarmLikeRate = v) } },
            )
            Spacer(Modifier.height(8.dp))
            MinLikesSlider(
                label    = "Chỉ tim video có từ",
                value    = state.taskFarmMinLikes,
                hint     = "Video ít tim hơn mức này sẽ không được tim. Không áp dụng cho job Tim.",
                onChange = { v -> onSet { copy(taskFarmMinLikes = v) } },
            )
        }

        SettingCard(index = 3) {
            CardLabel("Tim theo nội dung", Icons.Rounded.Tag, accent)
            Spacer(Modifier.height(10.dp))
            CfgSwitch(
                label     = "Tim theo hashtag",
                subtitle  = "Video có hashtag khớp sẽ được tim (vẫn qua ngưỡng số tim)",
                value     = state.taskFarmLikeByHashtag,
                onChanged = { onSet { copy(taskFarmLikeByHashtag = it) } },
            )
            Spacer(Modifier.height(6.dp))
            CfgTextField(
                label     = "Hashtag (cách nhau bằng dấu phẩy)",
                value     = state.taskFarmHashtags,
                hint      = "xuhuong, fyp, viral — không cần dấu #",
                onChanged = { onSet { copy(taskFarmHashtags = it) } },
            )
            Spacer(Modifier.height(10.dp))
            ThinDivider()
            Spacer(Modifier.height(10.dp))
            CfgSwitch(
                label     = "Tim theo chú thích",
                subtitle  = "Video có từ khoá trong chú thích sẽ được tim",
                value     = state.taskFarmLikeByCaption,
                onChanged = { onSet { copy(taskFarmLikeByCaption = it) } },
            )
            Spacer(Modifier.height(6.dp))
            CfgTextField(
                label     = "Từ khoá chú thích (cách nhau bằng dấu phẩy)",
                value     = state.taskFarmCaptionKeywords,
                hint      = "review, unboxing, hot",
                isLast    = true,
                onChanged = { onSet { copy(taskFarmCaptionKeywords = it) } },
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Nuôi Facebook và các dịch vụ khác (X · Instagram · Threads · Snapchat)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FacebookTimingSection(state: ConfigUiState, onSet: (ConfigUiState.() -> ConfigUiState) -> Unit) {
    val FbBlue = Color(0xFF1877F2)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingCard {
            CardLabel("Thời lượng nuôi mỗi phiên", Icons.Rounded.Schedule, FbBlue)
            Spacer(Modifier.height(8.dp))
            SettingSliderInt("Thời gian nuôi (giây)", state.facebookNurtureDurationSecs, 60, 3600, 60) {
                onSet { copy(facebookNurtureDurationSecs = it) }
            }
        }

        SettingCard {
            CardLabel("Thời gian đọc mỗi bài", Icons.Rounded.MenuBook, FbBlue)
            Spacer(Modifier.height(10.dp))
            RangeSliderPair(
                label    = "Khoảng đọc bài (giây)",
                minValue = state.facebookReadTimeMinSecs,
                maxValue = state.facebookReadTimeMaxSecs,
                range    = 3..60,
                accent   = FbBlue,
                onMinChanged = { onSet { copy(facebookReadTimeMinSecs = it) } },
                onMaxChanged = { onSet { copy(facebookReadTimeMaxSecs = it) } },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FacebookActionsSection(state: ConfigUiState, onSet: (ConfigUiState.() -> ConfigUiState) -> Unit) {
    val FbBlue = Color(0xFF1877F2)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingCard {
            CardLabel("Tỉ lệ thích bài đăng", Icons.Rounded.ThumbUp, FbBlue)
            Spacer(Modifier.height(10.dp))
            CfgSlider(
                label     = "",
                display   = "${(state.facebookLikeRate * 100).toInt()}%",
                value     = state.facebookLikeRate,
                range     = 0f..0.8f,
                steps     = 79,
                accent    = FbBlue,
                onChanged = { v -> onSet { copy(facebookLikeRate = v) } },
            )
        }

        SettingCard {
            CardLabel("Tỉ lệ xem bình luận", Icons.Rounded.Visibility, FbBlue)
            Spacer(Modifier.height(10.dp))
            CfgSlider(
                label     = "",
                display   = "${(state.facebookCommentViewRate * 100).toInt()}%",
                value     = state.facebookCommentViewRate,
                range     = 0f..0.5f,
                steps     = 49,
                accent    = FbBlue,
                onChanged = { v -> onSet { copy(facebookCommentViewRate = v) } },
            )
        }

        SettingCard {
            CardLabel("Xem Reels", Icons.Rounded.SmartDisplay, FbBlue)
            Spacer(Modifier.height(10.dp))
            CfgSlider(
                label     = "Tỉ lệ ghé Reels",
                display   = if (state.facebookReelsViewRate <= 0f) "Tắt"
                            else "${(state.facebookReelsViewRate * 100).toInt()}%",
                value     = state.facebookReelsViewRate,
                range     = 0f..0.5f,
                steps     = 49,
                accent    = FbBlue,
                onChanged = { v -> onSet { copy(facebookReelsViewRate = v) } },
            )
            Spacer(Modifier.height(10.dp))
            RangeSliderPair(
                label    = "Thời gian xem Reels (giây)",
                minValue = state.facebookReelsViewDurationMinSecs,
                maxValue = state.facebookReelsViewDurationMaxSecs,
                range    = 30..1800,
                accent   = FbBlue,
                summarySuffix = "s",
                onMinChanged = { onSet { copy(facebookReelsViewDurationMinSecs = it) } },
                onMaxChanged = { onSet { copy(facebookReelsViewDurationMaxSecs = it) } },
            )
            Spacer(Modifier.height(10.dp))
            CfgSlider(
                label     = "Tỉ lệ đúp màn hình thích Reel",
                display   = "${(state.facebookReelsLikeRate * 100).toInt()}%",
                value     = state.facebookReelsLikeRate,
                range     = 0f..0.8f,
                steps     = 79,
                accent    = FbBlue,
                enabled   = state.facebookReelsViewRate > 0f,
                onChanged = { v -> onSet { copy(facebookReelsLikeRate = v) } },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun XDemoSection(state: ConfigUiState, onSet: (ConfigUiState.() -> ConfigUiState) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingSliderInt("Thời gian nuôi (giây)", state.xNurtureDurationSecs, 60, 3600, 60) {
            onSet { copy(xNurtureDurationSecs = it) }
        }
        CfgSlider(
            label     = "Xác suất like tweet",
            display   = "${(state.xLikeRate * 100).toInt()}%",
            value     = state.xLikeRate,
            range     = 0f..0.8f,
            steps     = 79,
            onChanged = { v -> onSet { copy(xLikeRate = v) } },
        )
        CfgSlider(
            label     = "Xác suất repost tweet",
            display   = "${(state.xRetweetRate * 100).toInt()}%",
            value     = state.xRetweetRate,
            range     = 0f..0.3f,
            steps     = 29,
            onChanged = { v -> onSet { copy(xRetweetRate = v) } },
        )
        CfgSlider(
            label     = "Xác suất xem reply",
            display   = "${(state.xReplyViewRate * 100).toInt()}%",
            value     = state.xReplyViewRate,
            range     = 0f..0.5f,
            steps     = 49,
            onChanged = { v -> onSet { copy(xReplyViewRate = v) } },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InstagramDemoSection(state: ConfigUiState, onSet: (ConfigUiState.() -> ConfigUiState) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingSliderInt("Thời gian nuôi (giây)", state.instagramNurtureDurationSecs, 60, 3600, 60) {
            onSet { copy(instagramNurtureDurationSecs = it) }
        }
        CfgSlider(
            label     = "Xác suất like bài / reel",
            display   = "${(state.instagramLikeRate * 100).toInt()}%",
            value     = state.instagramLikeRate,
            range     = 0f..0.8f,
            steps     = 79,
            onChanged = { v -> onSet { copy(instagramLikeRate = v) } },
        )
        CfgSlider(
            label     = "Xác suất follow tác giả",
            display   = "${(state.instagramFollowRate * 100).toInt()}%",
            value     = state.instagramFollowRate,
            range     = 0f..0.3f,
            steps     = 29,
            onChanged = { v -> onSet { copy(instagramFollowRate = v) } },
        )
        CfgSlider(
            label     = "Xác suất xem bình luận",
            display   = "${(state.instagramCommentViewRate * 100).toInt()}%",
            value     = state.instagramCommentViewRate,
            range     = 0f..0.5f,
            steps     = 49,
            onChanged = { v -> onSet { copy(instagramCommentViewRate = v) } },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ThreadsDemoSection(state: ConfigUiState, onSet: (ConfigUiState.() -> ConfigUiState) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingSliderInt("Thời gian nuôi (giây)", state.threadsNurtureDurationSecs, 60, 3600, 60) {
            onSet { copy(threadsNurtureDurationSecs = it) }
        }
        CfgSlider(
            label     = "Xác suất like bài viết",
            display   = "${(state.threadsLikeRate * 100).toInt()}%",
            value     = state.threadsLikeRate,
            range     = 0f..0.8f,
            steps     = 79,
            onChanged = { v -> onSet { copy(threadsLikeRate = v) } },
        )
        CfgSlider(
            label     = "Xác suất xem reply",
            display   = "${(state.threadsReplyViewRate * 100).toInt()}%",
            value     = state.threadsReplyViewRate,
            range     = 0f..0.5f,
            steps     = 49,
            onChanged = { v -> onSet { copy(threadsReplyViewRate = v) } },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SnapchatDemoSection(state: ConfigUiState, onSet: (ConfigUiState.() -> ConfigUiState) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingSliderInt("Thời gian nuôi (giây)", state.snapchatNurtureDurationSecs, 60, 3600, 60) {
            onSet { copy(snapchatNurtureDurationSecs = it) }
        }
        SettingSliderInt("Thời gian xem mỗi Story (giây)", state.snapchatStoryViewSecs, 3, 30, 1) {
            onSet { copy(snapchatStoryViewSecs = it) }
        }
        CfgSlider(
            label     = "Xác suất thích Story/Spotlight",
            display   = "${(state.snapchatLikeRate * 100).toInt()}%",
            value     = state.snapchatLikeRate,
            range     = 0f..0.8f,
            steps     = 79,
            onChanged = { v -> onSet { copy(snapchatLikeRate = v) } },
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * v1.2.9: Khoảng min–max dùng chung 1 nhãn — ví dụ khoảng thời gian đọc bài Facebook.
 * v1.11.0: một thanh kéo 2 núm ([AtRangeSliderField]) thay cho 2 thanh riêng; luôn giữ min ≤ max (cách nhau ≥ [minGap]).
 */
@Composable
@Suppress("UNUSED_PARAMETER")
private fun RangeSliderPair(
    label:          String,
    minValue:       Int,
    maxValue:       Int,
    range:          IntRange,
    accent:         Color  = Purple,
    summaryPrefix:  String = "Mỗi bài: ",
    summarySuffix:  String = "s",
    minGap:         Int    = 0,
    onMinChanged:   (Int) -> Unit,
    onMaxChanged:   (Int) -> Unit,
) {
    val lo = minValue.coerceIn(range.first, range.last)
    val hi = maxValue.coerceIn(lo, range.last)
    AtRangeSliderField(
        label         = label,
        display       = "$summaryPrefix$lo–$hi$summarySuffix",
        start         = lo.toFloat(),
        end           = hi.toFloat(),
        onRangeChange = { s, e ->
            val ns = s.roundToInt(); val ne = e.roundToInt()
            if (ns != minValue) onMinChanged(ns)
            if (ne != maxValue) onMaxChanged(ne)
        },
        valueRange    = range.first.toFloat()..range.last.toFloat(),
        steps         = (range.last - range.first - 1).coerceAtLeast(0),
        minGap        = minGap.toFloat(),
        modifier      = Modifier.padding(bottom = 6.dp),
    )
}

// v1.3.0: Đã xoá LockedValueBadge / ExperimentModeBanner — không còn setting nào
// bị khoá nữa, mọi slider nuôi acc luôn hiện chỉnh tay được.

// Thanh kéo số nguyên — v1.11.0: dựng trên [AtSliderField]; giá trị luôn làm tròn về bước [step].
@Composable
private fun SettingSliderInt(
    label:    String,
    value:    Int,
    min:      Int,
    max:      Int,
    step:     Int,
    unit:     String = "",
    hint:     String? = null,
    zeroLabel: String? = null,
    onChange: (Int) -> Unit,
) {
    val intervals = ((max - min) / step).coerceAtLeast(1)
    AtSliderField(
        label         = label,
        display       = if (value == 0 && zeroLabel != null) zeroLabel else "$value$unit",
        value         = value.toFloat(),
        onValueChange = { raw -> onChange((min + Math.round((raw - min) / step) * step).coerceIn(min, max)) },
        valueRange    = min.toFloat()..max.toFloat(),
        steps         = intervals - 1,
        hint          = hint,
        modifier      = Modifier.padding(bottom = 12.dp),
    )
}
