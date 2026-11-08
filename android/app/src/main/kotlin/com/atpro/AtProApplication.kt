package com.atpro

import android.content.Context
import android.util.Log
import androidx.multidex.MultiDexApplication
import com.atpro.data.LocalRepository
import com.atpro.notification.AtProNotificationManager
import com.atpro.ui.theme.AtThemeState
import kotlinx.coroutines.*

class AtProApplication : MultiDexApplication() {

    val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private lateinit var _ctx: Context
        val ctx: Context get() = _ctx
        fun repo() = LocalRepository.getInstance(_ctx)
    }

    override fun onCreate() {
        super.onCreate()
        _ctx = applicationContext
        // v1.7.0: nạp chế độ giao diện Sáng/Tối TRƯỚC mọi Activity để khung đầu tiên đã đúng màu.
        AtThemeState.load(this)
        com.atpro.ui.theme.AtWindowBackground.install(this)
        Log.i("AtProApp", "AT PRO starting")

        // Notification channels must be created early (v1.12.0: tạo ở AtProNotificationManager.ensureChannels)
        AtProNotificationManager.ensureChannels(this)

        appScope.launch {
            runCatching { AtProNotificationManager.loadConfig() }
                .onFailure { Log.e("AtProApp", "loadConfig: ${it.message}") }
        }

        appScope.launch {
            runCatching { LocalRepository.getInstance(this@AtProApplication).cleanup() }
        }
    }
}
