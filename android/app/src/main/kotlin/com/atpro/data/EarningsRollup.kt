package com.atpro.data

import java.util.UUID

/**
 * v1.13.0 — Dòng TỔNG của thu nhập cũ (bản nén). Mỗi dòng gộp mọi sự kiện của MỘT máy ([origin]) trong MỘT ngày UTC
 * ([epochDay]) cho một ([kind], [account]).
 *
 * Vì sao không bị cộng đôi khi gộp giữa các máy: dòng tổng luôn THUỘC đúng một máy (id cố định theo
 * máy+ngày+loại+tài khoản) và luôn đầy đủ cho (máy, ngày) đó — xem [EarningsRollupPolicy.compact]. Các sự kiện thô
 * của cùng (máy, ngày) bị coi là "đã được phủ" và bị loại ([EarningsRollupPolicy.dropCovered]).
 */
data class EarningRollup(
    val id: String,
    val origin: String,
    val epochDay: Long,
    val kind: String,
    val account: String,
    val coins: Double,
    val jobs: Long,
)

/** Kết quả một lần nén. [changed] = false → không có gì để ghi. */
data class EarningsCompaction(
    val keptEntries: List<EarningEntry>,
    val rollups: List<EarningRollup>,
    val changed: Boolean,
)

/**
 * Lõi thuần (không Android) của việc nén thu nhập: kiểm thử được bằng JVM.
 *
 * Ngày được tính theo UTC để mọi máy cùng cho ra cùng một (máy, ngày) bất kể múi giờ. Dòng tổng chỉ dùng cho
 * tổng toàn thời gian / theo tài khoản; biểu đồ theo ngày/giờ (≤ 60 ngày) vẫn đọc từ dòng thô.
 */
object EarningsRollupPolicy {
    const val MS_PER_DAY = 86_400_000L

    /** Dòng thô trong khoảng này luôn được giữ (khớp biểu đồ 60 ngày). */
    const val RAW_WINDOW_DAYS = 60L
    /** Chỉ nén ngày đã cũ ít nhất chừng này (tránh nén ngày còn đang ghi). */
    const val MIN_COMPACT_AGE_DAYS = 2L
    /** Quá ngưỡng này thì nén thêm cả ngày còn trong cửa sổ 60 ngày (từ ngày cũ nhất) cho tới [COMPACT_TARGET_RAW]. */
    const val COMPACT_TRIGGER_RAW = 20_000
    const val COMPACT_TARGET_RAW = 15_000
    /** Giới hạn số dòng tổng do máy này giữ; chạm mức này thì ngừng nén thêm (dòng thô vẫn bị cắt như trước). */
    const val LOCAL_ROLLUP_CAP = 50_000
    /** Trần cứng cho một snapshot (đã gộp nhiều máy). */
    const val MAX_ROLLUPS = 100_000

    fun epochDay(atMs: Long): Long = Math.floorDiv(atMs, MS_PER_DAY)

    fun rollupId(origin: String, epochDay: Long, kind: String, account: String): String =
        UUID.nameUUIDFromBytes("rollup|$origin|$epochDay|$kind|$account".toByteArray(Charsets.UTF_8)).toString()

    /** Các cặp (máy, ngày) đã được dòng tổng phủ. */
    fun coverage(rollups: Collection<EarningRollup>): Set<Pair<String, Long>> =
        rollups.mapTo(HashSet()) { it.origin to it.epochDay }

    fun isCovered(entry: EarningEntry, coverage: Set<Pair<String, Long>>): Boolean {
        val origin = entry.origin ?: return false          // dòng cũ chưa có mã máy: không bao giờ bị phủ
        return (origin to epochDay(entry.atMs)) in coverage
    }

    /** Bỏ các dòng thô đã có dòng tổng phủ (chống đếm đôi). */
    fun dropCovered(entries: List<EarningEntry>, rollups: Collection<EarningRollup>): List<EarningEntry> {
        if (rollups.isEmpty()) return entries
        val covered = coverage(rollups)
        return entries.filterNot { isCovered(it, covered) }
    }

    /**
     * Hợp hai tập dòng tổng theo id. Cùng id (cùng máy/ngày/loại/tài khoản) → lấy bản "lớn hơn" (nhiều việc hơn,
     * rồi nhiều xu hơn): dòng tổng chỉ tăng, nên kết quả không phụ thuộc thứ tự và gộp lặp lại là idempotent.
     */
    fun mergeRollups(a: Collection<EarningRollup>, b: Collection<EarningRollup>): List<EarningRollup> {
        val byId = HashMap<String, EarningRollup>()
        for (r in a.asSequence() + b.asSequence()) {
            val prev = byId[r.id]
            byId[r.id] = if (prev == null || larger(r, prev)) r else prev
        }
        return byId.values.sortedWith(ORDER)
    }

    private fun larger(x: EarningRollup, y: EarningRollup): Boolean = when {
        x.jobs != y.jobs -> x.jobs > y.jobs
        x.coins != y.coins -> x.coins > y.coins
        else -> false
    }

    val ORDER: Comparator<EarningRollup> =
        compareBy<EarningRollup>({ it.epochDay }, { it.origin }, { it.id })

    /**
     * Nén các sự kiện thô CŨ do CHÍNH máy này tạo ([origin]) thành dòng tổng.
     *
     * - Luôn nén ngày cũ hơn [RAW_WINDOW_DAYS]; nếu dòng thô còn > [COMPACT_TRIGGER_RAW] thì nén thêm ngày cũ nhất
     *   (vẫn ≥ [MIN_COMPACT_AGE_DAYS] ngày tuổi) cho tới ≤ [COMPACT_TARGET_RAW].
     * - Mỗi (máy, ngày) được nén TRỌN VẸN trong một lần → dòng tổng luôn đầy đủ.
     * - Dòng không có mã máy hoặc của máy khác không bao giờ bị nén ở đây.
     * - Dừng khi sẽ vượt [LOCAL_ROLLUP_CAP].
     */
    fun compact(
        entries: List<EarningEntry>,
        rollups: List<EarningRollup>,
        origin: String,
        nowMs: Long,
    ): EarningsCompaction {
        val effective = dropCovered(entries, rollups)
        val today = epochDay(nowMs)
        val ageLimit = today - RAW_WINDOW_DAYS          // ngày < ageLimit: luôn nén
        val youngest = today - MIN_COMPACT_AGE_DAYS     // ngày > youngest: không nén

        val ownByDay = effective.asSequence()
            .filter { it.origin == origin }
            .groupBy { epochDay(it.atMs) }
            .filterKeys { it <= youngest }
            .toSortedMap()
        if (ownByDay.isEmpty()) {
            return EarningsCompaction(effective, rollups, changed = effective.size != entries.size)
        }

        val chosen = LinkedHashSet<Long>()
        var remaining = effective.size
        val overTrigger = effective.size > COMPACT_TRIGGER_RAW
        // Duyệt tăng dần theo ngày: ngày đủ cũ luôn nén; ngày trẻ hơn chỉ nén khi đang quá ngưỡng và chưa về mức đích.
        for ((day, list) in ownByDay) {
            val aged = day < ageLimit
            val pressure = overTrigger && remaining > COMPACT_TARGET_RAW
            if (!aged && !pressure) break
            chosen += day
            remaining -= list.size
        }
        if (chosen.isEmpty()) {
            return EarningsCompaction(effective, rollups, changed = effective.size != entries.size)
        }

        val created = ArrayList<EarningRollup>()
        val compactedIds = HashSet<String>()
        var capacity = LOCAL_ROLLUP_CAP - rollups.size
        for (day in chosen) {
            val groups = ownByDay.getValue(day).groupBy { it.kind to it.account }
            if (groups.size > capacity) break                 // không nén dở một ngày
            capacity -= groups.size
            for ((key, list) in groups) {
                created += EarningRollup(
                    id = rollupId(origin, day, key.first, key.second),
                    origin = origin,
                    epochDay = day,
                    kind = key.first,
                    account = key.second,
                    coins = list.sumOf { it.reward },
                    jobs = list.size.toLong(),
                )
            }
            ownByDay.getValue(day).mapTo(compactedIds) { it.id }
        }
        if (created.isEmpty()) {
            return EarningsCompaction(effective, rollups, changed = effective.size != entries.size)
        }

        val kept = effective.filterNot { it.id in compactedIds }
        return EarningsCompaction(kept, mergeRollups(rollups, created), changed = true)
    }
}
