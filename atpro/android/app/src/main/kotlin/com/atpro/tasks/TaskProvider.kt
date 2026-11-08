package com.atpro.tasks

import android.content.Context
import com.atpro.tasks.f4f.F4fAuth

/**
 * v1.4.0 — Lớp trừu tượng "nơi làm nhiệm vụ kiếm tiền" (task provider).
 * v1.6.0 — Có nhà cung cấp đầu tiên: Follow4Follow (`tasks/f4f/`).
 *
 * Thay thế toàn bộ package `golike/` (REST + WebView + token) đã bị gỡ ở v1.4.0.
 * Task mode trong `AutomationEngine` chỉ nói chuyện với interface này, không biết
 * gì về nhà cung cấp cụ thể → thêm/đổi nơi làm nhiệm vụ không phải sửa engine.
 */

/** Loại nhiệm vụ app có thể làm bằng Accessibility. */
enum class TaskJobKind { LIKE, FOLLOW }

/** Tài khoản TikTok đã liên kết với nhà cung cấp. */
data class TaskAccount(
    /** Id nội bộ do nhà cung cấp cấp (dùng khi gọi API). */
    val id: String,
    /** @username TikTok — dùng để khớp với tài khoản đang đăng nhập trên thiết bị. */
    val username: String,
    /** Dữ liệu riêng của nhà cung cấp cần gửi lại khi gọi API (opaque với engine). */
    val providerData: String = "",
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
        /**
         * v1.12.0 — job đã bị xoá/hết hạn phía server (vd "Không tìm thấy dữ liệu" khi báo complete/skip):
         * báo lại hay skip đều vô ích → engine bỏ job đó, không gọi skip, sang job kế.
         */
        val jobGone: Boolean = false,
    ) : TaskResult<Nothing>()
}

interface TaskProvider {
    /** Tên hiển thị trong log/UI. */
    val displayName: String

    /** Danh sách tài khoản TikTok đã liên kết. Gọi 1 lần đầu mỗi phiên task → provider reset trạng thái phiên. */
    suspend fun listAccounts(): TaskResult<List<TaskAccount>>

    /**
     * Lấy nhiệm vụ kế tiếp cho [account], chỉ trong các loại [kinds];
     * `Success(null)` = hiện không có nhiệm vụ (xem [noJobReason]).
     */
    suspend fun nextJob(account: TaskAccount, kinds: Set<TaskJobKind>): TaskResult<TaskJob?>

    /**
     * v1.6.0 — Engine gọi NGAY TRƯỚC khi mở link nhiệm vụ. Follow4Follow dùng để báo
     * `mark-start` (job LIKE) và ghi mốc thời gian mở link (đợi đủ ≥5s trước khi complete).
     * Lỗi ở đây không được làm hỏng luồng — implementation tự nuốt lỗi.
     */
    suspend fun onJobOpening(account: TaskAccount, job: TaskJob) {}

    /** Báo đã làm xong; trả về phần thưởng thực nhận. */
    suspend fun complete(account: TaskAccount, job: TaskJob): TaskResult<Double>

    /** Báo bỏ qua (không làm được trên máy). */
    suspend fun skip(account: TaskAccount, job: TaskJob): TaskResult<Unit>

    /** Lý do gần nhất [nextJob] trả `Success(null)` (vd đạt giới hạn ngày) — để ghi log; "" nếu không có. */
    fun noJobReason(account: TaskAccount): String = ""

    /**
     * v1.12.0 — Nhận từng bước kỹ thuật (đổi acc, lấy job…) dưới dạng một dòng chữ ngắn để engine ghi vào nhật ký
     * (dòng chi tiết). Không bao giờ chứa token/chữ ký. `null` = tắt.
     */
    fun setTraceListener(listener: ((String) -> Unit)?) {}
}

/**
 * Điểm cấp phát provider hiện hành: Follow4Follow (singleton, dùng chung phiên đăng nhập với UI).
 * Chưa đăng nhập → provider trả lỗi xác thực (`isAuthError = true`) ở lần gọi đầu và engine dừng task.
 */
object TaskProviders {
    fun current(context: Context): TaskProvider = F4fAuth.provider(context)
}
