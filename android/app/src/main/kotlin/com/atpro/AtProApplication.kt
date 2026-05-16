package com.atpro

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.multidex.MultiDexApplication
import com.atpro.data.LocalRepository
import com.atpro.notification.AtProNotificationManager
import kotlinx.coroutines.*

class AtProApplication : MultiDexApplication() {

    val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        // Fix 4: store context tại Application.onCreate() — không dùng ActivityThread
        // Đây là cách đúng và bền hơn ActivityThread.currentApplication()
        private lateinit var _ctx: Context
        val ctx: Context get() = _ctx

        // Convenience: lấy repo từ bất kỳ đâu mà không cần truyền context
        fun repo() = LocalRepository.getInstance(_ctx)
    }

    override fun onCreate() {
        super.onCreate()
        _ctx = applicationContext   // Gán 1 lần duy nhất, thread-safe với lateinit
        Log.i("AtProApp", "AT PRO starting — context stored")

        val repo = LocalRepository.getInstance(this)

        // Load notification config từ Room DB
        appScope.launch {
            runCatching {
                AtProNotificationManager.telegramToken  = repo.getConfig("telegram_token")
                AtProNotificationManager.telegramChatId = repo.getConfig("telegram_chat_id")
                AtProNotificationManager.discordWebhook = repo.getConfig("discord_webhook")
                AtProNotificationManager.enableTelegram = repo.getConfigBool("enable_telegram", true)
                AtProNotificationManager.enableDiscord  = repo.getConfigBool("enable_discord", true)
            }.onFailure { Log.e("AtProApp", "loadConfig: ${it.message}") }
        }

        // WS Server KHÔNG start ở đây nữa — lifecycle gắn vào FarmForegroundService
        // Lý do: onTerminate() không đáng tin; service lifecycle rõ ràng hơn

        // Dọn data cũ
        appScope.launch {
            runCatching { repo.cleanup() }
        }
    }

    // Fix 2: BỎ onTerminate() — Android không gọi nó reliably trên máy thật
    // WS server được stop trong FarmForegroundService.onDestroy() thay vào đó
}
