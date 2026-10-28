package com.atpro.ui.config

import android.os.Bundle
import com.atpro.ui.theme.AtTheme
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.*
import com.atpro.ui.logs.LogsViewModel

class ConfigActivity : AppCompatActivity() {
    private val vm:     ConfigViewModel by viewModels { ConfigViewModel.Factory(applicationContext) }
    private val logsVm: LogsViewModel   by viewModels { LogsViewModel.Factory(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor     = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContent {
            AtTheme() { ConfigScreen(vm = vm, logsVm = logsVm) }
        }
    }
}
