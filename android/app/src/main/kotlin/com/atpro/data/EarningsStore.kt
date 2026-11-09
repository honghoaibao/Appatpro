package com.atpro.data

import android.content.Context
import java.io.File

data class EarningEntry(val atMs: Long, val kind: String, val reward: Double, val account: String)

/**
 * Compact append-only earnings journal. Cloud sync reads only this bounded local file.
 */
object EarningsStore {
    private const val FILE = "earnings.log"
    private const val MAX_LINES = 30_000
    private const val KEEP = 25_000

    @Volatile private var dir: File? = null
    @Volatile private var appContext: Context? = null
    private val lock = Any()
    private var appendsSinceTrim = 0

    fun init(context: Context) {
        val app = context.applicationContext
        if (dir == null) dir = app.filesDir
        appContext = app
    }

    private fun markBackupDirty() {
        appContext?.let { com.atpro.backup.BackupStateStore.markDirty(it) }
    }

    /** Replace the journal during restore without scheduling a second upload. */
    fun replaceAll(entries: List<EarningEntry>) {
        val d = dir ?: return
        try {
            synchronized(lock) {
                val lines = entries.takeLast(KEEP).map { entry ->
                    val safeAccount = entry.account.replace('|', '_').replace('\n', ' ')
                    "${entry.atMs}|${entry.kind}|${entry.reward}|$safeAccount"
                }
                val content = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
                File(d, FILE).writeText(content)
                appendsSinceTrim = 0
            }
        } catch (_: Exception) {
            // Restore is best-effort for this secondary statistics file.
        }
    }

    fun record(kind: String, reward: Double, account: String, atMs: Long = System.currentTimeMillis()) {
        val d = dir ?: return
        if (reward.isNaN() || reward < 0.0) return
        val safeAccount = account.replace('|', '_').replace('\n', ' ')
        try {
            synchronized(lock) {
                val file = File(d, FILE)
                file.appendText("$atMs|$kind|$reward|$safeAccount\n")
                if (++appendsSinceTrim >= 200) {
                    appendsSinceTrim = 0
                    trim(file)
                }
                markBackupDirty()
            }
        } catch (_: Exception) {
            // Earnings are auxiliary and must never interrupt the automation flow.
        }
    }

    fun readAll(): List<EarningEntry> {
        val d = dir ?: return emptyList()
        return try {
            synchronized(lock) {
                val file = File(d, FILE)
                if (!file.exists()) emptyList()
                else file.useLines { lines -> lines.mapNotNull(::parseLine).toList() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear() {
        val d = dir ?: return
        try {
            synchronized(lock) {
                if (File(d, FILE).delete()) markBackupDirty()
            }
        } catch (_: Exception) {}
    }

    internal fun parseLine(line: String): EarningEntry? {
        val parts = line.split('|')
        if (parts.size < 4) return null
        val at = parts[0].toLongOrNull() ?: return null
        val reward = parts[2].toDoubleOrNull() ?: return null
        return EarningEntry(at, parts[1], reward, parts.drop(3).joinToString("|"))
    }

    private fun trim(file: File) {
        val lines = file.readLines()
        if (lines.size > MAX_LINES) {
            file.writeText(lines.takeLast(KEEP).joinToString("\n", postfix = "\n"))
        }
    }
}
