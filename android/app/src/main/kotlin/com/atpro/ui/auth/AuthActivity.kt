package com.atpro.ui.auth

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.atpro.ui.theme.AtTheme

class AuthActivity : AppCompatActivity() {
    private val viewModel: AuthViewModel by viewModels {
        AuthViewModel.Factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AtTheme {
                AuthScreen(viewModel = viewModel, onBack = { finish() })
            }
        }
    }
}
