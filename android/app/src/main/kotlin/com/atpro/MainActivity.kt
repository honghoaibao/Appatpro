package com.atpro

import android.content.Intent
import android.os.Bundle
import com.atpro.bridge.FlutterBridge
import com.atpro.data.FarmForegroundService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

/**
 * MainActivity — Flutter v2 embedding
 * extends FlutterActivity (io.flutter.embedding.android) — KHÔNG phải FlutterActivity v1
 *
 * Tự động:
 *  1. Setup FlutterBridge (Method + Event Channel)
 *  2. Start FarmForegroundService để WS server sống
 *  3. Flutter routing bắt đầu từ /splash → /setup → /
 */
class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        FlutterBridge.setup(flutterEngine)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Khởi động foreground service ngay khi app mở
        // → WS server sẵn sàng, notification hiện
        try {
            startForegroundService(FarmForegroundService.buildIntent(this))
        } catch (e: Exception) {
            // Ignore — service sẽ được start lại khi Accessibility Service kết nối
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Không stop service khi đóng app — farm tiếp tục background
    }
}
