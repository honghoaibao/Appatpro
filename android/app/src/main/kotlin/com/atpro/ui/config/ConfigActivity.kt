package com.atpro.ui.config

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import com.atpro.ui.golike.GolikeViewModel

class ConfigActivity : AppCompatActivity() {
    private val vm:       ConfigViewModel  by viewModels { ConfigViewModel.Factory(applicationContext) }
    private val golikeVm: GolikeViewModel  by viewModels { GolikeViewModel.Factory(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor     = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContent {
            MaterialTheme(darkColorScheme(
                background = Color(0xFF0D0D14), surface = Color(0xFF1A1A2E),
            )) { ConfigScreen(vm = vm, golikeVm = golikeVm) }
        }
    }
}
