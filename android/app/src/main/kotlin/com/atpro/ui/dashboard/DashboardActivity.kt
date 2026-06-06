package com.atpro.ui.dashboard

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.atpro.ui.MainScreen
import com.atpro.ui.accounts.AccountsViewModel
import com.atpro.ui.golike.GolikeLoginWebActivity
import com.atpro.ui.golike.GolikeViewModel
import com.atpro.ui.logs.LogsViewModel
import com.atpro.ui.stats.StatsViewModel

/**
 * DashboardActivity — single-Activity host cho toàn bộ app kể từ v1.1.5.
 *
 * v1.2.1: Thêm launcher cho GolikeLoginWebActivity.
 * Khi user bấm "Đăng nhập Golike" ở ServicesScreen →
 *   openGolikeLoginLauncher.launch() → GolikeLoginWebActivity →
 *   RESULT_OK → golikeVm.receiveTokenFromWebLogin(token) → refresh UI.
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
    private val golikeVm: GolikeViewModel by viewModels {
        GolikeViewModel.Factory(applicationContext)
    }

    // v1.2.1: Launcher để nhận kết quả từ GolikeLoginWebActivity
    private val golikeLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val token = result.data?.getStringExtra(GolikeLoginWebActivity.EXTRA_TOKEN) ?: ""
            golikeVm.receiveTokenFromWebLogin(token)
        }
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
                    dashboardVm      = dashboardVm,
                    statsVm          = statsVm,
                    accountsVm       = accountsVm,
                    logsVm           = logsVm,
                    golikeVm         = golikeVm,
                    onOpenGolikeLogin = {
                        golikeLoginLauncher.launch(
                            Intent(this@DashboardActivity, GolikeLoginWebActivity::class.java)
                        )
                    },
                )
            }
        }
    }
}

