package com.atpro.ui.config

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.atpro.ui.theme.AtTheme
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

/**
 * Màn hình cấu hình chi tiết. v1.8.0: mở từ tab "Cài đặt" kèm [EXTRA_SECTION] (tên mục) để vào thẳng mục cần chỉnh;
 * Nhật ký đã chuyển thành tab riêng nên không còn nằm trong Cài đặt.
 */
class ConfigActivity : AppCompatActivity() {
    private val vm: ConfigViewModel by viewModels { ConfigViewModel.Factory(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor     = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val section = intent.getStringExtra(EXTRA_SECTION)
        setContent {
            AtTheme() { ConfigScreen(vm = vm, initialSection = section) }
        }
    }

    companion object {
        const val EXTRA_SECTION = "com.atpro.config.SECTION"

        /** Intent mở Cài đặt chi tiết tại mục [section] (tên enum, vd "PERMISSIONS"); null = mục mặc định. */
        fun intent(context: Context, section: String? = null): Intent =
            Intent(context, ConfigActivity::class.java).apply {
                if (section != null) putExtra(EXTRA_SECTION, section)
            }
    }
}
