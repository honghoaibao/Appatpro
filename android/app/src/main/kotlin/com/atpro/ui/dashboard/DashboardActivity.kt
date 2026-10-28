package com.atpro.ui.dashboard

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.atpro.ui.theme.AtTheme
import com.atpro.ui.MainScreen
import com.atpro.ui.accounts.AccountsViewModel
import com.atpro.ui.schedule.ScheduleViewModel
import com.atpro.ui.stats.StatsViewModel

/**
 * DashboardActivity — single-Activity host cho toàn bộ app kể từ v1.1.5.
 *
 * v1.2.7: Xoá Golike UI (GolikeViewModel, GolikeLoginWebActivity).
 * v1.4.0: Golike gỡ hoàn toàn (backend + WebView); task mode đi qua TaskProvider.
 * v1.2.8: Thêm scheduleVm cho tab Lịch tự động.
 * v1.2.9: Xóa logsVm (Nhật ký chuyển vào Cài đặt); thêm Golike token & balance.
 */
class DashboardActivity : AppCompatActivity() {

    private val dashboardVm: DashboardViewModel by viewModels {
        DashboardViewModel.Factory(applicationContext)
    }
    private val statsVm: StatsViewModel by viewModels {
        StatsViewModel.Factory(applicationContext)
    }
    private val accountsVm: AccountsViewModel by viewModels {
        AccountsViewModel.Factory(applicationContext)
    }
    private val scheduleVm: ScheduleViewModel by viewModels {
        ScheduleViewModel.Factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor     = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContent {
            AtTheme() {
                MainScreen(
                    dashboardVm = dashboardVm,
                    statsVm     = statsVm,
                    accountsVm  = accountsVm,
                    scheduleVm  = scheduleVm,
                )
            }
        }
    }
}
