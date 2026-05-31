package com.atpro.ui.dashboard

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.atpro.ui.MainScreen
import com.atpro.ui.accounts.AccountsViewModel
import com.atpro.ui.logs.LogsViewModel
import com.atpro.ui.stats.StatsViewModel

/**
 * DashboardActivity — single-Activity host cho toàn bộ app kể từ v1.1.5.
 *
 * [v1.1.5] Chuyển sang tab navigation: Dashboard · Thống kê · Tài khoản · Nhật ký
 * đều nằm trong MainScreen. Các Activity riêng (StatsActivity, AccountsActivity,
 * LogsActivity) vẫn giữ nguyên cho backward compat nhưng không còn được dùng
 * trong luồng điều hướng chính.
 *
 * Mọi ViewModel được khởi tạo ở đây để sống cùng vòng đời Activity,
 * không bị huỷ khi switch tab.
 *
 * FIX (crash startup): private fun enableEdgeToEdge() bị xóa vì cùng JVM signature
 * với ComponentActivity.enableEdgeToEdge(). Window colors đặt inline.
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
    private val logsVm: LogsViewModel by viewModels {
        LogsViewModel.Factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor     = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0D0D14),
                    surface    = Color(0xFF1A1A2E),
                )
            ) {
                MainScreen(
                    dashboardVm = dashboardVm,
                    statsVm     = statsVm,
                    accountsVm  = accountsVm,
                    logsVm      = logsVm,
                )
            }
        }
    }
}
