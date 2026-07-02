package com.atpro.ui.accounts

import android.content.Context
import androidx.lifecycle.*
import com.atpro.data.IFarmRepository
import com.atpro.data.LocalRepository
import com.atpro.db.AtProDatabase
import com.atpro.db.dao.AccountDao
import com.atpro.db.entity.AccountEntity
import com.atpro.golike.GolikeRepository
import com.atpro.golike.GolikeResult
import com.atpro.golike.TikTokAccountDto
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AccountsViewModel(
    private val accountDao:   AccountDao,
    private val repo:         IFarmRepository,
    private val golikeRepo:   GolikeRepository? = null,
) : ViewModel() {

    // Raw stream từ Room — reactive, cập nhật khi engine auto-save account mới
    val accounts: StateFlow<List<AccountEntity>> =
        accountDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // v1.2.9: Danh sách TikTok acc liên kết với Golike
    private val _golikeAccounts = MutableStateFlow<List<TikTokAccountDto>>(emptyList())
    val golikeAccounts: StateFlow<List<TikTokAccountDto>> = _golikeAccounts.asStateFlow()

    private val _golikeLoading = MutableStateFlow(false)
    val golikeLoading: StateFlow<Boolean> = _golikeLoading.asStateFlow()

    private val _golikeError = MutableStateFlow<String?>(null)
    val golikeError: StateFlow<String?> = _golikeError.asStateFlow()

    init {
        // Tự động load Golike accounts nếu repo khả dụng
        if (golikeRepo != null) refreshGolikeAccounts()
    }

    fun refreshGolikeAccounts() {
        val gr = golikeRepo ?: return
        viewModelScope.launch {
            _golikeLoading.value = true
            _golikeError.value   = null
            when (val r = gr.getTikTokAccounts()) {
                is GolikeResult.Success -> {
                    _golikeAccounts.value = r.data
                    _golikeError.value    = null
                }
                is GolikeResult.Error   -> {
                    _golikeError.value = r.message
                }
            }
            _golikeLoading.value = false
        }
    }

    fun delete(username: String) {
        viewModelScope.launch { repo.log("DEL: Xóa tài khoản: @$username") }
        (repo as? LocalRepository)?.let { lr ->
            viewModelScope.launch { lr.deleteAccount(username) }
        }
    }

    fun setStatus(username: String, status: String) {
        (repo as? LocalRepository)?.let { lr ->
            viewModelScope.launch { lr.updateStatus(username, status) }
        }
    }

    class Factory(private val ctx: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = AtProDatabase.getInstance(ctx.applicationContext)
            val lr = LocalRepository.getInstance(ctx.applicationContext)
            val gr = GolikeRepository.getInstance(lr)
            return AccountsViewModel(db.accountDao(), lr, gr) as T
        }
    }
}
