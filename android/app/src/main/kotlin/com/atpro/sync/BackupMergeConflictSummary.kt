package com.atpro.sync

/**
 * Creates a short local-only diagnostic for a merge conflict.
 *
 * It includes only a fixed dataset label, a bounded entity key and a fixed conflict label.
 * It never includes account values, configuration values, session contents or Auth material.
 */
internal object BackupMergeConflictSummary {
    const val MAX_ITEMS = 3
    const val MAX_KEY_LENGTH = 40
    const val MAX_SUMMARY_LENGTH = 320

    fun format(conflicts: List<BackupMergeConflict>): String? {
        if (conflicts.isEmpty()) return null
        val ordered = conflicts.distinct().sortedWith(
            compareBy<BackupMergeConflict>({ it.dataset }, { it.entityKey }, { it.kind.name })
        )
        val details = ordered.take(MAX_ITEMS).map(::render)
        val remaining = ordered.size - details.size
        val suffix = if (remaining > 0) " (+$remaining mục khác)" else ""
        return (details.joinToString("; ") + suffix).take(MAX_SUMMARY_LENGTH)
    }

    private fun render(conflict: BackupMergeConflict): String {
        val dataset = when (conflict.dataset) {
            BackupTombstoneDataset.ACCOUNTS -> "Tài khoản"
            BackupTombstoneDataset.SAFE_CONFIGS -> "Cấu hình"
            BackupTombstoneDataset.SCHEDULES -> "Lịch tự động"
            "closedSessions" -> "Phiên"
            "earnings" -> "Thu nhập"
            "tombstones" -> "Dấu xóa"
            else -> "Dữ liệu"
        }
        val key = conflict.entityKey
            .filterNot(Char::isISOControl)
            .trim()
            .ifBlank { "*" }
            .take(MAX_KEY_LENGTH)
        val reason = when (conflict.kind) {
            BackupMergeConflictKind.ACCOUNT_STATUS -> "trạng thái tài khoản khác nhau"
            BackupMergeConflictKind.ACCOUNT_CREATION_TIME -> "thời điểm tạo tài khoản khác nhau"
            BackupMergeConflictKind.SESSION_CONTENT -> "nội dung phiên khác nhau"
            BackupMergeConflictKind.CONFIG_VALUE -> "giá trị cấu hình khác nhau"
            BackupMergeConflictKind.SCHEDULE_DEFINITION -> "định nghĩa lịch khác nhau"
            BackupMergeConflictKind.EARNING_CONTENT -> "nội dung thu nhập khác nhau"
            BackupMergeConflictKind.TOMBSTONE_ID_REUSE -> "ID dấu xóa bị trùng"
            BackupMergeConflictKind.DELETE_RECREATE_SAME_INSTALLATION -> "xóa rồi tạo lại trên cùng thiết bị"
            BackupMergeConflictKind.DUPLICATE_ENTITY_IDENTITY -> "định danh bị trùng"
            BackupMergeConflictKind.DATASET_LIMIT_EXCEEDED -> "vượt giới hạn dữ liệu"
        }
        return "$dataset [$key]: $reason"
    }
}
