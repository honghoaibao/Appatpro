package com.atpro.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BootReceiver — nhận BOOT_COMPLETED sau khi thiết bị reboot.
 *
 * FarmForegroundService KHÔNG được auto-start lại khi boot vì:
 *   - Nó giữ WakeLock (4h) + chạy WS server liên tục → hao pin/RAM
 *   - User chưa chắc muốn farm ngay sau khi reboot
 *
 * WorkManager tự rescue các PeriodicWork sau boot → schedule farm
 * vẫn hoạt động đúng giờ mà không cần ta làm gì thêm ở đây.
 *
 * Rollback: nếu cần auto-start service lại, uncomment startForegroundService.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("BootReceiver", "Device booted — WorkManager handles schedule restoration")
        // FarmForegroundService is started only when farming begins, not on boot.
        // WorkManager's PeriodicWork reschedule is automatic and does not require this receiver.
    }
}
