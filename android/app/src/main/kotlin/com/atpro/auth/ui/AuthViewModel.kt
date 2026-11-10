package com.atpro.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atpro.auth.domain.AuthRepository
import com.atpro.auth.domain.AuthResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isRegisterMode: Boolean = false,
    val isSubmitting: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

sealed interface AuthUiEvent {
    data object OpenDashboard : AuthUiEvent
}

class AuthViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = mutableState.asStateFlow()

    private val eventChannel = Channel<AuthUiEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    fun updateEmail(value: String) {
        mutableState.update { it.copy(email = value, message = null) }
    }

    fun updatePassword(value: String) {
        mutableState.update { it.copy(password = value, message = null) }
    }

    fun updateConfirmPassword(value: String) {
        mutableState.update { it.copy(confirmPassword = value, message = null) }
    }

    fun toggleMode() {
        mutableState.update {
            it.copy(
                isRegisterMode = !it.isRegisterMode,
                confirmPassword = "",
                message = null,
                isError = false,
            )
        }
    }

    fun submit() {
        val current = mutableState.value
        if (current.isSubmitting) return

        val email = current.email.trim()
        val password = current.password

        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showMessage("Vui lòng nhập địa chỉ email hợp lệ.", isError = true)
            return
        }
        if (password.isBlank()) {
            showMessage("Vui lòng nhập mật khẩu.", isError = true)
            return
        }
        if (current.isRegisterMode && password != current.confirmPassword) {
            showMessage("Mật khẩu xác nhận chưa khớp.", isError = true)
            return
        }

        viewModelScope.launch {
            mutableState.update { it.copy(isSubmitting = true, message = null, isError = false) }
            try {
                if (current.isRegisterMode) {
                    when (val result = authRepository.signUp(email, password)) {
                        is AuthResult.Success -> {
                            if (result.value.emailConfirmationRequired) {
                                mutableState.update {
                                    it.copy(
                                        isRegisterMode = false,
                                        password = "",
                                        confirmPassword = "",
                                        isSubmitting = false,
                                        message = "Tài khoản đã được tạo. Hãy kiểm tra email để xác nhận trước khi đăng nhập.",
                                        isError = false,
                                    )
                                }
                            } else {
                                completeAuthentication()
                            }
                        }
                        is AuthResult.Failure -> showMessage(result.message, isError = true)
                    }
                } else {
                    when (val result = authRepository.signIn(email, password)) {
                        is AuthResult.Success -> completeAuthentication()
                        is AuthResult.Failure -> showMessage(result.message, isError = true)
                    }
                }
            } finally {
                mutableState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    private fun completeAuthentication() {
        mutableState.update {
            it.copy(
                password = "",
                confirmPassword = "",
                isSubmitting = false,
                message = null,
                isError = false,
            )
        }
        eventChannel.trySend(AuthUiEvent.OpenDashboard)
    }

    private fun showMessage(message: String, isError: Boolean) {
        mutableState.update {
            it.copy(
                isSubmitting = false,
                message = message,
                isError = isError,
            )
        }
    }

    class Factory(
        private val authRepository: AuthRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AuthViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return AuthViewModel(authRepository) as T
        }
    }
}
