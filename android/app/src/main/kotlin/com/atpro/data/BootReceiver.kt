package com.atpro.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BootReceiver — khởi động lại WS server sau khi thiết bị reboot.
 * Accessibility Service cần user bật thủ công — không thể tự bật.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("BootReceiver", "Device booted — starting WS server")
        // Start WS server qua background service
        val svcIntent = Intent(context, FarmForegroundService::class.java)
        context.startForegroundService(svcIntent)
    }
}
