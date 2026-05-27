package com.atpro.ui.logs

import android.content.Context
import androidx.lifecycle.*
import com.atpro.db.AtProDatabase
import com.atpro.db.dao.LogDao
import com.atpro.db.entity.FarmLogEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LogsViewModel(private val logDao: LogDao) : ViewModel() {

    private val _levelFilter = MutableStateFlow<String?>(null)  // null = all

    /**
     * Stream logs từ Room — realtime khi engine ghi log mới.
     * Kết hợp với level filter để cho phép lọc theo INFO/WARNING/ERROR/SUCCESS.
     */
    val logs: StateFlow<List<FarmLogEntity>> =
        combine(logDao.observeRecent(500), _levelFilter) { all, level ->
            if (level == null) all else all.filter { it.level == level }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val levelFilter: StateFlow<String?> = _levelFilter.asStateFlow()

    fun setFilter(level: String?) { _levelFilter.value = level }

    fun clearAll() { viewModelScope.launch { logDao.clearAll() } }

    class Factory(private val ctx: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = AtProDatabase.getInstance(ctx.applicationContext)
            return LogsViewModel(db.logDao()) as T
        }
    }
}
