package com.atpro.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.atpro.tasks.TaskResult
import com.atpro.tasks.f4f.F4fAuth
import com.atpro.ui.theme.AtButton
import com.atpro.ui.theme.AtButtonStyle
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AtDialogCard
import com.atpro.ui.theme.AtIconBadge
import com.atpro.ui.theme.AtTextField
import com.atpro.ui.theme.AtType
import kotlinx.coroutines.launch

/**
 * Hộp thoại đăng nhập Follow4Follow (tách khỏi `F4fAccountCard.kt`).
 *
 * Mật khẩu chỉ nằm trong bộ nhớ của hộp thoại, gửi đi một lần rồi bỏ — KHÔNG lưu.
 * v1.12.7: giao diện làm lại (huy hiệu + tiêu đề, ô nhập có biểu tượng, ghi chú bảo mật, cuộn được khi bàn phím mở).
 */
@Composable
fun F4fLoginDialog(
    onDismiss:  () -> Unit,
    onLoggedIn: () -> Unit = {},
) {
    val context = LocalContext.current
    val auth    = remember { F4fAuth.manager(context) }
    val scope   = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var busy     by remember { mutableStateOf(false) }
    var error    by remember { mutableStateOf<String?>(null) }

    fun submit() {
        // Nút Done của bàn phím cũng gọi hàm này — không gửi khi còn trống.
        if (busy || username.isBlank() || password.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            when (val r = auth.login(username.trim(), password)) {
                is TaskResult.Success -> {
                    password = ""            // bỏ mật khẩu khỏi bộ nhớ ngay khi xong
                    busy = false
                    onLoggedIn()
                    onDismiss()
                }
                is TaskResult.Error -> {
                    busy = false
                    error = r.message
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress    = !busy,
            dismissOnClickOutside = !busy,
            usePlatformDefaultWidth = false,
        ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(AtColors.Scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                    onClick           = { if (!busy) onDismiss() },
                )
                .imePadding(),
        ) {
            val viewport = maxHeight
            // Cuộn được để khi bàn phím mở vẫn chạm tới ô nhập và nút; heightIn(min) giữ hộp thoại ở giữa khi đủ chỗ.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewport),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AtDialogCard(
                    modifier = Modifier
                        .padding(vertical = 16.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication        = null,
                            onClick           = { /* chặn — chạm vào thẻ không đóng hộp thoại */ },
                        ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AtIconBadge(Icons.Rounded.AccountBalanceWallet, size = 48.dp)
                        Spacer(Modifier.size(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Follow4Follow", style = AtType.Heading, color = AtColors.TextPrim)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Đăng nhập để kiếm xu và rút tiền",
                                color = AtColors.TextSec,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AtTextField(
                            value         = username,
                            onValueChange = { username = it; error = null },
                            label         = "Tên đăng nhập",
                            placeholder   = "Tài khoản follow4follow.net",
                            enabled       = !busy,
                            imeAction     = ImeAction.Next,
                            leading       = {
                                Icon(Icons.Rounded.AlternateEmail, contentDescription = null, tint = AtColors.TextMuted)
                            },
                        )
                        AtTextField(
                            value         = password,
                            onValueChange = { password = it; error = null },
                            label         = "Mật khẩu",
                            placeholder   = "Nhập mật khẩu",
                            enabled       = !busy,
                            obscure       = !showPass,
                            keyboardType  = KeyboardType.Password,
                            imeAction     = ImeAction.Done,
                            onDone        = ::submit,
                            error         = error,
                            leading       = {
                                Icon(Icons.Rounded.Lock, contentDescription = null, tint = AtColors.TextMuted)
                            },
                            trailing      = {
                                IconButton(onClick = { showPass = !showPass }, enabled = !busy) {
                                    Icon(
                                        if (showPass) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = if (showPass) "Ẩn mật khẩu" else "Hiện mật khẩu",
                                        tint     = AtColors.TextMuted,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AtColors.AccentSoft, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = AtColors.Accent,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "Mật khẩu chỉ dùng để đăng nhập, không được lưu trên máy.",
                            color = AtColors.TextSec,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }

                    Spacer(Modifier.height(18.dp))
                    AtButton(
                        text    = if (busy) "Đang xử lý" else "Đăng nhập",
                        onClick = ::submit,
                        enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        height  = 50.dp,
                    )
                    Spacer(Modifier.height(8.dp))
                    AtButton(
                        text    = "Huỷ",
                        onClick = onDismiss,
                        enabled = !busy,
                        style   = AtButtonStyle.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                        height  = 44.dp,
                    )
                }
            }
        }
    }
}
