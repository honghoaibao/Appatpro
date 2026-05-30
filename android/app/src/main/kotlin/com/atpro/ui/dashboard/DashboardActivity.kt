package com.atpro.ui.dashboard

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * DashboardActivity — host Activity cho native Compose dashboard.
 * Dashboard chinh cua AT PRO — native Compose (TASK-007, Flutter removed TASK-017).
 *
 * Extends AppCompatActivity (khong phai ComponentActivity) vi manifest dung
 * @style/AppTheme (parent: Theme.AppCompat.NoActionBar). AppCompatActivity la
 * superclass cua ComponentActivity nen Compose setContent/viewModels van hoat dong.
 *
 * FIX (crash startup): private fun enableEdgeToEdge() bi xoa vi cung JVM signature
 * voi ComponentActivity.enableEdgeToEdge() (@JvmOverloads). ART resolve invokevirtual
 * qua vtable -> goi parent's version thay vi local private method -> crash.
 * Window colors dat inline giong cac Activity khac.
 */
class DashboardActivity : AppCompatActivity() {

    private val vm: DashboardViewModel by viewModels {
        DashboardViewModel.Factory(applicationContext)
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
                DashboardScreen(vm = vm)
            }
        }
    }
}
