package com.atpro.ui.logs

import android.os.Bundle
import com.atpro.ui.theme.AtTheme
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.*

class LogsActivity : AppCompatActivity() {

    private val vm: LogsViewModel by viewModels {
        LogsViewModel.Factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor     = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContent {
            AtTheme() {
                LogsScreen(vm = vm)
            }
        }
    }
}
