package com.atpro.tasks.f4f

import com.atpro.tasks.TaskResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Các tính năng "ví" của Follow4Follow ngoài luồng làm job (v1.7.0): tổng quan, bảng xếp hạng, rút tiền,
 * lịch sử, giới thiệu, thông báo. Mỗi hàm trả [TaskResult] — không ném exception; 401 → `isAuthError`
 * (và gọi [onAuthExpired]), 429 → `cooldownSecs`. Chỉ ĐỌC trừ [withdraw] và [claimAllCommissions].
 *
 * Xem `.claude/context/Follow4FollowAPI_Documentation.md` mục 9.
 */
class F4fService(
    private val api: F4fApiClient,
    private val onAuthExpired: () -> Unit = {},
) {
    // ── Tổng quan ─────────────────────────────────────────────────────────────

    /** Lấy phần tử đầu tiên có giá trị (khác null/JsonNull) trong các key — server đặt tên không nhất quán. */
    private fun JsonObject.firstOf(vararg keys: String): JsonElement? =
        keys.firstNotNullOfOrNull { k -> this[k].nonNull() }

    suspend fun dashboard(): TaskResult<F4fDashboard> = get("/user/dashboard") { d ->
        // v1.10.0: chấp nhận thêm vỏ `data` và các tên key khác — xu chờ / hôm nay / tổng xu không còn rơi về 0 khi lệch key.
        val o = d.asObj()?.let { root -> root["data"].asObj() ?: root } ?: return@get null
        F4fDashboard(
            balance        = o.firstOf("balance", "coin", "coins", "balance_coin").asStr().orEmpty(),
            balancePending = o.firstOf("balance_pending", "pending_balance", "coin_pending", "pending", "balancePending").asStr().orEmpty(),
            totalCoin      = o.firstOf("total_coin", "total_coins", "totalCoin", "coin_total").asCoins(),
            levelName      = o["level"].asObj()?.get("name").asStr() ?: o["level"].asStr(),
            todayEarned    = o.firstOf("today_earned", "today_coin", "today_coins", "earned_today", "todayEarned").asStr().orEmpty(),
            tasksDoneToday = o.firstOf("tasks_done_today", "today_jobs", "jobs_today", "tasksDoneToday").asInt() ?: 0,
        )
    }

    suspend fun earnStats(type: String = "tiktok"): TaskResult<F4fEarnStats> =
        get("/user/earn-coin/statistical", mapOf("type" to type)) { d ->
            val o = d.asObj() ?: return@get null
            F4fEarnStats(
                totalJobs = o["total_jobs"].asInt() ?: 0, todayJobs = o["today_jobs"].asInt() ?: 0,
                totalPending = o["total_pending"].asStr().orEmpty(), todayPending = o["today_pending"].asStr().orEmpty(),
                totalApproved = o["total_approved"].asStr().orEmpty(), todayApproved = o["today_approved"].asStr().orEmpty(),
            )
        }

    suspend fun jobRewards(): TaskResult<List<F4fJobReward>> = get("/user/job-rewards") { d ->
        (d.asArr() ?: d.asObj()?.get("data").asArr() ?: return@get null).mapNotNull { el ->
            val o = el.asObj() ?: return@mapNotNull null
            F4fJobReward(
                platform = o["platform"].asStr().orEmpty(), typeJob = o["type_job"].asStr().orEmpty(),
                key = o["key"].asStr().orEmpty(), jobs = o["jobs"].asInt() ?: 0, remains = o["remains"].asInt() ?: 0,
                maxRewardFormatted = o["max_reward_formatted"].asStr() ?: o["max_reward"].asStr().orEmpty(),
            )
        }
    }

    suspend fun rate(): TaskResult<F4fRate> = get("/user/rate") { d ->
        val o = d.asObj() ?: return@get null
        F4fRate(o["rate"].asLong() ?: return@get null, o["from"].asStr() ?: "USD", o["to"].asStr() ?: "COIN")
    }

    // ── Bảng xếp hạng ─────────────────────────────────────────────────────────

    /** `GET /user/leaderboard?limit=N` (tham số là `limit`, KHÔNG phải page/per_page). Chấp nhận cả key HOA/thường. */
    suspend fun leaderboard(limit: Int = 20): TaskResult<F4fLeaderboard> =
        get("/user/leaderboard", mapOf("limit" to limit.toString())) { d ->
            val o = d.asObj()
            val rows = d.asArr()
                ?: o?.firstOf("leaderboard", "Leaderboard", "data", "items", "rows", "list", "users", "ranking")
                    ?.let { it.asArr() ?: it.asObj()?.get("data").asArr() }
                ?: return@get null
            val entries = rows.mapIndexedNotNull { i, el ->
                val e = el.asObj() ?: return@mapIndexedNotNull null
                // v1.10.0: bảng xếp hạng không hiện xu vì chỉ đọc 4 key và ép chuỗi định dạng ("12,345") về 0.
                // Nay thử nhiều key; không có key quen thuộc thì lấy trường nào có tên chứa "coin"/"earn".
                val coinEl = e.firstOf(
                    "coins", "Coins", "coin", "Coin", "total_coin", "total_coins", "coin_earned", "coins_earned",
                    "earned", "total_earned", "total", "balance", "reward", "score", "points", "point", "amount",
                ) ?: e.entries.firstOrNull { (k, v) ->
                    val key = k.lowercase()
                    ("coin" in key || "earn" in key || key == "xu") && v.nonNull() != null
                }?.value
                F4fLeaderEntry(
                    rank       = e.firstOf("rank", "Rank", "position").asInt() ?: (i + 1),
                    username   = e.firstOf("username", "Username", "user_name", "name", "user", "display_name").asStr().orEmpty(),
                    coins      = coinEl.asCoins() ?: 0L,
                    avatar     = (e["avatar"] ?: e["Avatar"]).asStr()?.takeIf { it.isNotBlank() },
                    coinsText  = coinEl.asStr().orEmpty(),
                )
            }
            F4fLeaderboard(entries, (o?.get("my_rank") ?: o?.get("MyRank") ?: o?.get("myRank")).asInt())
        }

    // ── Lịch sử job ───────────────────────────────────────────────────────────

    /** `action` BẮT BUỘC (follow|like) — thiếu thì server trả rỗng (đã field-test). */
    suspend fun earnHistory(action: String, page: Int = 1, type: String = "tiktok"): TaskResult<F4fPage<F4fEarnHistoryItem>> =
        get("/user/earn-coin/list", linkedMapOf("type" to type, "page" to page.toString(), "action" to action)) { d ->
            parsePage(d) { o ->
                F4fEarnHistoryItem(
                    id = o["id"].asLong() ?: return@parsePage null, jobId = o["job_id"].asLong() ?: 0L,
                    objectId = o["object_id_job"].asStr().orEmpty(), link = o["link_job"].asStr().orEmpty(),
                    coin = o["coin_received"].asStr().orEmpty(),
                    statusLabel = o["status_label"].asStr().orEmpty(), createdAt = o["created_at"].asStr().orEmpty(),
                )
            }
        }

    // ── Rút tiền ──────────────────────────────────────────────────────────────

    suspend fun withdrawStats(): TaskResult<F4fWithdrawStats> = get("/user/withdraw/statistical") { d ->
        val o = d.asObj() ?: return@get null
        F4fWithdrawStats(
            pending = o["amountPendingWithdraw"].asLong() ?: 0L,
            completed = o["amountCompletedWithdraw"].asLong() ?: 0L,
            rejected = o["amountRejectedWithdraw"].asLong() ?: 0L,
        )
    }

    suspend fun withdrawHistory(page: Int = 1): TaskResult<F4fPage<F4fWithdrawItem>> =
        get("/user/withdraw/list", linkedMapOf("page" to page.toString(), "per_page" to "10")) { d ->
            parsePage(d) { o ->
                F4fWithdrawItem(
                    id = o["id"].asLong() ?: return@parsePage null, type = o["type"].asStr().orEmpty(),
                    bankName = o["bank_name"].asStr(), accountName = o["account_name"].asStr(),
                    accountNumber = o["account_number"].asStr(), telco = o["telco"].asStr(),
                    network = o["network"].asStr(), address = o["address"].asStr(),
                    amount = o["amount"].asStr().orEmpty(), amountReceived = o["amount_received"].asStr().orEmpty(),
                    status = o["status"].asStr().orEmpty(), result = o["result"].asStr(),
                    createdAt = o["created_at"].asStr().orEmpty(),
                )
            }
        }

    /**
     * `GET /user/withdraw/info` — chỉ lấy GỢI Ý lựa chọn (ngân hàng/mạng/nhà mạng). Schema chưa được xác minh nên
     * đọc "khoan dung"; không đọc được thì trả danh sách rỗng và UI dùng giá trị mặc định.
     */
    suspend fun withdrawInfo(): TaskResult<F4fWithdrawInfo> = get("/user/withdraw/info") { d ->
        val o = d.asObj()
        F4fWithdrawInfo(
            banks    = optionNames(o?.get("banks") ?: o?.get("bank_list") ?: o?.get("bank")),
            networks = optionNames(o?.get("networks") ?: o?.get("network_list") ?: o?.get("network")),
            telcos   = optionNames(o?.get("telcos") ?: o?.get("telco_list") ?: o?.get("telco")),
        )
    }

    /**
     * GỬI yêu cầu rút tiền thật. Trả về thông báo của server khi thành công.
     * Không tự thử lại ở bất kỳ tầng nào (tránh rút trùng); lỗi mạng cũng trả lỗi để người dùng kiểm tra lịch sử rút.
     */
    suspend fun withdraw(request: F4fWithdrawRequest): TaskResult<String> {
        request.validate()?.let { return TaskResult.Error(it) }
        if (!api.hasToken) return notLoggedIn()
        val r = api.post(request.channel.path, request.body())
        if (r.isError) return r.toTaskError(onAuthExpired)
        return TaskResult.Success(r.message.ifBlank { "Đã gửi yêu cầu rút tiền" })
    }

    // ── Giới thiệu ────────────────────────────────────────────────────────────

    suspend fun referralSummary(): TaskResult<F4fReferralSummary> = get("/user/referral/summary") { d ->
        val o = d.asObj() ?: return@get null
        F4fReferralSummary(
            refCode = o["ref_code"].asStr().orEmpty(), refLink = o["ref_link"].asStr().orEmpty(),
            f1Count = o["f1_count"].asInt() ?: 0, f2Count = o["f2_count"].asInt() ?: 0,
            totalCommission = o["total_commission"].asStr().orEmpty(),
            pendingCommission = o["pending_commission"].asStr().orEmpty(),
            claimedCommission = o["claimed_commission"].asStr().orEmpty(),
            enabled = o["enabled"].asBool() ?: true,
        )
    }

    /** Nhận toàn bộ hoa hồng đang chờ (`POST /user/referral/claim-all`, không body). */
    suspend fun claimAllCommissions(): TaskResult<String> {
        if (!api.hasToken) return notLoggedIn()
        val r = api.post("/user/referral/claim-all")
        if (r.isError) return r.toTaskError(onAuthExpired)
        return TaskResult.Success(r.message.ifBlank { "Đã nhận hoa hồng" })
    }

    // ── Thông báo ─────────────────────────────────────────────────────────────

    suspend fun notifications(): TaskResult<F4fNotifications> = get("/user/notifications") { d ->
        val o = d.asObj()
        val rows = d.asArr() ?: o?.get("data").asArr() ?: return@get null
        F4fNotifications(
            items = rows.mapNotNull { el ->
                val n = el.asObj() ?: return@mapNotNull null
                F4fNotification(
                    id = n["id"].asLong() ?: return@mapNotNull null, type = n["type"].asStr().orEmpty(),
                    title = n["title"].asStr().orEmpty(), message = n["message"].asStr().orEmpty(),
                    read = n["read"].asBool() ?: false, createdAt = n["created_at"].asStr().orEmpty(),
                )
            },
            unread = o?.get("unread").asInt() ?: 0,
        )
    }

    // ── Tiện ích ──────────────────────────────────────────────────────────────

    private suspend fun <T> get(
        path: String,
        query: Map<String, String> = emptyMap(),
        parse: (JsonElement?) -> T?,
    ): TaskResult<T> {
        if (!api.hasToken) return notLoggedIn()
        val r = api.get(path, query)
        if (r.isError) return r.toTaskError(onAuthExpired)
        val parsed = parse(r.data) ?: return TaskResult.Error("Dữ liệu server trả về không đọc được ($path)")
        return TaskResult.Success(parsed)
    }

    private fun notLoggedIn(): TaskResult.Error = TaskResult.Error("Chưa đăng nhập Follow4Follow", isAuthError = true)

    /** Envelope phân trang: `data = {data:[…], per_page, total_page, current_page, total}`. Rỗng là hợp lệ. */
    private fun <T> parsePage(d: JsonElement?, mapItem: (JsonObject) -> T?): F4fPage<T> {
        val o = d.asObj()
        val rows = o?.get("data").asArr() ?: d.asArr()
        val items = rows?.mapNotNull { it.asObj()?.let(mapItem) }.orEmpty()
        return F4fPage(
            items = items,
            page = o?.get("current_page").asInt() ?: 1,
            totalPages = o?.get("total_page").asInt() ?: 1,
            total = o?.get("total").asInt() ?: items.size,
        )
    }

    /** Mảng chuỗi / mảng object (`name|label|code|value`) / object (lấy key) → danh sách tên. */
    private fun optionNames(el: JsonElement?): List<String> {
        el.asArr()?.let { arr ->
            return arr.mapNotNull { item ->
                item.asStr()?.takeIf { it.isNotBlank() }
                    ?: item.asObj()?.let { (it["name"] ?: it["label"] ?: it["code"] ?: it["value"]).asStr() }
            }.distinct()
        }
        return el.asObj()?.keys?.toList().orEmpty()
    }
}
