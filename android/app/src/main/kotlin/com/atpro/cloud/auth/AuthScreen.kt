package com.atpro.cloud.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Standalone auth form. The host Activity/ViewModel supplies callbacks and state;
 * this screen does not create a network client or touch automation/application data.
 */
@Composable
fun AuthScreen(
    loading: Boolean,
    message: String?,
    onSubmit: (email: String, password: String, createAccount: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var createAccount by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (createAccount) "Tạo tài khoản AT PRO" else "Đăng nhập AT PRO",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Đăng nhập để đồng bộ và khôi phục dữ liệu của bạn.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Email") },
            singleLine = true,
            enabled = !loading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Mật khẩu") },
            singleLine = true,
            enabled = !loading,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )

        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onSubmit(email.trim(), password, createAccount) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && email.contains("@") && password.length >= 6,
        ) {
            if (loading) {
                CircularProgressIndicator()
            } else {
                Text(if (createAccount) "Đăng ký" else "Đăng nhập")
            }
        }
        TextButton(
            onClick = { createAccount = !createAccount },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading,
        ) {
            Text(
                if (createAccount) "Đã có tài khoản? Đăng nhập"
                else "Chưa có tài khoản? Đăng ký",
            )
        }
    }
}
