package com.atpro.tasks

/**
 * v1.12.0 — Cơ chế chọn tài khoản TikTok NHẬN NHIỆM VỤ.
 *
 * Trước đây nhiệm vụ dùng chung lựa chọn "Toàn bộ máy / Danh sách chọn" (ô gõ tay @username) của chế độ Nuôi TikTok.
 * Giờ có lựa chọn riêng, chọn trực tiếp từ danh sách tài khoản đã liên kết với nhà cung cấp (Follow4Follow):
 *   • [TaskAccountMode.AUTO]     — mọi acc đã liên kết VÀ có trên máy.
 *   • [TaskAccountMode.SELECTED] — chỉ những acc người dùng tick (vẫn phải liên kết + có trên máy).
 *
 * Thuần Kotlin (không phụ thuộc Android) → test trực tiếp bằng JUnit (`TaskAccountSelectorTest`).
 */
enum class TaskAccountMode {
    AUTO, SELECTED;

    companion object {
        /** Giá trị lạ/rỗng → AUTO (mặc định an toàn: hành vi như trước khi có lựa chọn riêng). */
        fun parse(raw: String?): TaskAccountMode = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: AUTO
    }
}

/**
 * Kết quả chọn. [matched] theo THỨ TỰ TRÊN MÁY (thứ tự trong popup chuyển tài khoản của TikTok — ít thao tác chuyển nhất),
 * mỗi phần tử là (username hiển thị trên máy, tài khoản của nhà cung cấp). Các danh sách còn lại để báo cho người dùng
 * biết vì sao một acc không được làm.
 */
data class TaskSelection(
    val matched: List<Pair<String, TaskAccount>>,
    /** Đã chọn (SELECTED) nhưng không thấy trên máy. */
    val selectedNotOnDevice: List<String>,
    /** Đã chọn (SELECTED) nhưng chưa liên kết với nhà cung cấp. */
    val selectedNotLinked: List<String>,
    /** Có trên máy, đã liên kết nhưng không được chọn (chỉ SELECTED). */
    val notSelected: List<String>,
    /** Có trên máy nhưng chưa liên kết với nhà cung cấp. */
    val deviceNotLinked: List<String>,
)

object TaskAccountSelector {

    /** @Tên, hoa/thường → khoá so khớp. */
    fun key(username: String): String = username.trim().removePrefix("@").lowercase()

    /** Chuỗi "a, @b; c" / nhiều dòng → danh sách khoá (không trùng, bỏ rỗng). */
    fun parseList(raw: String?): List<String> =
        raw.orEmpty().split(',', ';', '\n').map { key(it) }.filter { it.isNotEmpty() }.distinct()

    fun select(
        deviceAccounts: List<String>,
        linked: List<TaskAccount>,
        mode: TaskAccountMode,
        selected: Collection<String>,
    ): TaskSelection {
        val device = LinkedHashMap<String, String>()                       // key → username trên máy
        deviceAccounts.forEach { u -> val k = key(u); if (k.isNotEmpty()) device.putIfAbsent(k, u.trim().removePrefix("@")) }
        val linkedByKey = LinkedHashMap<String, TaskAccount>()
        linked.forEach { linkedByKey.putIfAbsent(key(it.username), it) }
        val selectedKeys = selected.map(::key).filter { it.isNotEmpty() }.toCollection(LinkedHashSet())

        val matched = ArrayList<Pair<String, TaskAccount>>()
        val notSelected = ArrayList<String>()
        val deviceNotLinked = ArrayList<String>()
        for ((k, name) in device) {
            val acc = linkedByKey[k]
            when {
                acc == null -> deviceNotLinked.add(name)
                mode == TaskAccountMode.SELECTED && k !in selectedKeys -> notSelected.add(name)
                else -> matched.add(name to acc)
            }
        }

        val selectedNotOnDevice = ArrayList<String>()
        val selectedNotLinked = ArrayList<String>()
        if (mode == TaskAccountMode.SELECTED) {
            for (k in selectedKeys) {
                if (k !in device) selectedNotOnDevice.add(linkedByKey[k]?.username ?: k)
                if (k !in linkedByKey) selectedNotLinked.add(device[k] ?: k)
            }
        }
        return TaskSelection(matched, selectedNotOnDevice, selectedNotLinked, notSelected, deviceNotLinked)
    }
}
