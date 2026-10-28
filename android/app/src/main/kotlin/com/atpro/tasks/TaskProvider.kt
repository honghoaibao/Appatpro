package com.atpro.tasks

import android.content.Context

/**
 * v1.4.0 — Lớp trừu tượng "nơi làm nhiệm vụ kiếm tiền" (task provider).
 *
 * Thay thế toàn bộ package `golike/` (REST + WebView + token) đã bị gỡ ở v1.4.0.
 * Task mode trong `AutomationEngine` chỉ nói chuyện với interface này, không biết
 * gì về nhà cung cấp cụ thể → thêm/đổi nơi làm nhiệm vụ không phải sửa engine.
 *
 * Nhà cung cấp dự kiến: Follow4Follow (https://follow4follow.net/api-docs).
 * CHƯA có implementation — xem [TaskProviders.current].
 */

/** Loại nhiệm vụ app có thể làm bằng Accessibility. */
enum class TaskJobKind { LIKE, FOLLOW }

/** Tài khoản TikTok đã liên kết với nhà cung cấp. */
data class TaskAccount(
    /** Id nội bộ do nhà cung cấp cấp (dùng khi gọi API). */
    val id: String,
    /** @username TikTok — dùng để khớp với tài khoản đang đăng nhập trên thiết bị. */
    val username: String,
)

/** Một nhiệm vụ cụ thể. */
data class TaskJob(
    val id: String,
    val kind: TaskJobKind,
    /** Link mở bằng deep link (video để like / hồ sơ để follow). */
    val link: String,
    /** Phần thưởng (đơn vị tuỳ nhà cung cấp) — chỉ để hiển thị/log. */
    val reward: Double = 0.0,
    /** Dữ liệu riêng của nhà cung cấp cần gửi lại khi báo cáo (opaque với engine). */
    val providerData: String = "",
)

/** Kết quả gọi nhà cung cấp — thay cho `GolikeResult` cũ. */
sealed class TaskResult<out T> {
    data class Success<out T>(val data: T) : TaskResult<T>()
    data class Error(
        val message: String,
        /** true = thông tin xác thực hết hạn/không hợp lệ → engine dừng task. */
        val isAuthError: Boolean = false,
        /** Số giây server yêu cầu chờ trước khi gọi lại (0 = không). */
        val cooldownSecs: Int = 0,
    ) : TaskResult<Nothing>()
}

interface TaskProvider {
    /** Tên hiển thị trong log/UI. */
    val displayName: String

    /** Danh sách tài khoản TikTok đã liên kết. */
    suspend fun listAccounts(): TaskResult<List<TaskAccount>>

    /** Lấy nhiệm vụ kế tiếp cho [account]; `Success(null)` = hiện không có nhiệm vụ. */
    suspend fun nextJob(account: TaskAccount): TaskResult<TaskJob?>

    /** Báo đã làm xong; trả về phần thưởng thực nhận. */
    suspend fun complete(account: TaskAccount, job: TaskJob): TaskResult<Double>

    /** Báo bỏ qua (không làm được trên máy). */
    suspend fun skip(account: TaskAccount, job: TaskJob): TaskResult<Unit>
}

/**
 * Điểm cấp phát provider hiện hành.
 *
 * v1.4.0: trả `null` — Follow4Follow chưa được tích hợp vì chưa đọc được tài liệu API
 * (trang `follow4follow.net/api-docs` chặn truy cập tự động). Khi có tài liệu:
 *   1. Viết `Follow4FollowProvider : TaskProvider` trong package này.
 *   2. Trả instance ở đây.
 *   3. Đặt `AppConstants.TASKS_ENABLED = true`.
 */
object TaskProviders {
    @Suppress("UNUSED_PARAMETER")
    fun current(context: Context): TaskProvider? = null
}
