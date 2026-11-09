package com.atpro.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atpro.ui.theme.AtButton
import com.atpro.ui.theme.AtButtonStyle
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AtTextField
import com.atpro.ui.theme.AtTopBar
import com.atpro.ui.theme.AtType

@Composable
fun AuthScreen(viewModel: AuthViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AtColors.Bg)
            .statusBarsPadding()
            .padding(bottom = 16.dp),
    ) {
        AtTopBar(
            title = "Tài khoản AT PRO",
            subtitle = "Đăng nhập để quản lý tài khoản AT PRO",
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            if (state.user != null) {
                Text("Đã đăng nhập", style = AtType.Title, color = AtColors.TextPrim)
                Text(
                    state.user?.email.orEmpty(),
                    color = AtColors.TextSec,
                    fontSize = 14.sp,
                )
                Text(
                    "Dữ liệu trên thiết bị vẫn được lưu cục bộ. Việc đồng bộ và khôi phục cloud sẽ được kết nối ở giai đoạn tiếp theo.",
                    color = AtColors.TextMuted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                AtButton(
                    text = if (state.isLoading) "Đang xử lý..." else "Đăng xuất",
                    onClick = viewModel::signOut,
                    enabled = !state.isLoading,
                    style = AtButtonStyle.Danger,
                )
            } else {
                Text(
                    if (state.isSignUp) "Tạo tài khoản mới" else "Chào mừng trở lại",
                    style = AtType.Title,
                    color = AtColors.TextPrim,
                )
                Text(
                    if (state.isSignUp)
                        "Tài khoản AT PRO dùng để bảo vệ bản sao lưu dữ liệu của anh."
                    else
                        "Đăng nhập để chuẩn bị sử dụng tính năng sao lưu dữ liệu.",
                    color = AtColors.TextMuted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )

                AtTextField(
                    value = state.email,
                    onValueChange = viewModel::setEmail,
                    label = "Email",
                    placeholder = "name@example.com",
                    keyboardType = KeyboardType.Email,
                )
                AtTextField(
                    value = state.password,
                    onValueChange = viewModel::setPassword,
                    label = "Mật khẩu",
                    placeholder = "Nhập mật khẩu",
                    obscure = true,
                    keyboardType = KeyboardType.Password,
                )
                if (state.isSignUp) {
                    AtTextField(
                        value = state.confirmPassword,
                        onValueChange = viewModel::setConfirmPassword,
                        label = "Xác nhận mật khẩu",
                        placeholder = "Nhập lại mật khẩu",
                        obscure = true,
                        keyboardType = KeyboardType.Password,
                    )
                }

                AtButton(
                    text = when {
                        state.isLoading -> "Đang xử lý..."
                        state.isSignUp -> "Tạo tài khoản"
                        else -> "Đăng nhập"
                    },
                    onClick = viewModel::submit,
                    enabled = !state.isLoading,
                )

                TextButton(
                    onClick = { viewModel.setSignUp(!state.isSignUp) },
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.isSignUp) "Đã có tài khoản? Đăng nhập"
                        else "Chưa có tài khoản? Đăng ký",
                        color = AtColors.Accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    color = if (state.isError) AtColors.Danger else AtColors.Success,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
        }
    }
}
