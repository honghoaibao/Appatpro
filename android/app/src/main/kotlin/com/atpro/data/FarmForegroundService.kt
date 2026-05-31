package com.atpro.data

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.atpro.MainActivity
import com.atpro.accessibility.TikTokAccessibilityService
import com.atpro.network.LanWebSocketServer
import kotlinx.coroutines.*

/**
 * FarmForegroundService — Fix 2 + TASK-004
 *
 * Responsibilities:
 *   1. WS Server lifecycle (LanWebSocketServer.start/stop)
 *   2. Foreground notification (farm status visible to user)
 *   3. Heartbeat để cập nhật notification mỗi 10s
 *   4. [TASK-004] Partial Wake Lock để giữ CPU active khi màn hình tắt
 *
 * Wake Lock strategy (TASK-004 / android_rules.md §OEM compatibility):
 *   - PARTIAL_WAKE_LOCK: CPU tiếp tục chạy, màn hình tắt được
 *   - Acquire trong onStartCommand() sau khi startForeground()
 *   - Release trong onDestroy() — bắt buộc, không được skip
 *   - Timeout: 4 giờ — safety net nếu service bị kill mà onDestroy không chạy
 *
 * Notification channel: CHANNEL_ID = "atpro_farm_channel" (created by this service).
 * AtProApplication also creates "atpro_farm", "atpro_alert", "atpro_done" for
 * push notifications. These are separate, intentional channels.
 */
class FarmForegroundService : Service() {

    companion object {
        const val CHANNEL_ID  = "atpro_farm_channel"
        const val NOTIF_ID    = 1001
        const val ACTION_STOP = "com.atpro.STOP_FARM"

        /** Wake lock timeout: 4 giờ — đủ cho một phiên farm dài */
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L

        fun buildIntent(context: Context) =
            Intent(context, FarmForegroundService::class.java)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null

    /**
     * Partial wake lock — giữ CPU active khi màn hình tắt.
     * null khi service chưa start hoặc đã destroy.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        // Channel MUST exist before startForeground() is called.
        // createNotificationChannel() is idempotent — safe to call multiple times.
        createNotificationChannel()
        scope.launch {
            runCatching { LanWebSocketServer.start(applicationContext) }
                .onFailure { Log.e("FarmService", "WS start: ${it.message}") }
        }
        Log.i("FarmService", "Service created — WS server starting")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TikTokAccessibilityService.instance?.engine?.stop()
            releaseWakeLock()
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14 (API 34) với targetSdk=34: startForeground() BẮT BUỘC phải truyền
        // foregroundServiceType khớp với khai báo trong manifest (foregroundServiceType="dataSync").
        // Nếu không truyền → throw exception trên main thread → crash toàn bộ process.
        // ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC và 3-param startForeground() có từ API 29.
        //
        // FIX: bọc startForeground() trong try-catch riêng vì nó chạy NGOÀI try-catch của
        // MainActivity (service lifecycle chạy async sau khi startForegroundService() return).
        // Nếu startForeground() fail (VD: ForegroundServiceStartNotAllowedException trên API 34
        // khi app bị hệ thống restrict) → stopSelf() thay vì crash toàn bộ process.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    buildNotification("AT PRO đang chạy..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIF_ID, buildNotification("AT PRO đang chạy..."))
            }
        } catch (e: Exception) {
            Log.e("FarmService", "startForeground failed — stopping service: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        startHeartbeat()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatJob?.cancel()
        scope.cancel()
        releaseWakeLock()          // TASK-004: luôn release trước khi destroy
        LanWebSocketServer.stop()
        Log.i("FarmService", "Service destroyed — WS stopped, wake lock released")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Dipanggil ketika người dùng xóa app khỏi Recent Apps.
     *
     * Nếu không đang farm → dừng service để không còn hiện thông báo "AT PRO đang chạy".
     * Nếu đang farm → service tiếp tục chạy (WakeLock giữ CPU, farm hoàn tất).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val farming = TikTokAccessibilityService.instance?.engine?.isFarming == true
        if (!farming) {
            Log.i("FarmService", "onTaskRemoved: không đang farm — dừng service")
            releaseWakeLock()
            stopSelf()
        }
    }

    fun updateStatus(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    // ── Wake lock ─────────────────────────────────────────────

    /**
     * Acquire PARTIAL_WAKE_LOCK với timeout để giữ CPU active.
     *
     * Idempotent: gọi nhiều lần không duplicate lock.
     * Dùng acquire(timeout) thay acquire() để tránh battery drain
     * nếu service không được destroy đúng cách.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return   // đã giữ — không duplicate
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "atpro:farmWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            Log.i("FarmService", "Wake lock acquired (timeout ${WAKE_LOCK_TIMEOUT_MS / 3600_000}h)")
        } catch (e: Exception) {
            // Không crash nếu wake lock fail — farm tiếp tục nhưng có thể bị kill
            Log.w("FarmService", "Wake lock acquire failed: ${e.message}")
        }
    }

    /**
     * Release wake lock nếu đang giữ.
     * Idempotent — gọi nhiều lần an toàn.
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            Log.i("FarmService", "Wake lock released")
        } catch (e: Exception) {
            Log.w("FarmService", "Wake lock release failed: ${e.message}")
        }
    }

    // ── Heartbeat ─────────────────────────────────────────────

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            // Tunggu sampai farming benar-benar mulai sebelum memantau penghentian.
            // Ini menghindari service berhenti sendiri sebelum engine sempat set isFarming=true.
            var hadFarming = false

            while (isActive) {
                delay(10_000)
                val farming = TikTokAccessibilityService.instance?.engine?.isFarming == true

                if (farming) hadFarming = true

                // Nếu sudah pernah farming (hadFarming=true) tapi sekarang sudah selesai
                // → dừng service: farm đã hoàn tất, không cần giữ notification nữa.
                if (hadFarming && !farming) {
                    Log.i("FarmService", "Heartbeat: farm đã kết thúc — tự dừng service")
                    releaseWakeLock()
                    stopSelf()
                    return@launch
                }

                updateStatus(if (farming) "FARM: Đang farm..." else "AT PRO đang chạy...")
            }
        }
    }

    // ── Notification ──────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            buildIntent(this).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID, "AT PRO Farm Status", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Trạng thái farm TikTok" }
        nm.createNotificationChannel(ch)
    }
}
