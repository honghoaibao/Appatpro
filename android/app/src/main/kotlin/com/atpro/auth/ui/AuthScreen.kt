package com.atpro.auth.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atpro.R
import com.atpro.ui.theme.AtButton
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AtSegmented
import com.atpro.ui.theme.AtTextField
import com.atpro.ui.theme.AtType

@Composable
fun AuthScreen(viewModel: AuthViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var confirmPasswordVisible by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AtColors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // v1.12.7: logo thật của ứng dụng (trước đây chỉ là chữ "AT" trong ô màu).
            Image(
                painter = painterResource(R.drawable.icon_app),
                contentDescription = "AT PRO",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, AtColors.Hairline, RoundedCornerShape(20.dp)),
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = "AT PRO",
                style = AtType.Title,
                color = AtColors.TextPrim,
            )

            Spacer(Modifier.height(24.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(AtColors.Surface)
                    .border(1.dp, AtColors.Hairline, shape)
                    .padding(20.dp),
            ) {
                // v1.12.7: chuyển Đăng nhập / Đăng ký ngay đầu thẻ (trước đây là liên kết nhỏ ở cuối thẻ).
                AtSegmented(
                    options = listOf("Đăng nhập", "Đăng ký"),
                    selectedIndex = if (state.isRegisterMode) 1 else 0,
                    onSelect = { index ->
                        if (!state.isSubmitting && (index == 1) != state.isRegisterMode) viewModel.toggleMode()
                    },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (state.isRegisterMode) "Tạo tài khoản để sao lưu và đồng bộ dữ liệu giữa các thiết bị."
                    else "Đăng nhập để đồng bộ dữ liệu giữa các thiết bị.",
                    color = AtColors.TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(18.dp))

                AtTextField(
                    value = state.email,
                    onValueChange = viewModel::updateEmail,
                    label = "Email",
                    placeholder = "name@example.com",
                    enabled = !state.isSubmitting,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                    leading = {
                        Icon(Icons.Rounded.Email, contentDescription = null, tint = AtColors.TextMuted)
                    },
                )

                Spacer(Modifier.height(14.dp))
                AtTextField(
                    value = state.password,
                    onValueChange = viewModel::updatePassword,
                    label = "Mật khẩu",
                    placeholder = "Nhập mật khẩu",
                    enabled = !state.isSubmitting,
                    obscure = !passwordVisible,
                    keyboardType = KeyboardType.Password,
                    imeAction = if (state.isRegisterMode) ImeAction.Next else ImeAction.Done,
                    onDone = viewModel::submit,
                    leading = {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = AtColors.TextMuted)
                    },
                    trailing = {
                        IconButton(
                            onClick = { passwordVisible = !passwordVisible },
                            enabled = !state.isSubmitting,
                        ) {
                            Icon(
                                if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (passwordVisible) "Ẩn mật khẩu" else "Hiện mật khẩu",
                                tint = AtColors.TextMuted,
                            )
                        }
                    },
                )

                if (state.isRegisterMode) {
                    Spacer(Modifier.height(14.dp))
                    AtTextField(
                        value = state.confirmPassword,
                        onValueChange = viewModel::updateConfirmPassword,
                        label = "Xác nhận mật khẩu",
                        placeholder = "Nhập lại mật khẩu",
                        enabled = !state.isSubmitting,
                        obscure = !confirmPasswordVisible,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        onDone = viewModel::submit,
                        leading = {
                            Icon(Icons.Rounded.Lock, contentDescription = null, tint = AtColors.TextMuted)
                        },
                        trailing = {
                            IconButton(
                                onClick = { confirmPasswordVisible = !confirmPasswordVisible },
                                enabled = !state.isSubmitting,
                            ) {
                                Icon(
                                    if (confirmPasswordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (confirmPasswordVisible) "Ẩn mật khẩu" else "Hiện mật khẩu",
                                    tint = AtColors.TextMuted,
                                )
                            }
                        },
                    )
                }

                state.message?.let { message ->
                    Spacer(Modifier.height(14.dp))
                    val messageColor = if (state.isError) AtColors.Danger else AtColors.Success
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(messageColor.copy(alpha = 0.10f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message,
                            color = messageColor,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                AtButton(
                    text = if (state.isRegisterMode) "Tạo tài khoản" else "Đăng nhập",
                    onClick = viewModel::submit,
                    enabled = !state.isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                    height = 52.dp,
                )

                if (state.isSubmitting) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = AtColors.Accent,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(9.dp))
                        Text("Đang xử lý...", color = AtColors.TextMuted, fontSize = 12.sp)
                    }
                }

            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "Không có mạng vẫn dùng được dữ liệu trên máy.",
                color = AtColors.TextMuted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}


@Composable
fun AuthCheckingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AtColors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = AtColors.Accent,
                strokeWidth = 2.5.dp,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Đang kiểm tra tài khoản",
                color = AtColors.TextSec,
                fontSize = 13.sp,
            )
        }
    }
}
