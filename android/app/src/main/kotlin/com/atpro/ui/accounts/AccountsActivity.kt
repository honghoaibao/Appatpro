package com.atpro.ui.accounts

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color

class AccountsActivity : AppCompatActivity() {

    private val vm: AccountsViewModel by viewModels {
        AccountsViewModel.Factory(applicationContext)
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
                AccountsScreen(vm = vm)
            }
        }
    }
}
