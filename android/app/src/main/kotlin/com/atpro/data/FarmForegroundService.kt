package com.atpro.data

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.atpro.MainActivity
import com.atpro.accessibility.TikTokAccessibilityService
import com.atpro.network.LanWebSocketServer
import kotlinx.coroutines.*

/**
 * FarmForegroundService — Fix 2
 * WS Server lifecycle gắn vào đây (start/stop trong onCreate/onDestroy)
 * → Bền hơn Application.onTerminate() vốn không được Android gọi reliably
 */
class FarmForegroundService : Service() {

    companion object {
        const val CHANNEL_ID  = "atpro_farm_channel"
        const val NOTIF_ID    = 1001
        const val ACTION_STOP = "com.atpro.STOP_FARM"

        fun buildIntent(context: Context) =
            Intent(context, FarmForegroundService::class.java)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Fix 2: WS Server gắn vào Service lifecycle — đáng tin hơn Application
        scope.launch {
            runCatching { LanWebSocketServer.start() }
                .onFailure { Log.e("FarmService", "WS start: ${it.message}") }
        }
        Log.i("FarmService", "Service created — WS server starting")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TikTokAccessibilityService.instance?.engine?.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("AT PRO đang chạy..."))
        startHeartbeat()
        return START_STICKY  // Android restart service nếu bị kill
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatJob?.cancel()
        scope.cancel()
        // WS Server stop đúng chỗ — khi service thực sự bị destroy
        LanWebSocketServer.stop()
        Log.i("FarmService", "Service destroyed — WS server stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun updateStatus(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(10_000)
                val farming = TikTokAccessibilityService.instance?.engine?.isFarming == true
                updateStatus(if (farming) "🌾 Đang farm..." else "AT PRO đang chạy...")
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            buildIntent(this).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AT PRO")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "Dừng farm", stopPi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "AT PRO Farm Status", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Trạng thái farm TikTok" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}
