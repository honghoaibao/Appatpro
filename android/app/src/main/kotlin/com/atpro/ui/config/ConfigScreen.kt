package com.atpro.ui.config

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest

// ── Palette ───────────────────────────────────────────────────
private val BgDark    = Color(0xFF0D0D14)
private val CardDark  = Color(0xFF1A1A2E)
private val BorderDark= Color(0xFF374151)
private val Purple    = Color(0xFF6C63FF)
private val Green     = Color(0xFF10B981)
private val TextPrim  = Color(0xFFE5E7EB)
private val TextSec   = Color(0xFF9CA3AF)
private val TextMuted = Color(0xFF6B7280)

// ─────────────────────────────────────────────────────────────
//  Root
// ─────────────────────────────────────────────────────────────

@Composable
fun ConfigScreen(vm: ConfigViewModel) {
    val state   by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Load TikTok version on first composition
    LaunchedEffect(Unit) { vm.loadTikTokVersion(context) }

    // Show SnackBar on save
    LaunchedEffect(vm.saved) {
        vm.saved.collectLatest { snackbarHostState.showSnackbar("Đã lưu ✓") }
    }

    if (state.isLoading) {
        Box(Modifier.fillMaxSize().background(BgDark), Alignment.Center) {
            CircularProgressIndicator(color = Purple)
        }
        return
    }

    Scaffold(
        containerColor   = BgDark,
        snackbarHost     = { SnackbarHost(snackbarHostState) { data ->
            Snackbar(
                snackbarData  = data,
                containerColor = Green,
                contentColor  = Color.White,
                modifier = Modifier.padding(16.dp),
            )
        }},
        topBar = {
            ConfigTopBar(
                isDirty  = state.isDirty,
                isSaving = state.isSaving,
                onSave   = vm::save,
            )
        },
    ) { padding ->
        ConfigTabLayout(
            state   = state,
            onSet   = vm::set,
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
                Text("Cài đặt", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                if (isDirty) Text(
                    "Chưa lưu",
                    color = Purple.copy(alpha = 0.8f), fontSize = 11.sp,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark),
        actions = {
            TextButton(
                onClick  = onSave,
                enabled  = !isSaving && isDirty,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Purple,
                    )
                } else {
                    Text(
                        "Lưu",
                        color     = if (isDirty) Purple else TextMuted,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
        },
    )
}

// ─────────────────────────────────────────────────────────────
//  Tab layout
// ─────────────────────────────────────────────────────────────

@Composable
private fun ConfigTabLayout(
    state:    ConfigUiState,
    onSet:    (ConfigUiState.() -> ConfigUiState) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Thời gian", "Hành động", "Thông báo")

    Column(modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor   = BgDark,
            contentColor     = Purple,
            indicator        = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = Purple,
                )
            },
        ) {
            tabs.forEachIndexed { i, label ->
                Tab(
                    selected = selectedTab == i,
                    onClick  = { selectedTab = i },
                    text = {
                        Text(
                            label, fontSize = 13.sp,
                            color = if (selectedTab == i) Purple else TextMuted,
                        )
                    },
                )
            }
        }

        when (selectedTab) {
            0 -> TimingTab(state, onSet)
            1 -> ActionsTab(state, onSet)
            2 -> NotificationsTab(state, onSet)
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
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        CfgSlider(
            label    = "Thời gian farm mỗi tài khoản",
            display  = "${state.minutesPerAccount} phút",
            value    = state.minutesPerAccount.toFloat(),
            range    = 1f..60f, steps = 58,
            onChanged = { onSet { copy(minutesPerAccount = it.toInt()) } },
        )
        CfgDivider()
        CfgSlider(
            label    = "Xem video tối thiểu",
            display  = "${"%.1f".format(state.watchMin)}s",
            value    = state.watchMin.toFloat(),
            range    = 1f..15f, steps = 27,
            onChanged = { onSet { copy(watchMin = it.toDouble()) } },
        )
        CfgSlider(
            label    = "Xem video tối đa",
            display  = "${"%.1f".format(state.watchMax)}s",
            value    = state.watchMax.toFloat(),
            range    = 2f..30f, steps = 55,
            onChanged = { v ->
                onSet { copy(watchMax = v.toDouble().coerceAtLeast(watchMin + 1.0)) }
            },
        )
        CfgDivider()
        CfgSwitch(
            label    = "Nghỉ giữa các tài khoản",
            value    = state.enableRest,
            onChanged = { onSet { copy(enableRest = it) } },
        )
        if (state.enableRest) {
            CfgSlider(
                label    = "Thời gian nghỉ",
                display  = "${state.restMinutes} phút",
                value    = state.restMinutes.toFloat(),
                range    = 1f..30f, steps = 28,
                onChanged = { onSet { copy(restMinutes = it.toInt()) } },
            )
        }
        CfgDivider()
        CfgStepper(
            label    = "Số lần nhấn Back để khôi phục",
            value    = state.maxBackAttempts,
            range    = 1..20,
            onChanged = { onSet { copy(maxBackAttempts = it) } },
        )
        CfgDivider()
        TikTokInfoTile(state.tikTokVersion)
        Spacer(Modifier.height(40.dp))
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
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        CfgSlider(
            label    = "Tỉ lệ thích video",
            display  = "${(state.likeRate * 100).toInt()}%",
            value    = state.likeRate,
            range    = 0f..1f, steps = 19,
            onChanged = { onSet { copy(likeRate = it) } },
        )
        CfgSlider(
            label    = "Tỉ lệ theo dõi",
            display  = "${(state.followRate * 100).toInt()}%",
            value    = state.followRate,
            range    = 0f..1f, steps = 19,
            onChanged = { onSet { copy(followRate = it) } },
        )
        CfgDivider()
        CfgSwitch(
            label     = "Bỏ qua video trực tiếp",
            subtitle  = "Tự động vuốt qua khi gặp livestream",
            value     = state.skipLive,
            onChanged = { onSet { copy(skipLive = it) } },
        )
        CfgSwitch(
            label     = "Xác nhận tài khoản sau khi chuyển",
            subtitle  = "Vào profile kiểm tra đúng tài khoản",
            value     = state.verifyAccount,
            onChanged = { onSet { copy(verifyAccount = it) } },
        )
        Spacer(Modifier.height(40.dp))
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
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        CfgSectionLabel("Telegram")
        CfgTextField(
            label     = "Bot Token",
            value     = state.telegramToken,
            hint      = "123456789:ABCdef...",
            obscure   = true,
            onChanged = { onSet { copy(telegramToken = it) } },
        )
        CfgTextField(
            label     = "Chat ID",
            value     = state.telegramChatId,
            hint      = "-100...",
            onChanged = { onSet { copy(telegramChatId = it) } },
        )
        CfgDivider()
        CfgSectionLabel("Discord")
        CfgTextField(
            label     = "Webhook URL",
            value     = state.discordWebhook,
            hint      = "https://discord.com/api/webhooks/...",
            obscure   = true,
            onChanged = { onSet { copy(discordWebhook = it) } },
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(CardDark)
                .padding(12.dp),
        ) {
            Icon(
                Icons.Rounded.Info, null,
                tint = TextMuted, modifier = Modifier.size(16.dp).padding(top = 1.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Thông báo được gửi khi bắt đầu farm, hoàn thành phiên, hoặc phát hiện checkpoint.",
                color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp,
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

// ─────────────────────────────────────────────────────────────
//  Reusable config widgets
// ─────────────────────────────────────────────────────────────

@Composable
private fun CfgSectionLabel(text: String) {
    Text(
        text,
        color = Purple, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun CfgDivider() {
    HorizontalDivider(color = BorderDark.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 14.dp))
}

@Composable
private fun CfgSlider(
    label:     String,
    display:   String,
    value:     Float,
    range:     ClosedFloatingPointRange<Float>,
    steps:     Int,
    onChanged: (Float) -> Unit,
) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = TextPrim, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Purple.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(display, color = Purple, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Slider(
            value           = value.coerceIn(range.start, range.endInclusive),
            onValueChange   = onChanged,
            valueRange      = range,
            steps           = steps,
            colors          = SliderDefaults.colors(
                thumbColor          = Purple,
                activeTrackColor    = Purple,
                inactiveTrackColor  = BorderDark,
            ),
        )
    }
}

@Composable
private fun CfgSwitch(
    label:     String,
    subtitle:  String?  = null,
    value:     Boolean,
    onChanged: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrim, fontSize = 14.sp)
            if (subtitle != null) Text(
                subtitle, color = TextMuted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked         = value,
            onCheckedChange = onChanged,
            colors          = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Purple),
        )
    }
}

@Composable
private fun CfgStepper(label: String, value: Int, range: IntRange, onChanged: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextPrim, fontSize = 14.sp, modifier = Modifier.weight(1f))
        StepBtn(Icons.Rounded.Remove, value > range.first) { onChanged((value - 1).coerceAtLeast(range.first)) }
        Text(
            "$value",
            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        StepBtn(Icons.Rounded.Add, value < range.last) { onChanged((value + 1).coerceAtMost(range.last)) }
    }
}

@Composable
private fun StepBtn(
    icon:    androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Purple.copy(alpha = 0.15f) else BorderDark)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, null,
            tint     = if (enabled) Purple else TextMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun CfgTextField(
    label:     String,
    value:     String,
    hint:      String   = "",
    obscure:   Boolean  = false,
    onChanged: (String) -> Unit,
) {
    var showText by remember { mutableStateOf(false) }

    Column(Modifier.padding(bottom = 16.dp)) {
        Text(label, color = TextSec, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        OutlinedTextField(
            value         = value,
            onValueChange = onChanged,
            modifier      = Modifier.fillMaxWidth(),
            placeholder   = { Text(hint, color = Color(0xFF4B5563), fontSize = 13.sp) },
            singleLine    = true,
            visualTransformation = if (obscure && !showText) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon  = if (obscure) {{
                IconButton(onClick = { showText = !showText }) {
                    Icon(
                        if (showText) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        null, tint = TextMuted, modifier = Modifier.size(18.dp),
                    )
                }
            }} else null,
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
    }
}

@Composable
private fun TikTokInfoTile(version: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CardDark)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text("♪", color = Color.White, fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("TikTok", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(version, color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

// Helper for TabRow indicator
@Composable
private fun Modifier.tabIndicatorOffset(tabPosition: androidx.compose.material3.TabPosition): Modifier =
    this.fillMaxWidth()
        .wrapContentSize(Alignment.BottomStart)
        .offset(x = tabPosition.left)
        .width(tabPosition.width)
