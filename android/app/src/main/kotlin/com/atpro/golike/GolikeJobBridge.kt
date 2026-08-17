package com.atpro.golike

import android.content.Context
import android.content.Intent
import android.util.Log
import com.atpro.ui.golike.GolikeJobWebActivity
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference

/**
 * v1.3.3 (bổ sung) — Kết quả bấm "Nhận Job ngay" trên trang Golike.
 *
 * Thay thế `TikTokJobDto` (lấy qua REST API) — giờ dữ liệu job được ĐỌC TỪ
 * MÀN HÌNH (trang "Chi tiết" sau khi nhận job), không còn parse JSON response.
 */
sealed class GolikeJobReceiveResult {
    /** Không bấm được nút "Nhận Job ngay" (không có job khả dụng lúc này). */
    object None : GolikeJobReceiveResult()

    /** Bấm được nút nhưng không đi tới được trang chi tiết (lỗi mạng/UI đổi). */
    data class Failed(val reason: String) : GolikeJobReceiveResult()

    /**
     * Nhận job thành công, đã ở trang "Chi tiết".
     * @param type "like" | "follow" | "unknown" — đọc từ tiêu đề trang chi tiết.
     * @param jobIdLabel Chuỗi "Job Id: xxxxx" hiển thị trên trang (để log/đối chiếu).
     */
    data class Received(val type: String, val jobIdLabel: String) : GolikeJobReceiveResult()
}

/** v1.3.3 (bổ sung) — Kết quả sau khi bấm "Hoàn thành" trên trang chi tiết job. */
sealed class GolikeJobReportResult {
    /** Dialog "Thành công" xuất hiện, đã bấm OK. */
    object Success : GolikeJobReportResult()

    /** Dialog "Lỗi" xuất hiện → đã tự bấm OK → Báo lỗi → cuộn → Gửi báo cáo → OK. */
    object ErrorReported : GolikeJobReportResult()

    /** Không xác định được dialog nào xuất hiện (timeout / UI đổi). */
    object Unknown : GolikeJobReportResult()
}

/**
 * GolikeJobBridge — cầu nối giữa `AutomationEngine` (coroutine nền chạy trong
 * `TikTokAccessibilityService`) và `GolikeJobWebActivity` (WebView giả lập
 * người dùng thật thao tác trên `app.golike.net/jobs/tiktok`).
 *
 * Vì sao cần bridge riêng thay vì gọi thẳng activity:
 *   `AccessibilityService` không có `startActivityForResult()` — không thể
 *   "chờ kết quả" theo cách thông thường của Activity. Bridge này dùng
 *   `WeakReference` tới activity đang sống (nếu có) + polling (`delay` ngắn)
 *   để chờ activity attach/render xong, thay cho `CompletableDeferred` gắn
 *   trực tiếp vào Intent — đơn giản hơn và không rò rỉ callback nếu activity
 *   bị hệ thống kill giữa chừng (khi đó `activityRef.get()` chỉ đơn thuần trả
 *   null, vòng lặp `withActivity` tự timeout thay vì treo mãi).
 *
 * Vòng đời: 1 activity instance DUY NHẤT cho cả phiên làm task (khai báo
 * `android:launchMode="singleTask"` trong Manifest) — `bringToFront()` gọi
 * lại `startActivity()` với `FLAG_ACTIVITY_REORDER_TO_FRONT` để đưa activity
 * đã tồn tại lên trước (kích hoạt `onNewIntent`, KHÔNG tạo lại `onCreate`),
 * giữ nguyên trạng thái đã chọn tài khoản + WebView đang ở đúng trang.
 */
object GolikeJobBridge {

    private const val TAG = "GolikeJobBridge"

    @Volatile
    private var activityRef: WeakReference<GolikeJobWebActivity>? = null

    /** Gọi từ `GolikeJobWebActivity.onCreate()`/`onResume()`. */
    fun attach(activity: GolikeJobWebActivity) {
        activityRef = WeakReference(activity)
    }

    /** Gọi từ `GolikeJobWebActivity.onDestroy()`. */
    fun detach(activity: GolikeJobWebActivity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    /**
     * Mở `GolikeJobWebActivity` nếu chưa có instance nào đang sống, hoặc đưa
     * instance hiện có lên foreground. Trả false nếu quá timeout mà activity
     * vẫn chưa sẵn sàng (trang chưa tải xong lần đầu).
     */
    suspend fun ensureOpen(context: Context, timeoutMs: Long = 15_000L): Boolean {
        val existing = activityRef?.get()
        if (existing != null && !existing.isFinishing) {
            bringToFront(context)
        } else {
            val intent = Intent(context, GolikeJobWebActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            context.startActivity(intent)
        }
        return waitAttached(timeoutMs)
    }

    /** Đưa activity đang sống (nếu có) lên foreground mà KHÔNG tạo instance mới. */
    fun bringToFront(context: Context) {
        val intent = Intent(context, GolikeJobWebActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        context.startActivity(intent)
    }

    /** Đóng hẳn activity — gọi khi kết thúc toàn bộ phiên làm task Golike. */
    fun close() {
        activityRef?.get()?.finish()
        activityRef = null
    }

    /**
     * v1.3.3 (bổ sung 3) — Kiểm tra WebView Golike còn "sống" và đã sẵn sàng
     * (đã tải xong trang lần đầu) hay chưa — dùng làm điều kiện short-circuit
     * cho cơ chế tự khởi động lại (xem `AutomationEngine.ensureGolikeReady()`).
     * Trả false nếu: chưa từng mở, activity đã bị hệ thống kill (WeakReference
     * rỗng), đang finish, hoặc trang chưa tải xong.
     */
    fun isAlive(): Boolean {
        val act = activityRef?.get()
        return act != null && !act.isFinishing && act.isPageReady
    }

    /**
     * Bước 1 — chọn acc ĐẦU DANH SÁCH trong dropdown "Tài khoản kiếm thưởng".
     * v1.3.3 (bổ sung 2) — acc đầu danh sách LUÔN là acc TikTok đang đăng
     * nhập trên thiết bị (xác nhận qua kiểm thử thực tế) — không cần dò/so
     * khớp theo username, tức không cần đọc hồ sơ TikTok trước.
     * `\[expectedLabel\]` chỉ dùng để log đối chiếu debug, không ảnh hưởng click.
     */
    suspend fun selectAccount(expectedLabel: String): Boolean =
        withActivity(default = false) { it.selectAccount(expectedLabel) }

    /** Bước 2 — bấm "Nhận Job ngay" + xử lý popup xác nhận + đọc trang chi tiết. */
    suspend fun receiveJob(): GolikeJobReceiveResult =
        withActivity(default = GolikeJobReceiveResult.Failed("GolikeJobWebActivity không sẵn sàng")) {
            it.receiveJob()
        }

    /** Bước 3 — bấm nút "TikTok" trên trang chi tiết để mở link nhiệm vụ. */
    suspend fun clickTikTokButton(): Boolean =
        withActivity(default = false) { it.clickTikTokButton() }

    /** Job không khớp cấu hình loại job (LIKE/FOLLOW) — thoát ra, để job tự hết hạn. */
    suspend fun abandonJob(): Boolean =
        withActivity(default = false) { it.abandonJob() }

    /** Bấm "Hoàn thành" + đọc dialog kết quả (Thành công / Lỗi) + xử lý tương ứng. */
    suspend fun completeJob(): GolikeJobReportResult =
        withActivity(default = GolikeJobReportResult.Unknown) { it.completeJob() }

    private suspend fun <T> withActivity(
        default: T,
        timeoutMs: Long = 12_000L,
        action: suspend (GolikeJobWebActivity) -> T,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val act = activityRef?.get()
            if (act != null && !act.isFinishing) return action(act)
            delay(200L)
        }
        // v1.3.3 (bổ sung 4) — log khi KHÔNG tìm được activity còn sống trong
        // suốt timeout — dấu hiệu trực tiếp của bug "tool bị dừng, không thao
        // tác" (activity đã chết/bị kill nhưng không ai gọi lại ensureOpen()).
        Log.w(TAG, "withActivity: không có GolikeJobWebActivity sống sau ${timeoutMs}ms — trả về default")
        return default
    }

    private suspend fun waitAttached(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val act = activityRef?.get()
            if (act != null && !act.isFinishing && act.isPageReady) return true
            delay(200L)
        }
        Log.w(TAG, "waitAttached: GolikeJobWebActivity chưa sẵn sàng sau ${timeoutMs}ms")
        return false
    }
}
