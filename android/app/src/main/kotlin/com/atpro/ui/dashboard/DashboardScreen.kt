package com.atpro.ui.dashboard

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import com.atpro.ui.accounts.AccountsActivity
import com.atpro.ui.config.ConfigActivity
import com.atpro.ui.logs.LogsActivity
import com.atpro.ui.stats.StatsActivity
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atpro.automation.LiveFarmStats

// ── Design tokens — mirror Flutter dashboard_screen.dart ─────
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
    val state by vm.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        AnimatedContent(
            targetState = state.isFarming,
            transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(200))
            },
            label = "farming_state",
        ) { farming ->
            if (farming) FarmingView(state, vm)
            else         IdleView(state, vm)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Idle view
// ─────────────────────────────────────────────────────────────

@Composable
private fun IdleView(state: DashboardUiState, vm: DashboardViewModel) {
    val context = LocalContext.current

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

            // Fix 3: Settings icon — links ConfigActivity (previously missing from nav)
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
            Text(
                text  = "${state.displayCount}",
                color = Color.White,
                fontSize   = 72.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 72.sp,
            )
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

        // ── Farm mode toggle (Fix 2: dual-mode) ──
        FarmModeToggle(
            selected  = state.farmMode,
            onSelect  = vm::setFarmMode,
        )

        // ── Account list input — visible only in SELECTED_LIST mode ──
        if (state.farmMode == FarmMode.SELECTED_LIST) {
            Spacer(Modifier.height(12.dp))
            AccountListInput(
                value     = state.customAccounts,
                onChanged = vm::setCustomAccounts,
            )
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

// ─────────────────────────────────────────────────────────────
//  Farming view (unchanged)
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
            Text(
                text  = if (state.isPaused) "Đang tạm dừng" else "Đang farm",
                color = if (state.isPaused) Amber else Green,
                fontSize   = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = vm::stop,
                colors  = ButtonDefaults.textButtonColors(contentColor = RedStop),
            ) {
                Text("Dừng", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.weight(1f))

        // ── Current account ──
        CurrentAccountCard(stats)

        Spacer(Modifier.height(32.dp))

        // ── Stats grid ──
        if (stats.account.isNotEmpty()) {
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
            active   = selected == FarmMode.ALL_LOCAL,
            modifier = Modifier.weight(1f),
            onClick  = { onSelect(FarmMode.ALL_LOCAL) },
        )
        ModeTab(
            label    = "Danh sách chọn",
            active   = selected == FarmMode.SELECTED_LIST,
            modifier = Modifier.weight(1f),
            onClick  = { onSelect(FarmMode.SELECTED_LIST) },
        )
    }
}

@Composable
private fun ModeTab(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Purple else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color      = if (active) Color.White else TextMuted,
            fontSize   = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
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
//  Components (unchanged)
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
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (online) Green else TextMuted)
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
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick  = onClick,
            enabled  = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape  = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor         = Purple,
                disabledContainerColor = BorderDark,
            ),
        ) {
            Text(
                "Bắt đầu farm",
                fontSize   = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (hint != null) {
            Spacer(Modifier.height(10.dp))
            Text(hint, color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CurrentAccountCard(stats: LiveFarmStats) {
    val progress = if (stats.total > 0) stats.index.toFloat() / stats.total else 0f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "@${stats.account.ifEmpty { "..." }}",
            color      = Color.White,
            fontSize   = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            Text("${stats.index}", color = Purple, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(" / ", color = TextMuted, fontSize = 15.sp)
            Text("${stats.total}", color = TextMuted, fontSize = 15.sp)
        }
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color           = Purple,
            trackColor      = BorderDark,
        )
    }
}

@Composable
private fun StatsGrid(stats: LiveFarmStats) {
    val mins = stats.remainingSecs / 60
    val secs = stats.remainingSecs % 60
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatBox("${stats.videos}",  "Video",  Purple, Modifier.weight(1f))
        StatBox("${stats.likes}",   "Thích",  Pink,   Modifier.weight(1f))
        StatBox("${stats.follows}", "Theo",   Green,  Modifier.weight(1f))
        StatBox(
            value    = "$mins:${secs.toString().padStart(2, '0')}",
            label    = "Còn lại",
            color    = Amber,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatBox(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(vertical = 16.dp),
    ) {
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(4.dp))
        Text(label, color = TextSec, fontSize = 11.sp)
    }
}

@Composable
private fun PauseResumeButton(isPaused: Boolean, onPause: () -> Unit, onResume: () -> Unit) {
    OutlinedButton(
        onClick  = if (isPaused) onResume else onPause,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape    = RoundedCornerShape(14.dp),
        border   = ButtonDefaults.outlinedButtonBorder.copy(
            brush = SolidColor(BorderDark),
        ),
        colors   = ButtonDefaults.outlinedButtonColors(
            contentColor = if (isPaused) Green else TextSec,
        ),
    ) {
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
        ShortcutTile("👤", "Tài khoản", Modifier.weight(1f)) {
            context.startActivity(Intent(context, AccountsActivity::class.java))
        }
        ShortcutTile("📊", "Thống kê", Modifier.weight(1f)) {
            context.startActivity(Intent(context, StatsActivity::class.java))
        }
        ShortcutTile("📋", "Nhật ký", Modifier.weight(1f)) {
            context.startActivity(Intent(context, LogsActivity::class.java))
        }
    }
}

@Composable
private fun ShortcutTile(emoji: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CardDark)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Text(emoji, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(label, color = TextSec, fontSize = 11.sp)
    }
}

// Helper: SolidColor brush for border
private fun SolidColor(color: Color) = Brush.linearGradient(listOf(color, color))
