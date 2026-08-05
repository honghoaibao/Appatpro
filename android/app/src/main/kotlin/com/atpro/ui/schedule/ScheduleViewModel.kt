package com.atpro.ui.schedule

import android.content.Context
import androidx.lifecycle.*
import com.atpro.scheduler.ScheduledFarmManager
import com.atpro.scheduler.ScheduledFarmManager.FarmSchedule
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

class ScheduleViewModel(private val context: Context) : ViewModel() {

    private val _schedules = MutableStateFlow<List<FarmSchedule>>(emptyList())
    val schedules: StateFlow<List<FarmSchedule>> = _schedules.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _schedules.value = ScheduledFarmManager.getSchedules()
            _isLoading.value = false
        }
    }

    fun add(label: String, hour: Int, minute: Int, days: List<Int>, serviceMode: String = "FARM") {
        val schedule = FarmSchedule(
            id          = UUID.randomUUID().toString(),
            label       = label,
            hourOfDay   = hour,
            minute      = minute,
            daysOfWeek  = days,
            enabled     = true,
            serviceMode = serviceMode,
        )
        ScheduledFarmManager.setSchedule(context, schedule)
        _schedules.update { it + schedule }
    }

    fun toggle(schedule: FarmSchedule) {
        val updated = schedule.copy(enabled = !schedule.enabled)
        if (updated.enabled) ScheduledFarmManager.setSchedule(context, updated)
        else                 ScheduledFarmManager.cancelSchedule(context, schedule.id)
        _schedules.update { list -> list.map { if (it.id == schedule.id) updated else it } }
    }

    fun delete(schedule: FarmSchedule) {
        ScheduledFarmManager.cancelSchedule(context, schedule.id)
        _schedules.update { it.filter { s -> s.id != schedule.id } }
    }

    class Factory(private val ctx: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ScheduleViewModel(ctx.applicationContext) as T
    }
}
