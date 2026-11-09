package com.atpro

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.atpro.auth.ui.AuthActivity
import com.atpro.ui.dashboard.DashboardActivity
import kotlinx.coroutines.launch

/**
 * Launcher gate: restore the local Auth session before opening the app's main dashboard.
 * Core app data remains local-first; an offline restore keeps a saved session available.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val session = (application as AtProApplication).authRepository.restoreSession()
            if (isFinishing || isDestroyed) return@launch

            val destination = if (session != null) {
                DashboardActivity::class.java
            } else {
                AuthActivity::class.java
            }

            startActivity(
                Intent(this@MainActivity, destination).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
            finish()
        }
    }
}
