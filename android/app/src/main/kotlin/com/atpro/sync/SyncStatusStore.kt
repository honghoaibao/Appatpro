package com.atpro.sync

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v1.12.6 — Trạng thái đồng bộ Supabase dùng chung cho `SyncWorker` (ghi) và màn Cài đặt (đọc).
 *
 * Chỉ lưu thông tin hiển thị (kết quả, mốc thời gian, số thay đổi chờ). Không có token, email hay dữ liệu người dùng.
 * Cờ "đang chạy" không bền: tiến trình chết giữa chừng thì [SyncStatusSnapshot.isRunning] tự hết hạn.
 */
object SyncStatusStore {

    private const val PREFS = "atpro_sync_status"
    private const val K_OUTCOME = "outcome"
    private const val K_LAST_ATTEMPT = "last_attempt"
    private const val K_LAST_MANUAL_REQUEST = "last_manual_request"
    private const val K_LAST_SUCCESS = "last_success"
    private const val K_PENDING = "pending"
    private const val K_RETRYING = "retrying"
    private const val K_CONFLICT_DETAILS = "conflict_details"

    private val _state = MutableStateFlow(SyncStatusSnapshot())
    val state: StateFlow<SyncStatusSnapshot> = _state

    @Volatile private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val outcome = runCatching { SyncOutcome.valueOf(p.getString(K_OUTCOME, null) ?: "") }
            .getOrDefault(SyncOutcome.NEVER)
        _state.value = SyncStatusSnapshot(
            outcome = outcome,
            lastAttemptAt = p.getLong(K_LAST_ATTEMPT, 0L),
            lastSuccessAt = p.getLong(K_LAST_SUCCESS, 0L),
            pending = p.getInt(K_PENDING, 0).coerceAtLeast(0),
            retrying = p.getBoolean(K_RETRYING, false),
            runningSince = 0L,
            conflictDetails = if (outcome == SyncOutcome.MERGE_CONFLICT) {
                p.getString(K_CONFLICT_DETAILS, null)?.take(BackupMergeConflictSummary.MAX_SUMMARY_LENGTH)
            } else null,
        )
    }

    /** Timestamp of the last accepted manual request, independent of worker completion. */
    @Synchronized
    fun lastManualRequestAt(): Long = prefs?.getLong(K_LAST_MANUAL_REQUEST, 0L) ?: 0L

    /** Persist immediately so queued/offline requests also respect the cooldown. */
    @Synchronized
    fun markManualRequest(nowMs: Long) {
        // requestManual runs on Dispatchers.IO; commit prevents an offline queued request
        // from losing its cooldown window if the app process is terminated immediately.
        prefs?.edit()?.putLong(K_LAST_MANUAL_REQUEST, nowMs)?.commit()
    }

    /** Một lần chạy bắt đầu (hoặc vừa được xếp lịch theo yêu cầu người dùng). */
    @Synchronized
    fun markRunning(nowMs: Long = System.currentTimeMillis()) {
        _state.value = _state.value.copy(runningSince = nowMs)
    }

    /** Lần chạy bị huỷ giữa chừng: bỏ cờ đang chạy, giữ nguyên kết quả cũ. */
    @Synchronized
    fun stopRunning() {
        _state.value = _state.value.copy(runningSince = 0L)
    }

    /**
     * Ghi kết quả của một lần chạy.
     *
     * @param outcome kết quả; `null` = lần chạy gặp lỗi tạm và sẽ được WorkManager thử lại.
     * @param pending số thay đổi còn chờ; `null` = giữ số cũ.
     */
    @Synchronized
    fun record(
        outcome: SyncOutcome?,
        pending: Int?,
        nowMs: Long = System.currentTimeMillis(),
        conflictDetails: String? = null,
    ) {
        val cur = _state.value
        val next = if (outcome == null) {
            cur.copy(lastAttemptAt = nowMs, retrying = true, runningSince = 0L, pending = pending ?: cur.pending)
        } else {
            cur.copy(
                outcome = outcome,
                lastAttemptAt = nowMs,
                lastSuccessAt = if (outcome.isSuccess) nowMs else cur.lastSuccessAt,
                pending = if (outcome.isSuccess) pending ?: 0 else pending ?: cur.pending,
                retrying = false,
                runningSince = 0L,
                conflictDetails = if (outcome == SyncOutcome.MERGE_CONFLICT) {
                    conflictDetails?.take(BackupMergeConflictSummary.MAX_SUMMARY_LENGTH)
                        ?: cur.conflictDetails
                } else null,
            )
        }
        _state.value = next
        persist(next)
    }

    /** Đăng xuất: bỏ trạng thái của tài khoản cũ. */
    @Synchronized
    fun clear() {
        _state.value = SyncStatusSnapshot()
        // Keep the device-level manual request cooldown across account sign-out.
        prefs?.edit()
            ?.remove(K_OUTCOME)
            ?.remove(K_LAST_ATTEMPT)
            ?.remove(K_LAST_SUCCESS)
            ?.remove(K_PENDING)
            ?.remove(K_RETRYING)
            ?.remove(K_CONFLICT_DETAILS)
            ?.apply()
    }

    private fun persist(s: SyncStatusSnapshot) {
        prefs?.edit()
            ?.putString(K_OUTCOME, s.outcome.name)
            ?.putLong(K_LAST_ATTEMPT, s.lastAttemptAt)
            ?.putLong(K_LAST_SUCCESS, s.lastSuccessAt)
            ?.putInt(K_PENDING, s.pending)
            ?.putBoolean(K_RETRYING, s.retrying)
            ?.putString(K_CONFLICT_DETAILS, s.conflictDetails)
            ?.apply()
    }
}
