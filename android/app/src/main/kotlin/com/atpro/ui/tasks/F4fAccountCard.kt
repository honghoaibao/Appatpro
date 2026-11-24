package com.atpro.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atpro.tasks.TaskResult
import com.atpro.tasks.f4f.F4fAuth
import com.atpro.tasks.f4f.F4fAuthState
import com.atpro.ui.theme.AtButton
import com.atpro.ui.theme.AtButtonStyle
import com.atpro.ui.theme.AtCard
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AtStatus
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
//  v1.6.0 — Đăng nhập Follow4Follow
//
//  • F4fLoginDialog : (v1.12.7) đã tách sang F4fLoginDialog.kt.
//  • F4fAccountCard : thẻ trạng thái phiên (đăng nhập / số dư / làm mới / đăng xuất), dùng trong
//                     Cài đặt → Nhiệm vụ. Trạng thái dùng chung với tab Dịch vụ và engine qua [F4fAuth].
// ─────────────────────────────────────────────────────────────────────────────

/** Thẻ trạng thái phiên Follow4Follow. Tự kiểm tra token + cập nhật số dư khi hiện lần đầu. */
@Composable
fun F4fAccountCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val auth    = remember { F4fAuth.manager(context) }
    val state by auth.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var showLogin  by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var note       by remember { mutableStateOf<String?>(null) }

    // Mở màn hình lần đầu: xác thực token còn dùng được không (401 → tự về "chưa đăng nhập").
    LaunchedEffect(Unit) {
        if (auth.isLoggedIn) auth.refresh()
    }

    AtCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Follow4Follow", color = AtColors.TextPrim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                (state as? F4fAuthState.LoggedIn)?.username?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text("@$it", color = AtColors.TextMuted, fontSize = 12.sp, maxLines = 1)
                }
            }
            when (state) {
                is F4fAuthState.LoggedIn -> AtStatus("Đã đăng nhập", AtColors.Success, pulsing = false)
                F4fAuthState.LoggedOut   -> AtStatus("Chưa đăng nhập", AtColors.TextMuted, pulsing = false)
            }
        }

        val loggedIn = state as? F4fAuthState.LoggedIn
        if (loggedIn != null) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox("Số dư", loggedIn.balance ?: "—", Modifier.weight(1f))
                StatBox("Xu chờ", loggedIn.balancePending?.takeIf { it.isNotBlank() } ?: "0", Modifier.weight(1f))
            }
        }

        if (note != null) {
            Spacer(Modifier.height(10.dp))
            Text(note!!, color = AtColors.Warning, fontSize = 12.sp, lineHeight = 17.sp)
        }

        Spacer(Modifier.height(14.dp))
        if (loggedIn == null) {
            AtButton(text = "Đăng nhập", onClick = { note = null; showLogin = true }, height = 46.dp)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AtButton(
                    text     = if (refreshing) "Đang tải" else "Làm mới",
                    onClick  = {
                        if (!refreshing) {
                            refreshing = true
                            note = null
                            scope.launch {
                                val r = auth.refresh()
                                if (r is TaskResult.Error) note = r.message
                                refreshing = false
                            }
                        }
                    },
                    enabled  = !refreshing,
                    style    = AtButtonStyle.Secondary,
                    modifier = Modifier.weight(1f),
                    height   = 44.dp,
                )
                AtButton(
                    text     = "Đăng xuất",
                    onClick  = { scope.launch { auth.logout() } },
                    style    = AtButtonStyle.Danger,
                    modifier = Modifier.weight(1f),
                    height   = 44.dp,
                )
            }
        }
    }

    if (showLogin) F4fLoginDialog(onDismiss = { showLogin = false })
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(AtColors.SurfaceHigh, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, color = AtColors.TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = AtColors.TextPrim, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
