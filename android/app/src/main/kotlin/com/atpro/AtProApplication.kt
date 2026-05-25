package com.atpro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.multidex.MultiDexApplication
import com.atpro.data.LocalRepository
import com.atpro.notification.AtProNotificationManager
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
        Log.i("AtProApp", "AT PRO v1.0.0 starting")

        // Notification channels must be created early
        createNotificationChannels()

        val repo = LocalRepository.getInstance(this)

        appScope.launch {
            runCatching {
                AtProNotificationManager.telegramToken  = repo.getConfig("telegram_token")
                AtProNotificationManager.telegramChatId = repo.getConfig("telegram_chat_id")
                AtProNotificationManager.discordWebhook = repo.getConfig("discord_webhook")
                AtProNotificationManager.enableTelegram = repo.getConfigBool("enable_telegram", true)
                AtProNotificationManager.enableDiscord  = repo.getConfigBool("enable_discord", true)
            }.onFailure { Log.e("AtProApp", "loadConfig: ${it.message}") }
        }

        appScope.launch {
            runCatching { repo.cleanup() }
        }
    }

    // Context is available here via Application — getSystemService() is valid
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel("atpro_farm", "Trạng thái farm",
                NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Thông tin phiên farm đang chạy" }
        )
        nm.createNotificationChannel(
            NotificationChannel("atpro_alert", "Cảnh báo farm",
                NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Checkpoint, lỗi và cảnh báo" }
        )
        nm.createNotificationChannel(
            NotificationChannel("atpro_done", "Hoàn thành phiên",
                NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Thông báo khi hoàn thành nuôi tài khoản" }
        )
        Log.i("AtProApp", "Notification channels created")
    }
}
