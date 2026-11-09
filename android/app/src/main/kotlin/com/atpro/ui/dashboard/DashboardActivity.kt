package com.atpro.ui.dashboard

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.atpro.AtProApplication
import com.atpro.auth.ui.AuthActivity
import com.atpro.notification.NotificationNavigator
import com.atpro.ui.MainScreen
import com.atpro.ui.accounts.AccountsViewModel
import com.atpro.ui.logs.LogsViewModel
import com.atpro.ui.schedule.ScheduleViewModel
import com.atpro.ui.stats.StatsViewModel
import com.atpro.ui.theme.AtTheme
import kotlinx.coroutines.launch

/**
 * DashboardActivity — single-Activity host cho toàn bộ app.
 * Session is checked here as a second gate for notification/internal launches.
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
    private val logsVm: LogsViewModel by viewModels {
        LogsViewModel.Factory(applicationContext)
    }

    private var dashboardReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                android.app.Activity.OVERRIDE_TRANSITION_OPEN,
                android.R.anim.fade_in,
                android.R.anim.fade_out,
            )
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        lifecycleScope.launch {
            val app = application as AtProApplication
            val session = app.authRepository.restoreSession()
            if (isFinishing || isDestroyed) return@launch

            if (session == null) {
                startActivity(Intent(this@DashboardActivity, AuthActivity::class.java))
                finish()
                return@launch
            }

            showDashboard(app)
        }
    }

    private fun showDashboard(app: AtProApplication) {
        if (dashboardReady) return
        dashboardReady = true
        handleNotificationIntent(intent)

        setContent {
            AtTheme {
                MainScreen(
                    dashboardVm = dashboardVm,
                    statsVm = statsVm,
                    accountsVm = accountsVm,
                    scheduleVm = scheduleVm,
                    logsVm = logsVm,
                    authRepository = app.authRepository,
                    onSignOut = {
                        // Run sign-out in the application scope so it is not cancelled
                        // when this Activity is finished and the auth screen is opened.
                        app.appScope.launch {
                            app.authRepository.signOut()
                        }
                        startActivity(Intent(this, AuthActivity::class.java))
                        finish()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (dashboardReady) handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        intent?.getStringExtra(NotificationNavigator.EXTRA_OPEN_TAB)?.let {
            NotificationNavigator.request(it)
        }
    }
}
