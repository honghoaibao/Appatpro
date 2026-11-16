package com.atpro.sync

import com.atpro.notification.NotifText

/**
 * v1.12.6 — Trạng thái đồng bộ Supabase hiển thị cho người dùng. Thuần Kotlin, không phụ thuộc Android
 * nên test được bằng JUnit (`SyncStatusTest`). Phần lưu trữ nằm ở [SyncStatusStore].
 */

/** Mã `SyncWorker` trả về qua `Data`. Một nơi duy nhất để worker, bộ lưu trữ và test dùng chung. */
object SyncStatusCodes {
    const val OUTPUT_STATUS = "sync_status"
    const val OUTPUT_PENDING_COUNT = "pending_count"
    const val OUTPUT_CONFLICT_DETAILS = "conflict_details"

    /** Đầu vào của lần chạy do người dùng bấm "Đồng bộ ngay": chạy cả khi không có thay đổi chờ gửi. */
    const val INPUT_MANUAL = "manual"
    /** Automatic initial/periodic check, including an empty local Outbox. */
    const val INPUT_FORCE_REMOTE_CHECK = "force_remote_check"

    const val APPLICATION_UNAVAILABLE = "application_unavailable"
    const val SIGNED_OUT = "signed_out"
    const val OWNER_NOT_BOUND = "owner_not_bound"
    const val NO_PENDING_DATA = "no_pending_data"
    const val EMPTY_LOCAL_NO_REMOTE_BACKUP = "empty_local_no_remote_backup"
    const val OUTBOX_HEAD_UNAVAILABLE = "outbox_head_unavailable"
    const val INVALID_OUTBOX_EVENT = "invalid_outbox_event"
    const val OVERSIZED_EVENT = "oversized_event"
    const val AUTH_OWNER_CHANGED = "auth_owner_changed"
    const val AUTH_REFRESH_FAILED = "auth_refresh_failed"
    const val SYNC_COMMITTED = "sync_committed"
    const val ACKNOWLEDGEMENT_REJECTED = "acknowledgement_rejected"
    const val REMOTE_REJECTED = "remote_rejected"
    const val MERGE_CONFLICT = "merge_conflict"
    const val CAS_REJECTED = "cas_rejected"
    const val LOCAL_SYNC_FAILED = "local_sync_failed"
}

/** Pure policy for the user-triggered sync cooldown. A clock rollback fails closed. */
object ManualSyncPolicy {
    fun canRequest(lastRequestAtMs: Long, nowMs: Long, minIntervalMs: Long): Boolean {
        if (lastRequestAtMs <= 0L || minIntervalMs <= 0L) return true
        if (nowMs < lastRequestAtMs) return false
        return nowMs - lastRequestAtMs >= minIntervalMs
    }
}

/** Kết quả cuối cùng của một lần đồng bộ (không gồm "đang thử lại" — đó là cờ riêng của [SyncStatusSnapshot]). */
enum class SyncOutcome {
    /** Chưa chạy lần nào trên máy này. */
    NEVER,
    /** Vừa gửi/nhận xong. */
    COMMITTED,
    /** Không có gì chờ gửi. */
    UP_TO_DATE,
    /** Local data is empty and no remote backup exists yet; wait for the first real mutation. */
    EMPTY_READY,
    SIGNED_OUT,
    /** Dữ liệu trên máy chưa liên kết với tài khoản đang đăng nhập. */
    NOT_LINKED,
    /** Đổi sang tài khoản khác giữa chừng — dừng, không tự gắn lại. */
    OWNER_CHANGED,
    /** Làm mới phiên đăng nhập thất bại (không phải lỗi mạng). */
    AUTH_EXPIRED,
    /** Hai thiết bị sửa cùng một mục và không thể tự gộp. */
    MERGE_CONFLICT,
    /** Máy chủ hoặc bước xác nhận từ chối. */
    REJECTED,
    /** Lỗi dữ liệu cục bộ. */
    FAILED;

    val isSuccess: Boolean get() = this == COMMITTED || this == UP_TO_DATE

    /** Cần người dùng làm gì đó, thử lại tự động không giải quyết được. */
    val needsAttention: Boolean
        get() = when (this) {
            NOT_LINKED, OWNER_CHANGED, AUTH_EXPIRED, MERGE_CONFLICT, REJECTED, FAILED -> true
            else -> false
        }

    companion object {
        fun fromStatusCode(code: String?): SyncOutcome = when (code) {
            SyncStatusCodes.SYNC_COMMITTED -> COMMITTED
            SyncStatusCodes.NO_PENDING_DATA -> UP_TO_DATE
            SyncStatusCodes.EMPTY_LOCAL_NO_REMOTE_BACKUP -> EMPTY_READY
            SyncStatusCodes.SIGNED_OUT -> SIGNED_OUT
            SyncStatusCodes.OWNER_NOT_BOUND -> NOT_LINKED
            SyncStatusCodes.AUTH_OWNER_CHANGED -> OWNER_CHANGED
            SyncStatusCodes.AUTH_REFRESH_FAILED -> AUTH_EXPIRED
            SyncStatusCodes.MERGE_CONFLICT -> MERGE_CONFLICT
            SyncStatusCodes.ACKNOWLEDGEMENT_REJECTED,
            SyncStatusCodes.REMOTE_REJECTED,
            SyncStatusCodes.CAS_REJECTED -> REJECTED
            else -> FAILED
        }
    }
}

/**
 * Ảnh chụp trạng thái để vẽ giao diện.
 *
 * @property outcome kết quả cuối cùng đã biết (giữ nguyên khi đang chờ thử lại).
 * @property retrying lần chạy gần nhất gặp lỗi tạm (mạng, máy chủ bận) và WorkManager sẽ thử lại.
 * @property runningSince mốc bắt đầu lần chạy hiện tại; 0 = không chạy.
 */
data class SyncStatusSnapshot(
    val outcome: SyncOutcome = SyncOutcome.NEVER,
    val lastAttemptAt: Long = 0L,
    val lastSuccessAt: Long = 0L,
    val pending: Int = 0,
    val retrying: Boolean = false,
    val runningSince: Long = 0L,
    val conflictDetails: String? = null,
) {
    /** Tiến trình chết giữa chừng thì cờ chạy không được kẹt mãi. */
    fun isRunning(nowMs: Long): Boolean =
        runningSince > 0L && nowMs >= runningSince && nowMs - runningSince < RUNNING_STALE_MS

    companion object {
        const val RUNNING_STALE_MS = 3 * 60_000L
    }
}

object SyncStatusText {

    /** "vừa xong", "5 phút trước", "3 giờ trước", "2 ngày trước". */
    fun relative(nowMs: Long, thenMs: Long): String {
        val diff = (nowMs - thenMs).coerceAtLeast(0L)
        val minutes = diff / 60_000L
        return when {
            diff < 45_000L -> "vừa xong"
            minutes < 60L -> "${maxOf(minutes, 1L)} phút trước"
            minutes < 24L * 60L -> "${minutes / 60L} giờ trước"
            else -> "${minutes / (24L * 60L)} ngày trước"
        }
    }

    fun headline(s: SyncStatusSnapshot, nowMs: Long): String = when {
        s.isRunning(nowMs) -> "Đang đồng bộ"
        s.retrying -> "Chờ thử lại"
        else -> when (s.outcome) {
            SyncOutcome.NEVER -> "Chưa đồng bộ"
            SyncOutcome.EMPTY_READY -> "Chưa có dữ liệu"
            SyncOutcome.COMMITTED, SyncOutcome.UP_TO_DATE -> "Đã đồng bộ"
            SyncOutcome.SIGNED_OUT -> "Chưa đăng nhập"
            SyncOutcome.NOT_LINKED -> "Chưa liên kết dữ liệu"
            SyncOutcome.OWNER_CHANGED -> "Sai tài khoản"
            SyncOutcome.AUTH_EXPIRED -> "Phiên đăng nhập hết hạn"
            SyncOutcome.MERGE_CONFLICT -> "Dữ liệu xung đột"
            SyncOutcome.REJECTED -> "Máy chủ từ chối"
            SyncOutcome.FAILED -> "Đồng bộ lỗi"
        }
    }

    fun detail(s: SyncStatusSnapshot, nowMs: Long): String {
        val parts = ArrayList<String>(3)
        if (s.isRunning(nowMs)) {
            parts += "Đang gửi và nhận dữ liệu"
        } else {
            val reason = when {
                s.retrying -> "Mạng hoặc máy chủ chưa sẵn sàng"
                else -> when (s.outcome) {
                    SyncOutcome.EMPTY_READY -> "Thiết bị chưa có dữ liệu cần sao lưu"
                    SyncOutcome.NOT_LINKED -> "Liên kết dữ liệu với tài khoản này để bắt đầu"
                    SyncOutcome.OWNER_CHANGED -> "Dữ liệu trên máy thuộc tài khoản khác"
                    SyncOutcome.AUTH_EXPIRED -> "Đăng xuất rồi đăng nhập lại"
                    SyncOutcome.MERGE_CONFLICT -> "Hai thiết bị cùng sửa một mục"
                    SyncOutcome.REJECTED -> "Thử lại sau, dữ liệu trên máy vẫn giữ nguyên"
                    SyncOutcome.FAILED -> "Dữ liệu trên máy vẫn giữ nguyên"
                    else -> null
                }
            }
            if (reason != null) parts += reason
            if (s.outcome == SyncOutcome.MERGE_CONFLICT) {
                s.conflictDetails?.takeIf { it.isNotBlank() }?.let(parts::add)
            }
            if (s.lastSuccessAt > 0L) parts += "Lần cuối ${relative(nowMs, s.lastSuccessAt)}"
            else if (s.outcome == SyncOutcome.NEVER && reason == null) parts += "Chưa có lần nào"
        }
        if (s.pending > 0) parts += "${s.pending} thay đổi chờ gửi"
        return parts.joinToString(" · ")
    }

    /**
     * Nội dung thông báo hệ thống. [manual] = người dùng vừa bấm "Đồng bộ ngay" nên kết quả thành công cũng được báo.
     */
    fun notification(outcome: SyncOutcome, manual: Boolean): NotifText = when (outcome) {
        SyncOutcome.COMMITTED, SyncOutcome.UP_TO_DATE ->
            if (manual) NotifText("Đã đồng bộ", "Dữ liệu trên máy khớp với đám mây")
            else NotifText("Đồng bộ đã ổn định", "Dữ liệu trên máy khớp với đám mây")
        SyncOutcome.NOT_LINKED ->
            NotifText("Chưa liên kết dữ liệu", "Vào Cài đặt → Sao lưu dữ liệu để liên kết với tài khoản này")
        SyncOutcome.OWNER_CHANGED ->
            NotifText("Đồng bộ đã dừng", "Tài khoản đang đăng nhập không phải chủ của dữ liệu trên máy")
        SyncOutcome.AUTH_EXPIRED ->
            NotifText("Cần đăng nhập lại", "Phiên đăng nhập hết hạn nên chưa đồng bộ được")
        SyncOutcome.MERGE_CONFLICT ->
            NotifText("Dữ liệu xung đột", "Hai thiết bị cùng sửa một mục. Dữ liệu trên máy vẫn giữ nguyên")
        SyncOutcome.REJECTED ->
            NotifText("Máy chủ từ chối đồng bộ", "Dữ liệu trên máy vẫn giữ nguyên")
        SyncOutcome.FAILED ->
            NotifText("Đồng bộ lỗi", "Dữ liệu trên máy vẫn giữ nguyên")
        SyncOutcome.SIGNED_OUT ->
            NotifText("Chưa đăng nhập", "Đăng nhập AT PRO để đồng bộ dữ liệu")
        SyncOutcome.EMPTY_READY ->
            NotifText("Chưa có dữ liệu", "Dữ liệu mới sẽ được sao lưu tự động")
        SyncOutcome.NEVER ->
            NotifText("Chưa đồng bộ", "Chưa có lần đồng bộ nào")
    }
}

/** Khi nào một kết quả đồng bộ đáng để báo bằng thông báo hệ thống. */
object SyncNotifyPolicy {

    /** Cùng một loại sự cố chỉ nhắc lại sau khoảng này. */
    const val PROBLEM_COOLDOWN_MS = 6 * 60 * 60_000L

    /**
     * - Người dùng bấm "Đồng bộ ngay": luôn báo kết quả (thành công hoặc sự cố).
     * - Chạy nền: chỉ báo khi vào trạng thái cần xử lý (đổi so với lần trước), và một lần khi hết sự cố.
     * - Chưa đăng nhập / chưa có lần nào / không có gì để gửi: không báo.
     */
    fun shouldNotify(previous: SyncOutcome, current: SyncOutcome, manual: Boolean): Boolean = when {
        current == SyncOutcome.NEVER || current == SyncOutcome.SIGNED_OUT ||
            current == SyncOutcome.EMPTY_READY -> manual
        manual -> true
        current.needsAttention -> current != previous
        current == SyncOutcome.COMMITTED -> previous.needsAttention
        else -> false
    }
}
