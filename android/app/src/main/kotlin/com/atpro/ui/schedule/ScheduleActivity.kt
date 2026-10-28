package com.atpro.ui.schedule

import android.os.Bundle
import com.atpro.ui.theme.AtTheme
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.*

class ScheduleActivity : AppCompatActivity() {
    private val vm: ScheduleViewModel by viewModels { ScheduleViewModel.Factory(applicationContext) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor     = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContent {
            AtTheme() { ScheduleScreen(vm = vm) }
        }
    }
}
