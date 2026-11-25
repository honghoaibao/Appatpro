package com.atpro.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * A single earning entry returned by the F4F provider.
 * The persisted ID keeps the same logical earning identifiable across snapshot retries.
 */
data class EarningEntry(
    val atMs: Long,
    val kind: String,
    val reward: Double,
    val account: String,
    val id: String = UUID.randomUUID().toString(),
    /** v1.13.0: mã máy tạo sự kiện (null = dòng cũ trước v1.13.0 — không bao giờ bị nén). */
    val origin: String? = null,
)

data class EarningsDirtyState(
    val generation: Long = 0,
    val acknowledgedGeneration: Long = 0,
) {
    val isDirty: Boolean get() = generation > acknowledgedGeneration
}

data class EarningsStoreSnapshot(
    /** Dòng thô còn hiệu lực (đã bỏ các dòng được dòng tổng phủ). */
    val entries: List<EarningEntry>,
    val dirtyState: EarningsDirtyState,
    /** v1.13.0: dòng tổng của thu nhập cũ đã nén. */
    val rollups: List<EarningRollup> = emptyList(),
)

/**
 * Earnings history remains file-backed to avoid inflating Room with per-job rows.
 *
 * Current format: v3|entryUUID|timestampMs|kind|reward|account|originInstallUUID (v1.13.0)
 * Older rows `v2|entryUUID|timestampMs|kind|reward|account` (no origin) remain readable and are never compacted.
 * v1.13.0: sự kiện cũ do chính máy này tạo được nén thành dòng tổng trong `earnings.rollup` ([EarningsRollupPolicy]).
 * Legacy rows are migrated atomically and receive a stored UUID. A small AtomicFile
 * generation marker gives the future sync worker a durable dirty signal without
 * requiring a Room transaction for this file-backed dataset.
 */
object EarningsStore {
    private const val FILE = "earnings.log"
    private const val DIRTY_FILE = "earnings.dirty"
    private const val ROLLUP_FILE = "earnings.rollup"
    private const val ORIGIN_FILE = "earnings.origin"
    private const val MAX_LINES = 30_000
    private const val KEEP = 25_000
    private const val DIRTY_FORMAT = "v1"

    @Volatile private var dir: File? = null
    private val lock = Any()
    private var appendsSinceTrim = 0
    private var cachedOrigin: String? = null

    fun init(context: Context) {
        synchronized(lock) {
            val target = context.applicationContext.filesDir
            if (dir != target) cachedOrigin = null
            dir = target
        }
    }

    fun record(
        kind: String,
        reward: Double,
        account: String,
        atMs: Long = System.currentTimeMillis(),
    ) {
        val directory = dir ?: return
        if (!reward.isFinite() || reward < 0.0) return

        try {
            synchronized(lock) {
                initializeDirtyMarkerLocked(directory)
                if (!ensureLegacyMigratedLocked(directory)) return

                // Dirty first: a crash can produce a harmless false-positive, never a missed write.
                markDirtyLocked(directory)
                val entry = EarningEntry(
                    atMs = atMs,
                    kind = sanitize(kind),
                    reward = reward,
                    account = sanitize(account),
                    origin = originLocked(directory),
                )
                FileOutputStream(File(directory, FILE), true).use { output ->
                    output.write((toStorageLine(entry) + "\n").toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                    output.fd.sync()
                }

                if (++appendsSinceTrim >= 200) {
                    appendsSinceTrim = 0
                    runCatching { compactLocked(directory, System.currentTimeMillis()) }
                    trimLocked(File(directory, FILE))
                }
            }
        } catch (_: Exception) {
            // Earnings are auxiliary; storage failures must not crash the service.
        }
    }

    /** Consistent file contents + dirty generation under a single process lock. */
    fun readSnapshot(): EarningsStoreSnapshot {
        val directory = dir ?: return EarningsStoreSnapshot(emptyList(), EarningsDirtyState())
        synchronized(lock) {
            initializeDirtyMarkerLocked(directory)
            if (!ensureLegacyMigratedLocked(directory)) {
                throw IllegalStateException("Could not safely migrate the earnings file")
            }

            val file = File(directory, FILE)
            val entries = if (!file.exists()) {
                emptyList()
            } else {
                file.useLines { lines -> lines.mapNotNull(::parseLine).toList() }
            }
            val rollups = readRollupsLocked(directory)
            return EarningsStoreSnapshot(
                entries = EarningsRollupPolicy.dropCovered(entries, rollups),
                dirtyState = readDirtyStateLocked(directory),
                rollups = rollups,
            )
        }
    }

    fun readRollups(): List<EarningRollup> =
        runCatching { readSnapshot().rollups }.getOrDefault(emptyList())

    /**
     * v1.13.0: nén sự kiện CŨ của chính máy này thành dòng tổng. Dòng tổng được ghi TRƯỚC, dòng thô bị bỏ SAU; nếu dừng
     * giữa chừng thì dòng thô còn lại bị luật "phủ" loại khi đọc — tổng không bao giờ mất hay bị đếm đôi.
     * Trả true nếu có thay đổi (đã đánh dấu dirty để lần đồng bộ sau gửi bản tổng lên). Gọi từ nền (SyncWorker).
     */
    fun compactIfNeeded(nowMs: Long = System.currentTimeMillis()): Boolean {
        val directory = dir ?: return false
        return runCatching {
            synchronized(lock) {
                initializeDirtyMarkerLocked(directory)
                if (!ensureLegacyMigratedLocked(directory)) return@synchronized false
                compactLocked(directory, nowMs)
            }
        }.getOrDefault(false)
    }

    private fun compactLocked(directory: File, nowMs: Long): Boolean {
        val file = File(directory, FILE)
        if (!file.exists()) return false
        val rawLines = file.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
        val parsed = rawLines.map(::parseLine)
        if (parsed.any { it == null }) return false       // dòng lỗi: không đụng vào, giữ nguyên để kiểm tra
        val entries = parsed.filterNotNull()
        val rollups = readRollupsLocked(directory)
        val result = EarningsRollupPolicy.compact(entries, rollups, originLocked(directory), nowMs)
        if (!result.changed) return false

        markDirtyLocked(directory)
        if (result.rollups != rollups) writeRollupsLocked(directory, result.rollups)   // 1) tổng trước
        writeTextAtomically(file, result.keptEntries.joinToString("\n", postfix = if (result.keptEntries.isEmpty()) "" else "\n") {
            toStorageLine(it)
        })                                                                               // 2) bỏ dòng thô sau
        return true
    }

    fun readAll(): List<EarningEntry> =
        runCatching { readSnapshot().entries }.getOrDefault(emptyList())

    fun dirtyState(): EarningsDirtyState {
        val directory = dir ?: return EarningsDirtyState()
        synchronized(lock) {
            initializeDirtyMarkerLocked(directory)
            return readDirtyStateLocked(directory)
        }
    }

    fun isDirty(): Boolean = dirtyState().isDirty

    /**
     * Future sync calls this only after a committed remote snapshot.
     * Writes made after the captured generation remain dirty.
     */
    fun acknowledgeThrough(snapshotGeneration: Long): Boolean {
        val directory = dir ?: return false
        return runCatching {
            synchronized(lock) {
                initializeDirtyMarkerLocked(directory)
                val current = readDirtyStateLocked(directory)
                if (snapshotGeneration < current.acknowledgedGeneration ||
                    snapshotGeneration > current.generation
                ) {
                    false
                } else {
                    writeDirtyStateLocked(
                        directory,
                        current.copy(acknowledgedGeneration = snapshotGeneration),
                    )
                    true
                }
            }
        }.getOrDefault(false)
    }

    /**
     * Merge a staged restore into the current file by stable UUID. Replaying the same
     * journal is idempotent, and earnings appended after staging are retained.
     */
    fun mergeFromRestore(
        restoredEntries: List<EarningEntry>,
        restoredRollups: List<EarningRollup> = emptyList(),
    ) {
        val directory = dir ?: error("EarningsStore has not been initialized")
        require(restoredEntries.size <= KEEP) { "Restore earnings exceed the retention bound" }
        require(restoredEntries.all {
            it.atMs >= 0L && it.kind.isNotBlank() && it.reward.isFinite() && it.reward >= 0.0 &&
                sanitize(it.kind) == it.kind && sanitize(it.account) == it.account &&
                runCatching { UUID.fromString(it.id).toString() == it.id }.getOrDefault(false) &&
                (it.origin == null || runCatching { UUID.fromString(it.origin).toString() == it.origin }.getOrDefault(false))
        }) { "Restore contains invalid earning entries" }
        require(restoredRollups.size <= EarningsRollupPolicy.MAX_ROLLUPS) { "Restore rollups exceed the bound" }
        require(restoredRollups.all(::isValidRollup)) { "Restore contains invalid earning rollups" }

        synchronized(lock) {
            initializeDirtyMarkerLocked(directory)
            if (!ensureLegacyMigratedLocked(directory)) {
                throw IllegalStateException("Could not safely migrate the earnings file")
            }
            val currentFile = File(directory, FILE)
            val current = if (currentFile.exists()) {
                val rawLines = currentFile.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
                val parsed = rawLines.map(::parseLine)
                if (parsed.any { it == null }) {
                    throw IllegalStateException("Earnings file contains malformed rows; restore will not discard them")
                }
                parsed.filterNotNull()
            } else {
                emptyList()
            }
            val currentRollups = readRollupsLocked(directory)
            val mergedRollups = EarningsRollupPolicy.mergeRollups(currentRollups, restoredRollups)
            val merged = LinkedHashMap<String, EarningEntry>()
            (current + restoredEntries).forEach { entry ->
                val previous = merged[entry.id]
                // Dòng cũ (v2) và bản v3 của cùng một id chỉ khác ở origin → không phải xung đột nội dung.
                if (previous != null && previous.copy(origin = null) != entry.copy(origin = null)) {
                    throw IllegalStateException("Earning UUID content conflict: ${entry.id}")
                }
                merged[entry.id] = if (previous?.origin != null) previous else entry
            }
            // Dòng thô đã có dòng tổng của cùng (máy, ngày) phủ thì bỏ (chống đếm đôi).
            val effective = EarningsRollupPolicy.dropCovered(merged.values.toList(), mergedRollups)
            val retained = effective.sortedWith(compareBy({ it.atMs }, { it.id })).takeLast(KEEP)
            val normalizedCurrent = current.sortedWith(compareBy({ it.atMs }, { it.id })).takeLast(KEEP)
            val rollupsChanged = mergedRollups != currentRollups.sortedWith(EarningsRollupPolicy.ORDER)
            if (retained == normalizedCurrent && !rollupsChanged) return
            if (rollupsChanged) writeRollupsLocked(directory, mergedRollups)   // tổng trước, thô sau (xem compactIfNeeded)

            // Dirty first: if the process stops during the atomic file replacement, the
            // journal remains and a replay can safely complete the same merge.
            markDirtyLocked(directory)
            val atomic = AtomicFile(currentFile)
            var output: FileOutputStream? = null
            try {
                output = atomic.startWrite()
                val content = retained.joinToString("\n", postfix = if (retained.isEmpty()) "" else "\n") {
                    toStorageLine(it)
                }
                output.write(content.toByteArray(StandardCharsets.UTF_8))
                output.flush()
                output.fd.sync()
                atomic.finishWrite(output)
            } catch (error: Exception) {
                output?.let { atomic.failWrite(it) }
                throw error
            }
        }
    }

    fun clear() {
        val directory = dir ?: return
        try {
            synchronized(lock) {
                val file = File(directory, FILE)
                val rollupFile = File(directory, ROLLUP_FILE)
                if ((file.exists() && file.length() > 0L) || rollupFile.exists()) {
                    markDirtyLocked(directory)
                    AtomicFile(file).delete()
                    AtomicFile(rollupFile).delete()
                }
            }
        } catch (_: Exception) {
            // Preserve best-effort local clear behavior.
        }
    }

    internal fun parseLine(line: String): EarningEntry? {
        val fields = line.split('|')
        if (fields.firstOrNull() == "v3") {
            if (fields.size != 7) return null
            val id = runCatching { UUID.fromString(fields[1]).toString() }.getOrNull() ?: return null
            val at = fields[2].toLongOrNull() ?: return null
            val reward = fields[4].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
            val origin = runCatching { UUID.fromString(fields[6]).toString() }.getOrNull() ?: return null
            return EarningEntry(at, fields[3], reward, fields[5], id, origin)
        }
        if (fields.firstOrNull() == "v2") {
            if (fields.size != 6) return null
            val id = runCatching { UUID.fromString(fields[1]).toString() }.getOrNull() ?: return null
            val at = fields[2].toLongOrNull() ?: return null
            val reward = fields[4].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
            return EarningEntry(at, fields[3], reward, fields[5], id)
        }

        // Legacy four-column form. The read path migrates and persists UUIDs before backup.
        if (fields.size < 4) return null
        val at = fields[0].toLongOrNull() ?: return null
        val reward = fields[2].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val id = UUID.nameUUIDFromBytes(line.toByteArray(StandardCharsets.UTF_8)).toString()
        return EarningEntry(at, fields[1], reward, fields.drop(3).joinToString("|"), id)
    }

    private fun sanitize(value: String): String =
        value.replace('|', '_').replace('\n', ' ').replace('\r', ' ')

    private fun toStorageLine(entry: EarningEntry): String =
        if (entry.origin != null) {
            "v3|${entry.id}|${entry.atMs}|${sanitize(entry.kind)}|${entry.reward}|${sanitize(entry.account)}|${entry.origin}"
        } else {
            "v2|${entry.id}|${entry.atMs}|${sanitize(entry.kind)}|${entry.reward}|${sanitize(entry.account)}"
        }

    // ── v1.13.0: dòng tổng + mã máy ─────────────────────────────────────────────────────────────

    /** Mã máy dùng làm chủ sở hữu dòng tổng; tạo một lần rồi giữ cố định (xoá dữ liệu app = máy mới). */
    private fun originLocked(directory: File): String {
        cachedOrigin?.let { return it }
        val file = File(directory, ORIGIN_FILE)
        runCatching {
            val text = file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)?.trim()
            if (text != null) return UUID.fromString(text).toString().also { cachedOrigin = it }
        }
        val created = UUID.randomUUID().toString()
        writeTextAtomically(file, created)
        cachedOrigin = created
        return created
    }

    internal fun isValidRollup(r: EarningRollup): Boolean =
        runCatching {
            UUID.fromString(r.id).toString() == r.id &&
                UUID.fromString(r.origin).toString() == r.origin &&
                r.id == EarningsRollupPolicy.rollupId(r.origin, r.epochDay, r.kind, r.account) &&
                r.kind.isNotBlank() && sanitize(r.kind) == r.kind && sanitize(r.account) == r.account &&
                r.coins.isFinite() && r.coins >= 0.0 && r.jobs > 0L
        }.getOrDefault(false)

    internal fun rollupLine(r: EarningRollup): String =
        "r1|${r.id}|${r.origin}|${r.epochDay}|${sanitize(r.kind)}|${r.jobs}|${r.coins}|${sanitize(r.account)}"

    internal fun parseRollupLine(line: String): EarningRollup? {
        val f = line.split('|')
        if (f.size != 8 || f[0] != "r1") return null
        val rollup = EarningRollup(
            id = f[1], origin = f[2],
            epochDay = f[3].toLongOrNull() ?: return null,
            kind = f[4],
            account = f[7],
            coins = f[6].toDoubleOrNull() ?: return null,
            jobs = f[5].toLongOrNull() ?: return null,
        )
        return rollup.takeIf(::isValidRollup)
    }

    private fun readRollupsLocked(directory: File): List<EarningRollup> {
        val file = File(directory, ROLLUP_FILE)
        if (!file.exists()) return emptyList()
        // Dòng tổng hỏng bị bỏ qua (không ném): đọc thu nhập không được làm hỏng màn hình thống kê.
        return file.useLines(StandardCharsets.UTF_8) { lines -> lines.mapNotNull(::parseRollupLine).toList() }
            .let { EarningsRollupPolicy.mergeRollups(it, emptyList()) }
    }

    private fun writeRollupsLocked(directory: File, rollups: List<EarningRollup>) {
        val sorted = rollups.sortedWith(EarningsRollupPolicy.ORDER)
        writeTextAtomically(
            File(directory, ROLLUP_FILE),
            sorted.joinToString("\n", postfix = if (sorted.isEmpty()) "" else "\n") { rollupLine(it) },
        )
    }

    private fun writeTextAtomically(file: File, text: String) {
        val atomic = AtomicFile(file)
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(text.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            output?.let { atomic.failWrite(it) }
            throw error
        }
    }

    private fun initializeDirtyMarkerLocked(directory: File) {
        val marker = File(directory, DIRTY_FILE)
        if (!marker.exists()) {
            val hasExistingEntries = File(directory, FILE).let { it.exists() && it.length() > 0L }
            val initial = if (hasExistingEntries) {
                EarningsDirtyState(generation = 1, acknowledgedGeneration = 0)
            } else {
                EarningsDirtyState()
            }
            writeDirtyStateLocked(directory, initial)
        } else if (readDirtyStateOrNullLocked(directory) == null) {
            // Corrupt/missing metadata must never be interpreted as clean.
            writeDirtyStateLocked(
                directory,
                EarningsDirtyState(generation = 1, acknowledgedGeneration = 0),
            )
        }
    }

    private fun readDirtyStateLocked(directory: File): EarningsDirtyState =
        readDirtyStateOrNullLocked(directory)
            ?: EarningsDirtyState(generation = 1, acknowledgedGeneration = 0)

    private fun readDirtyStateOrNullLocked(directory: File): EarningsDirtyState? {
        return try {
            val atomic = AtomicFile(File(directory, DIRTY_FILE))
            val raw = atomic.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val fields = raw.trim().split('|')
            if (fields.size != 3 || fields[0] != DIRTY_FORMAT) return null
            val generation = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val acknowledged = fields[2].toLongOrNull()?.takeIf { it >= 0 && it <= generation } ?: return null
            EarningsDirtyState(generation, acknowledged)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDirtyStateLocked(directory: File, state: EarningsDirtyState) {
        val atomic = AtomicFile(File(directory, DIRTY_FILE))
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            val text = "$DIRTY_FORMAT|${state.generation}|${state.acknowledgedGeneration}"
            output.write(text.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            output?.let { atomic.failWrite(it) }
            throw error
        }
    }

    /** Atomically migrate legacy rows while retaining malformed lines for local inspection. */
    private fun ensureLegacyMigratedLocked(directory: File): Boolean {
        val file = File(directory, FILE)
        if (!file.exists() || file.length() == 0L) return true

        val lines = try {
            file.readLines(StandardCharsets.UTF_8)
        } catch (_: Exception) {
            return false
        }

        var changed = false
        val migrated = lines.map { line ->
            if (line.startsWith("v2|") || line.startsWith("v3|")) {
                line
            } else {
                val fields = line.split('|')
                val at = fields.getOrNull(0)?.toLongOrNull()
                val reward = fields.getOrNull(2)?.toDoubleOrNull()
                if (fields.size < 4 || at == null || reward == null ||
                    !reward.isFinite() || reward < 0.0
                ) {
                    line
                } else {
                    changed = true
                    toStorageLine(
                        EarningEntry(
                            atMs = at,
                            kind = sanitize(fields[1]),
                            reward = reward,
                            account = sanitize(fields.drop(3).joinToString("|")),
                        ),
                    )
                }
            }
        }
        if (!changed) return true

        return try {
            markDirtyLocked(directory)
            val atomic = AtomicFile(file)
            var output: FileOutputStream? = null
            try {
                output = atomic.startWrite()
                output.write(migrated.joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8))
                output.flush()
                output.fd.sync()
                atomic.finishWrite(output)
            } catch (error: Exception) {
                output?.let { atomic.failWrite(it) }
                throw error
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun markDirtyLocked(directory: File): Long {
        val current = readDirtyStateLocked(directory)
        val next = current.copy(generation = current.generation + 1L)
        writeDirtyStateLocked(directory, next)
        return next.generation
    }

    private fun trimLocked(file: File) {
        if (!file.exists()) return
        val lines = file.readLines(StandardCharsets.UTF_8)
        if (lines.size <= MAX_LINES) return

        val retained = lines.takeLast(KEEP)
        val atomic = AtomicFile(file)
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(retained.joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8))
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            output?.let { atomic.failWrite(it) }
        }
    }
}
