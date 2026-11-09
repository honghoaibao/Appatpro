package com.atpro.ui.dashboard

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atpro.auth.SupabaseConfig
import com.atpro.backup.BackupStateStore
import com.atpro.ui.auth.AuthScreen
import com.atpro.ui.auth.AuthViewModel
import com.atpro.notification.NotificationNavigator
import com.atpro.ui.theme.AtTheme
import com.atpro.ui.MainScreen
import com.atpro.ui.accounts.AccountsViewModel
import com.atpro.ui.logs.LogsViewModel
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
    // v1.8.0: Nhật ký thành tab riêng (trước đây nằm trong Cài đặt).
    private val logsVm: LogsViewModel by viewModels {
        LogsViewModel.Factory(applicationContext)
    }
    private val authVm: AuthViewModel by viewModels {
        AuthViewModel.Factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // API ≥ 34: khai báo hiệu ứng mở (fade) — (API < 34 dùng hiệu ứng mặc định của hệ thống).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                android.app.Activity.OVERRIDE_TRANSITION_OPEN,
                android.R.anim.fade_in,
                android.R.anim.fade_out,
            )
        }
        window.statusBarColor     = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        handleNotificationIntent(intent)
        setContent {
            AtTheme {
                val authState by authVm.state.collectAsStateWithLifecycle()
                var cloudAccountOpen by remember { mutableStateOf(false) }
                val requiresCloudLogin = SupabaseConfig.isConfigured &&
                    !BackupStateStore.isOfflineMode(applicationContext) &&
                    !authState.isSignedIn

                if (requiresCloudLogin) {
                    AuthScreen(
                        viewModel = authVm,
                        onContinueOffline = { authVm.continueOffline() },
                    )
                } else {
                    Box(Modifier.fillMaxSize()) {
                        MainScreen(
                            dashboardVm = dashboardVm,
                            statsVm = statsVm,
                            accountsVm = accountsVm,
                            scheduleVm = scheduleVm,
                            logsVm = logsVm,
                            onOpenCloudAccount = { cloudAccountOpen = true },
                        )
                        if (cloudAccountOpen) {
                            AuthScreen(
                                viewModel = authVm,
                                onContinueOffline = {
                                    authVm.continueOffline()
                                    cloudAccountOpen = false
                                },
                                onClose = { cloudAccountOpen = false },
                            )
                        }
                    }
                }
            }
        }
    }

    /** v1.12.0: app đang mở mà người dùng chạm thông báo → chuyển đúng tab (launchMode singleTop). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        intent?.getStringExtra(NotificationNavigator.EXTRA_OPEN_TAB)?.let { NotificationNavigator.request(it) }
    }
}
