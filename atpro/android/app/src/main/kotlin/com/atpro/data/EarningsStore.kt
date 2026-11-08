package com.atpro.data

import android.content.Context
import java.io.File

/**
 * Một lần nhận thưởng khi hoàn thành job (v1.10.0).
 * @param kind    "LIKE" hoặc "FOLLOW"
 * @param reward  số xu server trả về khi báo cáo job
 */
data class EarningEntry(val atMs: Long, val kind: String, val reward: Double, val account: String)

/**
 * Nhật ký thu nhập theo từng job — nguồn dữ liệu cho tab "Kiếm tiền" của Thống kê.
 *
 * Lưu dạng văn bản mỗi job một dòng `thờiĐiểmMs|LOẠI|xu|tàiKhoản` trong `filesDir/earnings.log`:
 * không cần Room/migration, ghi nối thêm (O(1)), tự cắt bớt khi vượt [MAX_LINES].
 * `record()` không làm gì nếu chưa `init()` (unit test / chưa bật dịch vụ) và không bao giờ ném lỗi.
 */
object EarningsStore {
    private const val FILE      = "earnings.log"
    private const val MAX_LINES = 30_000
    private const val KEEP      = 25_000

    @Volatile private var dir: File? = null
    private val lock = Any()
    private var appendsSinceTrim = 0

    fun init(context: Context) {
        if (dir == null) dir = context.applicationContext.filesDir
    }

    fun record(kind: String, reward: Double, account: String, atMs: Long = System.currentTimeMillis()) {
        val d = dir ?: return
        if (reward.isNaN() || reward < 0.0) return
        val safeAccount = account.replace('|', '_').replace('\n', ' ')
        try {
            synchronized(lock) {
                val f = File(d, FILE)
                f.appendText("$atMs|$kind|$reward|$safeAccount\n")
                if (++appendsSinceTrim >= 200) { appendsSinceTrim = 0; trim(f) }
            }
        } catch (_: Exception) { /* ghi thu nhập là phụ — không được làm hỏng luồng chạy */ }
    }

    fun readAll(): List<EarningEntry> {
        val d = dir ?: return emptyList()
        return try {
            synchronized(lock) {
                val f = File(d, FILE)
                if (!f.exists()) emptyList() else f.useLines { lines -> lines.mapNotNull(::parseLine).toList() }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun clear() {
        val d = dir ?: return
        try { synchronized(lock) { File(d, FILE).delete() } } catch (_: Exception) {}
    }

    internal fun parseLine(line: String): EarningEntry? {
        val p = line.split('|')
        if (p.size < 4) return null
        val at = p[0].toLongOrNull() ?: return null
        val reward = p[2].toDoubleOrNull() ?: return null
        return EarningEntry(at, p[1], reward, p.drop(3).joinToString("|"))
    }

    private fun trim(f: File) {
        val lines = f.readLines()
        if (lines.size > MAX_LINES) f.writeText(lines.takeLast(KEEP).joinToString("\n", postfix = "\n"))
    }
}
