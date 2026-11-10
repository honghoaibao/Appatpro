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
)

data class EarningsDirtyState(
    val generation: Long = 0,
    val acknowledgedGeneration: Long = 0,
) {
    val isDirty: Boolean get() = generation > acknowledgedGeneration
}

data class EarningsStoreSnapshot(
    val entries: List<EarningEntry>,
    val dirtyState: EarningsDirtyState,
)

/**
 * Earnings history remains file-backed to avoid inflating Room with per-job rows.
 *
 * Current format: v2|entryUUID|timestampMs|kind|reward|account
 * Legacy rows are migrated atomically and receive a stored UUID. A small AtomicFile
 * generation marker gives the future sync worker a durable dirty signal without
 * requiring a Room transaction for this file-backed dataset.
 */
object EarningsStore {
    private const val FILE = "earnings.log"
    private const val DIRTY_FILE = "earnings.dirty"
    private const val MAX_LINES = 30_000
    private const val KEEP = 25_000
    private const val DIRTY_FORMAT = "v1"

    @Volatile private var dir: File? = null
    private val lock = Any()
    private var appendsSinceTrim = 0

    fun init(context: Context) {
        synchronized(lock) {
            val target = context.applicationContext.filesDir
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
                )
                FileOutputStream(File(directory, FILE), true).use { output ->
                    output.write((toStorageLine(entry) + "\n").toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                    output.fd.sync()
                }

                if (++appendsSinceTrim >= 200) {
                    appendsSinceTrim = 0
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
            return EarningsStoreSnapshot(entries, readDirtyStateLocked(directory))
        }
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
    fun mergeFromRestore(restoredEntries: List<EarningEntry>) {
        val directory = dir ?: error("EarningsStore has not been initialized")
        require(restoredEntries.size <= KEEP) { "Restore earnings exceed the retention bound" }
        require(restoredEntries.all {
            it.atMs >= 0L && it.kind.isNotBlank() && it.reward.isFinite() && it.reward >= 0.0 &&
                sanitize(it.kind) == it.kind && sanitize(it.account) == it.account &&
                runCatching { UUID.fromString(it.id).toString() == it.id }.getOrDefault(false)
        }) { "Restore contains invalid earning entries" }

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
            val merged = LinkedHashMap<String, EarningEntry>()
            (current + restoredEntries).forEach { entry ->
                val previous = merged[entry.id]
                if (previous != null && previous != entry) {
                    throw IllegalStateException("Earning UUID content conflict: ${entry.id}")
                }
                merged[entry.id] = entry
            }
            val retained = merged.values.sortedWith(compareBy({ it.atMs }, { it.id })).takeLast(KEEP)
            val normalizedCurrent = current.sortedWith(compareBy({ it.atMs }, { it.id })).takeLast(KEEP)
            if (retained == normalizedCurrent) return

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
                if (file.exists() && file.length() > 0L) {
                    markDirtyLocked(directory)
                    AtomicFile(file).delete()
                }
            }
        } catch (_: Exception) {
            // Preserve best-effort local clear behavior.
        }
    }

    internal fun parseLine(line: String): EarningEntry? {
        val fields = line.split('|')
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
        "v2|${entry.id}|${entry.atMs}|${sanitize(entry.kind)}|${entry.reward}|${sanitize(entry.account)}"

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
            if (line.startsWith("v2|")) {
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
