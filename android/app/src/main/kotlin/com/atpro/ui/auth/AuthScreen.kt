package com.atpro.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atpro.auth.SupabaseConfig
import com.atpro.ui.theme.AtButton
import com.atpro.ui.theme.AtCard
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AtType

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onContinueOffline: () -> Unit,
    onClose: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AtColors.Bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("AT PRO", style = AtType.Title, color = AtColors.TextPrim)
        Spacer(Modifier.height(8.dp))
        Text("Tài khoản và sao lưu đám mây", style = AtType.Body, color = AtColors.TextSec)
        Spacer(Modifier.height(24.dp))

        AtCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(20.dp),
        ) {
            if (state.isSignedIn) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Rounded.Cloud,
                    contentDescription = null,
                    tint = AtColors.Success,
                )
                Spacer(Modifier.height(12.dp))
                Text("Đã kết nối AT PRO", style = AtType.Heading, color = AtColors.TextPrim)
                Text(state.email, style = AtType.Body, color = AtColors.TextSec)
                Text(
                    "Dữ liệu trên thiết bị vẫn được giữ local. Bản sao lưu chạy khi có Internet.",
                    style = AtType.Caption,
                    color = AtColors.TextMuted,
                )
                state.message?.let {
                    Text(it, color = AtColors.Success, style = AtType.Caption)
                }
                Spacer(Modifier.height(16.dp))
                AtButton(
                    text = if (state.isBusy) "Đang đăng xuất…" else "Đăng xuất",
                    onClick = viewModel::signOut,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onClose != null) {
                    TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Đóng") }
                }
            } else if (!SupabaseConfig.isConfigured) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Rounded.CloudOff,
                    contentDescription = null,
                    tint = AtColors.TextMuted,
                )
                Spacer(Modifier.height(12.dp))
                Text("Chưa kết nối Supabase", style = AtType.Heading, color = AtColors.TextPrim)
                Text(
                    "Tài khoản đám mây sẽ được bật sau khi hoàn tất cấu hình kết nối. App hiện vẫn dùng dữ liệu local.",
                    style = AtType.Body,
                    color = AtColors.TextSec,
                )
                Spacer(Modifier.height(16.dp))
                AtButton(
                    text = "Tiếp tục dùng ngoại tuyến",
                    onClick = onContinueOffline,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onClose != null) TextButton(onClick = onClose) { Text("Đóng") }
            } else {
                Text(
                    if (state.isRegisterMode) "Tạo tài khoản AT PRO" else "Đăng nhập AT PRO",
                    style = AtType.Heading,
                    color = AtColors.TextPrim,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                    enabled = !state.isBusy,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Mật khẩu") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (state.isRegisterMode) ImeAction.Next else ImeAction.Done,
                    ),
                    enabled = !state.isBusy,
                )
                if (state.isRegisterMode) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Nhập lại mật khẩu") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        enabled = !state.isBusy,
                    )
                }
                state.error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = AtType.Caption)
                }
                state.message?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = AtColors.Success, style = AtType.Caption)
                }
                Spacer(Modifier.height(16.dp))
                AtButton(
                    text = if (state.isRegisterMode) "Đăng ký" else "Đăng nhập",
                    onClick = {
                        if (state.isRegisterMode) viewModel.signUp(email, password, confirmPassword)
                        else viewModel.signIn(email, password)
                    },
                    enabled = !state.isBusy && email.isNotBlank() && password.isNotBlank() &&
                        (!state.isRegisterMode || confirmPassword.isNotBlank()),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.isBusy) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator()
                }
                TextButton(
                    onClick = { viewModel.setRegisterMode(!state.isRegisterMode) },
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isRegisterMode) "Đã có tài khoản? Đăng nhập" else "Chưa có tài khoản? Đăng ký")
                }
                TextButton(
                    onClick = onContinueOffline,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Tiếp tục dùng ngoại tuyến") }
                if (onClose != null) TextButton(onClick = onClose) { Text("Đóng") }
            }
        }
    }
}
