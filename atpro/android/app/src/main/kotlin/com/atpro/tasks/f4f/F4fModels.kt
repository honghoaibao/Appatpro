package com.atpro.tasks.f4f

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mô hình dữ liệu các tính năng ví/xếp hạng/rút tiền Follow4Follow (v1.7.0).
 * Schema lấy từ `.claude/context/follow4follow/API_reference_v2.txt` mục 4.2/4.4/4.7/4.9/4.11.
 * Số tiền/coin giữ nguyên dạng chuỗi server trả về khi chỉ để hiển thị (server lúc trả số, lúc trả chuỗi kèm đơn vị).
 */

data class F4fDashboard(
    val balance: String,
    val balancePending: String,
    val totalCoin: Long?,
    val levelName: String?,
    val todayEarned: String,
    val tasksDoneToday: Int,
)

/** [coinsText] = nguyên văn server (hiện khi [coins] đọc ra 0 nhưng server có trả chuỗi). */
data class F4fLeaderEntry(
    val rank: Int, val username: String, val coins: Long, val avatar: String?,
    val coinsText: String = "",
)

data class F4fLeaderboard(val entries: List<F4fLeaderEntry>, val myRank: Int?)

data class F4fEarnStats(
    val totalJobs: Int, val todayJobs: Int,
    val totalPending: String, val todayPending: String,
    val totalApproved: String, val todayApproved: String,
)

data class F4fJobReward(
    val platform: String, val typeJob: String, val key: String,
    val jobs: Int, val remains: Int, val maxRewardFormatted: String,
)

data class F4fRate(val rate: Long, val from: String, val to: String)

/** Một trang danh sách (envelope phân trang `data.data` + meta). */
data class F4fPage<T>(val items: List<T>, val page: Int, val totalPages: Int, val total: Int) {
    val hasMore: Boolean get() = page < totalPages
}

data class F4fEarnHistoryItem(
    val id: Long, val jobId: Long, val objectId: String, val link: String,
    val coin: String, val statusLabel: String, val createdAt: String,
)

data class F4fWithdrawStats(val pending: Long, val completed: Long, val rejected: Long)

data class F4fWithdrawItem(
    val id: Long, val type: String,
    val bankName: String?, val accountName: String?, val accountNumber: String?,
    val telco: String?, val network: String?, val address: String?,
    val amount: String, val amountReceived: String,
    val status: String, val result: String?, val createdAt: String,
)

/** Gợi ý lựa chọn lấy từ `GET /user/withdraw/info` (rỗng nếu schema không đoán được → UI dùng mặc định). */
data class F4fWithdrawInfo(val banks: List<String>, val networks: List<String>, val telcos: List<String>)

data class F4fReferralSummary(
    val refCode: String, val refLink: String,
    val f1Count: Int, val f2Count: Int,
    val totalCommission: String, val pendingCommission: String, val claimedCommission: String,
    val enabled: Boolean,
)

data class F4fNotification(
    val id: Long, val type: String, val title: String, val message: String, val read: Boolean, val createdAt: String,
)

data class F4fNotifications(val items: List<F4fNotification>, val unread: Int)

// ─────────────────────────────────────────────────────────────────────────────
//  Rút tiền — 5 kênh, mỗi kênh một body khác nhau (xác minh từ call-site trang withdraw của web)
// ─────────────────────────────────────────────────────────────────────────────

enum class F4fWithdrawChannel(val label: String, val path: String) {
    BANK("Ngân hàng", "/user/withdraw/bank"),
    ZALOPAY("ZaloPay", "/user/withdraw/zalopay"),
    PAYPAL("PayPal", "/user/withdraw/paypal"),
    CRYPTO("Crypto", "/user/withdraw/crypto"),
    CARD("Thẻ cào", "/user/withdraw/card"),
}

/**
 * Yêu cầu rút tiền đã gom đủ dữ liệu. [validate] kiểm tra cục bộ trước khi gửi; [body] đúng shape server;
 * [summary] là các dòng "nhãn → giá trị" để hiện trong hộp thoại xác nhận (rút tiền KHÔNG hoàn tác được).
 */
sealed class F4fWithdrawRequest {
    abstract val channel: F4fWithdrawChannel
    abstract val amount: Long
    abstract fun body(): JsonObject
    abstract fun summary(): List<Pair<String, String>>

    /** Lỗi nhập liệu đầu tiên, hoặc null nếu hợp lệ. */
    fun validate(): String? {
        if (amount <= 0L) return "Nhập số coin muốn rút"
        return validateFields()
    }

    protected abstract fun validateFields(): String?

    data class Bank(
        val bankName: String, val accountName: String, val accountNumber: String, override val amount: Long,
    ) : F4fWithdrawRequest() {
        override val channel get() = F4fWithdrawChannel.BANK
        override fun body() = buildJsonObject {
            put("bank_name", bankName.trim()); put("account_name", accountName.trim())
            put("account_number", accountNumber.trim()); put("amount", amount)
        }
        override fun summary() = listOf(
            "Ngân hàng" to bankName.trim(), "Chủ tài khoản" to accountName.trim(),
            "Số tài khoản" to accountNumber.trim(), "Số coin" to amount.toString(),
        )
        override fun validateFields(): String? = when {
            bankName.isBlank()      -> "Nhập tên ngân hàng"
            accountName.isBlank()   -> "Nhập tên chủ tài khoản"
            accountNumber.isBlank() -> "Nhập số tài khoản"
            else -> null
        }
    }

    /** ZaloPay dùng CÙNG shape với ngân hàng (`bank_name`, `account_name`, `account_number` = số điện thoại). */
    data class ZaloPay(
        val accountName: String, val phone: String, override val amount: Long, val bankName: String = "ZaloPay",
    ) : F4fWithdrawRequest() {
        override val channel get() = F4fWithdrawChannel.ZALOPAY
        override fun body() = buildJsonObject {
            put("bank_name", bankName); put("account_name", accountName.trim())
            put("account_number", phone.trim()); put("amount", amount)
        }
        override fun summary() = listOf(
            "Chủ tài khoản" to accountName.trim(), "Số điện thoại ZaloPay" to phone.trim(), "Số coin" to amount.toString(),
        )
        override fun validateFields(): String? = when {
            accountName.isBlank() -> "Nhập tên chủ tài khoản"
            phone.isBlank()       -> "Nhập số điện thoại ZaloPay"
            else -> null
        }
    }

    /** PayPal: trường là `email` (KHÔNG phải `account_number`). */
    data class PayPal(val email: String, override val amount: Long) : F4fWithdrawRequest() {
        override val channel get() = F4fWithdrawChannel.PAYPAL
        override fun body() = buildJsonObject { put("email", email.trim()); put("amount", amount) }
        override fun summary() = listOf("Email PayPal" to email.trim(), "Số coin" to amount.toString())
        override fun validateFields(): String? =
            if (!email.trim().contains('@') || email.trim().length < 5) "Email PayPal không hợp lệ" else null
    }

    /** Crypto: địa chỉ ví là `wallet_address` (KHÔNG phải `address`). */
    data class Crypto(val network: String, val walletAddress: String, override val amount: Long) : F4fWithdrawRequest() {
        override val channel get() = F4fWithdrawChannel.CRYPTO
        override fun body() = buildJsonObject {
            put("network", network.trim()); put("wallet_address", walletAddress.trim()); put("amount", amount)
        }
        override fun summary() = listOf(
            "Mạng" to network.trim().uppercase(), "Địa chỉ ví" to walletAddress.trim(), "Số coin" to amount.toString(),
        )
        override fun validateFields(): String? = when {
            network.isBlank()                -> "Chọn mạng chuyển"
            walletAddress.trim().length < 10 -> "Địa chỉ ví không hợp lệ"
            else -> null
        }
    }

    data class Card(val telco: String, override val amount: Long) : F4fWithdrawRequest() {
        override val channel get() = F4fWithdrawChannel.CARD
        override fun body() = buildJsonObject { put("telco", telco.trim()); put("amount", amount) }
        override fun summary() = listOf("Nhà mạng" to telco.trim(), "Số coin" to amount.toString())
        override fun validateFields(): String? = if (telco.isBlank()) "Chọn nhà mạng" else null
    }
}
