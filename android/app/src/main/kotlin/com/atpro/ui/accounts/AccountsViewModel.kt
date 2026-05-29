package com.atpro.ui.accounts

import android.content.Context
import androidx.lifecycle.*
import com.atpro.data.IFarmRepository
import com.atpro.data.LocalRepository
import com.atpro.db.AtProDatabase
import com.atpro.db.dao.AccountDao
import com.atpro.db.entity.AccountEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AccountsViewModel(
    private val accountDao: AccountDao,
    private val repo:       IFarmRepository,
) : ViewModel() {

    // Raw stream từ Room — reactive, cập nhật khi engine auto-save account mới
    val accounts: StateFlow<List<AccountEntity>> =
        accountDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(username: String) {
        viewModelScope.launch { repo.log("[DEL] Xóa tài khoản: @$username") }
        // Delegate qua LocalRepository cast — xóa account
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
            return AccountsViewModel(db.accountDao(), lr) as T
        }
    }
}
