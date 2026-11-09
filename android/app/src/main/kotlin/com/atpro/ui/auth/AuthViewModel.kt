package com.atpro.ui.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atpro.auth.AuthOperationResult
import com.atpro.auth.SupabaseServices
import com.atpro.backup.BackupStateStore
import com.atpro.backup.BackupWorkScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class AuthUiState(
    val isSignedIn: Boolean = false,
    val email: String = "",
    val isRegisterMode: Boolean = false,
    val isBusy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class AuthViewModel(private val appContext: Context) : ViewModel() {
    private val repository = SupabaseServices.auth(appContext)
    private val _state = MutableStateFlow(
        AuthUiState(
            isSignedIn = repository.currentSession() != null,
            email = repository.currentSession()?.user?.email.orEmpty(),
        )
    )
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.session.collectLatest { session ->
                _state.value = _state.value.copy(
                    isSignedIn = session != null,
                    email = session?.user?.email.orEmpty(),
                    isBusy = false,
                )
            }
        }
        viewModelScope.launch {
            if (repository.currentSession() != null) repository.refreshSession()
        }
    }

    fun setRegisterMode(enabled: Boolean) {
        _state.value = _state.value.copy(isRegisterMode = enabled, message = null, error = null)
    }

    fun signIn(email: String, password: String) {
        if (_state.value.isBusy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true, error = null, message = null)
            when (val result = repository.signIn(email, password)) {
                is AuthOperationResult.Success -> {
                    BackupStateStore.setOfflineMode(appContext, false)
                    BackupWorkScheduler.enqueue(appContext)
                    _state.value = _state.value.copy(
                        isBusy = false,
                        isSignedIn = true,
                        email = result.user.email,
                        message = "Đăng nhập thành công",
                    )
                }
                is AuthOperationResult.Error -> _state.value =
                    _state.value.copy(isBusy = false, error = result.message)
                AuthOperationResult.EmailConfirmationRequired -> _state.value =
                    _state.value.copy(isBusy = false, message = "Hãy xác nhận email trước khi đăng nhập")
            }
        }
    }

    fun signUp(email: String, password: String, confirmPassword: String) {
        if (_state.value.isBusy) return
        if (password != confirmPassword) {
            _state.value = _state.value.copy(error = "Mật khẩu xác nhận không khớp")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true, error = null, message = null)
            when (val result = repository.signUp(email, password)) {
                is AuthOperationResult.Success -> {
                    BackupStateStore.setOfflineMode(appContext, false)
                    BackupWorkScheduler.enqueue(appContext)
                    _state.value = _state.value.copy(
                        isBusy = false,
                        isSignedIn = true,
                        email = result.user.email,
                        message = "Tạo tài khoản thành công",
                    )
                }
                is AuthOperationResult.Error -> _state.value =
                    _state.value.copy(isBusy = false, error = result.message)
                AuthOperationResult.EmailConfirmationRequired -> _state.value = _state.value.copy(
                    isBusy = false,
                    isRegisterMode = false,
                    message = "Đã gửi email xác nhận. Hãy xác nhận rồi đăng nhập.",
                )
            }
        }
    }

    fun signOut() {
        if (_state.value.isBusy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true, error = null)
            repository.signOut()
            _state.value = _state.value.copy(
                isBusy = false,
                isSignedIn = false,
                email = "",
                message = "Đã đăng xuất",
            )
        }
    }

    fun continueOffline() {
        BackupStateStore.setOfflineMode(appContext, true)
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AuthViewModel(context.applicationContext) as T
    }
}
