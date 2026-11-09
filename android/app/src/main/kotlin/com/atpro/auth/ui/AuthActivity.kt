package com.atpro.auth.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.atpro.AtProApplication
import com.atpro.ui.dashboard.DashboardActivity
import com.atpro.ui.theme.AtTheme
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private val authViewModel: AuthViewModel by viewModels {
        AuthViewModel.Factory((application as AtProApplication).authRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        setContent {
            AtTheme {
                AuthScreen(authViewModel)
            }
        }

        lifecycleScope.launch {
            authViewModel.events.collect { event ->
                when (event) {
                    AuthUiEvent.OpenDashboard -> {
                        startActivity(
                            Intent(this@AuthActivity, DashboardActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                        finish()
                    }
                }
            }
        }
    }
}
