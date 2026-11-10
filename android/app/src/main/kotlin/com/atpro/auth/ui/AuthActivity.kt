package com.atpro.auth.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                authViewModel.events.collect { event ->
                    when (event) {
                        AuthUiEvent.OpenDashboard -> {
                            startActivity(Intent(this@AuthActivity, DashboardActivity::class.java))
                            finish()
                        }
                    }
                }
            }
        }
    }
}
