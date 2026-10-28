package com.atpro.ui.accounts

import android.os.Bundle
import com.atpro.ui.theme.AtTheme
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.*

class AccountsActivity : AppCompatActivity() {

    private val vm: AccountsViewModel by viewModels {
        AccountsViewModel.Factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor     = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContent {
            AtTheme() {
                AccountsScreen(vm = vm)
            }
        }
    }
}
