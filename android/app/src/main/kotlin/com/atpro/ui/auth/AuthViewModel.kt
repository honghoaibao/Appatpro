package com.atpro.ui.auth

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atpro.auth.AuthActionResult
import com.atpro.auth.SupabaseAuthRepository
import com.atpro.auth.SupabaseAuthState
import com.atpro.auth.SupabaseAuthUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isSignUp: Boolean = false,
    val isLoading: Boolean = false,
    val user: SupabaseAuthUser? = null,
    val message: String? = null,
    val isError: Boolean = false,
)

class AuthViewModel(private val repository: SupabaseAuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.state.collect { authState ->
                _state.update {
                    it.copy(user = (authState as? SupabaseAuthState.SignedIn)?.user)
                }
            }
        }
        viewModelScope.launch { repository.restoreSavedSession() }
    }

    fun setEmail(value: String) = _state.update { it.copy(email = value, message = null) }
    fun setPassword(value: String) = _state.update { it.copy(password = value, message = null) }
    fun setConfirmPassword(value: String) = _state.update { it.copy(confirmPassword = value, message = null) }

    fun setSignUp(enabled: Boolean) = _state.update {
        it.copy(isSignUp = enabled, message = null, confirmPassword = "")
    }

    fun submit() {
        val current = _state.value
        val email = current.email.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError("Vui lòng nhập địa chỉ email hợp lệ.")
            return
        }
        if (current.password.length < 6) {
            showError("Mật khẩu cần có ít nhất 6 ký tự.")
            return
        }
        if (current.isSignUp && current.password != current.confirmPassword) {
            showError("Mật khẩu xác nhận không khớp.")
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, message = null, isError = false) }
            val result = if (current.isSignUp) {
                repository.signUp(email, current.password)
            } else {
                repository.signIn(email, current.password)
            }
            _state.update { it.copy(isLoading = false) }
            when (result) {
                is AuthActionResult.Success -> {
                    val message = if (result.needsEmailConfirmation) {
                        "Tài khoản đã được tạo. Hãy kiểm tra email để xác nhận trước khi đăng nhập."
                    } else {
                        "Đăng nhập AT PRO thành công."
                    }
                    _state.update {
                        it.copy(
                            message = message,
                            isError = false,
                            password = "",
                            confirmPassword = "",
                            isSignUp = false,
                        )
                    }
                }
                is AuthActionResult.Failure -> showError(result.message)
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, message = null) }
            repository.signOut()
            _state.update {
                it.copy(
                    isLoading = false,
                    password = "",
                    confirmPassword = "",
                    message = "Đã đăng xuất khỏi AT PRO trên thiết bị này.",
                    isError = false,
                )
            }
        }
    }

    private fun showError(message: String) {
        _state.update { it.copy(message = message, isError = true) }
    }

    class Factory(private val context: android.content.Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(AuthViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            return AuthViewModel(SupabaseAuthRepository.getInstance(context)) as T
        }
    }
}
