package com.atpro

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.atpro.ui.dashboard.DashboardActivity

/**
 * MainActivity — native Android entry point (Flutter removed, TASK-017).
 *
 * Chỉ làm 1 việc: chuyển sang DashboardActivity (Compose) rồi finish().
 *
 * FarmForegroundService KHÔNG được start ở đây vì:
 *   - Service giữ WakeLock + WS server → hao pin/RAM khi không dùng
 *   - Service chỉ nên chạy khi thật sự farm (startFarm() trong DashboardViewModel)
 *
 * Rollback: nếu cần auto-start service, thêm lại startForegroundService() trước startActivity().
 * ADR: xem revision-2026-05-24-fix-background-farm-settings.md
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Redirect sang DashboardActivity (native Compose) — no service start
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
