package com.atpro.tasks.f4f

import com.atpro.tasks.TaskAccount
import com.atpro.tasks.TaskJob
import com.atpro.tasks.TaskJobKind
import com.atpro.tasks.TaskProvider
import com.atpro.tasks.TaskResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Nhà cung cấp nhiệm vụ Follow4Follow (TikTok Follow / Like) — v1.6.0.
 *
 * Luồng 1 phiên task (mirror đúng bản web + `f4f.py` v2.8, xem `.claude/context/Follow4FollowAPI_Documentation.md`):
 *
 *  1. [listAccounts]  GET  /user/account/quick-switch?type=tiktok  (data.accounts[]; v1.12.0)
 *                     dự phòng GET /user/account/list?type=tiktok&page=N (mọi trang)
 *  2. [nextJob]       GET  /user/earn-coin?platform=tiktok&type=follow|like&uid=<tên acc>  (LUÔN kèm uid; lấy lại/đổi acc: +refresh=1)
 *                     • accountActive null / khác acc cần làm → thang đổi acc S1–S5 (active-acc → uid số → quick-switch cũ → …)
 *                     • `account_daily_usage.reached` → ngừng lấy job cho acc này
 *  3. [onJobOpening]  POST /user/earn-coin/tiktok-like/mark-start   (chỉ job LIKE, TRƯỚC khi mở link)
 *  4. [complete]      POST /user/earn-coin/complete-job {job_id, account_id, type, type_job}  (đợi ≥5s sau khi mở link)
 *     [skip]          POST /user/earn-coin/skip-job     {job_id, account_id, uid, object_id, type}
 *
 * Provider KHÔNG an toàn đa luồng — engine gọi tuần tự từ 1 coroutine. Mọi lỗi trả về dạng
 * [TaskResult.Error] (không ném exception): 401 → `isAuthError`, 429 → `cooldownSecs`.
 */
class Follow4FollowProvider(
    private val api: F4fApiClient,
    /** Báo số dư mới (chuỗi thô từ server) để UI cập nhật. */
    private val onBalance: (balance: String?, pending: String?) -> Unit = { _, _ -> },
    /** Token bị server từ chối (401) → xoá phiên đăng nhập. */
    private val onAuthExpired: () -> Unit = {},
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** Thời gian tối thiểu từ lúc mở link tới lúc báo complete (web đếm ngược 5s). Test truyền 0. */
    private val minDwellMs: Long = MIN_DWELL_MS,
) : TaskProvider {

    override val displayName: String = "Follow4Follow"

    @Volatile private var traceListener: ((String) -> Unit)? = null
    override fun setTraceListener(listener: ((String) -> Unit)?) { traceListener = listener }
    private fun trace(line: String) { traceListener?.invoke(line) }

    private class Usage(val done: Int?, val limit: Int?, val remaining: Int?, val reached: Boolean)

    // ── Trạng thái phiên (reset ở listAccounts) ───────────────────────────────
    private var serverActiveId: String? = null          // acc F4F đang active phía server (theo hiểu biết của ta)
    private var fetchedOnce = false                     // đã gọi earn-coin ít nhất 1 lần → các lần sau gửi refresh=1
    private var rotation = 0                            // xoay vòng LIKE/FOLLOW khi chạy BOTH
    private val queues = HashMap<String, ArrayDeque<TaskJob>>()
    private val queuedAt = HashMap<String, Long>()
    private val quotaHit = HashSet<String>()            // id acc đã hết lượt hôm nay
    private val reasons = HashMap<String, String>()
    private val openedAt = HashMap<String, Long>()      // job id → mốc mở link

    // ── TaskProvider ──────────────────────────────────────────────────────────

    override suspend fun listAccounts(): TaskResult<List<TaskAccount>> {
        if (!api.hasToken) return notLoggedIn()
        resetSession()

        // 1) Danh sách "quick-switch" — đúng nguồn của build web mới (f4f.py v2.7+): data.accounts[] = {id, name, uid, avatar}.
        val qs = api.get(PATH_QUICK_SWITCH, mapOf("type" to PLATFORM))
        if (qs.isUnauthorized) return failure(qs)                 // phiên chết → khỏi thử nguồn khác
        if (!qs.isError) {
            val fromQuick = parseQuickSwitchAccounts(qs.data)
            if (fromQuick.isNotEmpty()) {
                trace("Danh sách tài khoản: ${fromQuick.size} acc (quick-switch)")
                return TaskResult.Success(fromQuick)
            }
        }

        // 2) Dự phòng: account/list phân trang (schema cũ — trang quản lý tài khoản của web vẫn dùng).
        val accounts = ArrayList<TaskAccount>()
        var page = 1
        while (true) {
            val r = api.get(PATH_ACCOUNT_LIST, mapOf("type" to PLATFORM, "page" to page.toString()))
            if (r.isError) return failure(r)
            val d = r.dataObj
            d?.get("data").asArr()?.forEach { el -> parseAccount(el)?.let { accounts.add(it) } }
            val totalPages = d?.get("total_page").asInt() ?: 1
            page++
            if (page > totalPages || page > MAX_ACCOUNT_PAGES) break
        }
        trace("Danh sách tài khoản: ${accounts.size} acc (account/list)")
        return TaskResult.Success(accounts)
    }

    /**
     * `data.accounts` (build mới) · `data.data` · `data` là mảng — server đang chuyển tiếp nên nhận cả ba dạng.
     * Chỉ giữ phần tử có `id`.
     */
    private fun parseQuickSwitchAccounts(data: JsonElement?): List<TaskAccount> {
        val rows = when (data) {
            null -> null
            else -> data.asObj()?.let { it["accounts"].asArr() ?: it["data"].asArr() } ?: data.asArr()
        } ?: return emptyList()
        return rows.mapNotNull { parseAccount(it) }
    }

    override suspend fun nextJob(account: TaskAccount, kinds: Set<TaskJobKind>): TaskResult<TaskJob?> {
        if (!api.hasToken) return notLoggedIn()
        if (kinds.isEmpty()) return TaskResult.Success(null)
        if (account.id in quotaHit) return TaskResult.Success(null)

        val order = orderedKinds(kinds)

        // 1) Còn job trong hàng đợi (đã lấy trước đó, chưa quá hạn) → dùng luôn, khỏi gọi mạng.
        for (kind in order) popFresh(account, kind)?.let { return TaskResult.Success(it) }

        // 2) Hết hàng đợi → lấy mới từng loại.
        for (kind in order) {
            val fetched = fetchJobs(account, kind)
            if (fetched is TaskResult.Error) return fetched
            if (account.id in quotaHit) return TaskResult.Success(null)
            popFresh(account, kind)?.let { return TaskResult.Success(it) }
        }

        if (reasons[account.id].isNullOrBlank()) {
            reasons[account.id] = "server chưa có job ${order.joinToString("/") { it.name.lowercase() }}"
        }
        return TaskResult.Success(null)
    }

    override suspend fun onJobOpening(account: TaskAccount, job: TaskJob) {
        openedAt[job.id] = nowMs()
        if (job.kind == TaskJobKind.LIKE) {
            // fire-and-forget như web (`.catch(() => {})`): server ghi mốc bắt đầu để đối chiếu khi complete.
            api.post(PATH_MARK_START, buildJsonObject {
                put("job_id", idJson(job.id))
                put("account_id", idJson(account.id))
            })
        }
    }

    override suspend fun complete(account: TaskAccount, job: TaskJob): TaskResult<Double> {
        if (!api.hasToken) return notLoggedIn()

        // Web chỉ cho Hoàn thành sau khi đếm ngược 5s kể từ lúc mở link.
        openedAt[job.id]?.let { opened ->
            val wait = minDwellMs - (nowMs() - opened)
            if (wait > 0) delay(wait)
        }

        val r = api.post(PATH_COMPLETE, buildJsonObject {
            put("job_id", idJson(job.id))
            put("account_id", idJson(account.id))
            put("type", PLATFORM)
            put("type_job", job.kind.name.lowercase())
        })
        if (r.isError) return failureOfJob(r, account)

        openedAt.remove(job.id)
        var received: Double? = null
        r.dataObj?.let { d ->
            // API v2.7 (đã field-test): data = { coin_received, current_balance, balance_pending, account_daily_usage }.
            received = d["coin_received"].asDouble()
            onBalance(d["current_balance"].asStr(), d["balance_pending"].asStr())
            updateUsage(account, d["account_daily_usage"])
        }
        // Ưu tiên số coin server THỰC GHI NHẬN; thiếu thì dùng phần thưởng hiển thị lúc lấy job.
        return TaskResult.Success(received ?: job.reward)
    }

    override suspend fun skip(account: TaskAccount, job: TaskJob): TaskResult<Unit> {
        if (!api.hasToken) return notLoggedIn()
        val r = api.post(PATH_SKIP, buildJsonObject {
            put("job_id", idJson(job.id))
            put("account_id", idJson(account.id))
            put("uid", uidOf(account))
            put("object_id", objectIdOf(job))
            put("type", PLATFORM)
        })
        if (r.isError) return failureOfJob(r, account)
        openedAt.remove(job.id)
        return TaskResult.Success(Unit)
    }

    override fun noJobReason(account: TaskAccount): String = reasons[account.id].orEmpty()

    // ── Lấy job (f4f.py v2.8 — build web mới) ──────────────────────────────────
    //
    // Khác bản cũ (v1.6–v1.11): (1) LUÔN gửi `uid` = tên acc — web không bao giờ gọi earn-coin khi chưa biết acc, fetch
    // trần bị server trả "Không tìm thấy dữ liệu"; (2) đổi acc = fetch kèm `uid` (server đổi accountActive THEO uid),
    // KHÔNG POST quick-switch trước (build mới trả "Không tìm thấy dữ liệu" cho POST đó — chính là lỗi "đổi acc không
    // được"); (3) fetch không hiệu lực → thang đổi acc S1–S5, mỗi bước có trace.

    /** Một lần gọi earn-coin đã bóc tách. */
    private sealed interface Earn {
        class Ok(val payload: JsonObject?, val activeId: String?, val activeName: String?, val jobCount: Int) : Earn
        class Err(val response: F4fResponse) : Earn
    }

    private suspend fun earnOnce(kind: TaskJobKind, uid: String?, refresh: Boolean, tag: String): Earn {
        var r = api.get(PATH_EARN, earnQuery(kind, uid, refresh))
        if (r.isRateLimited) {                                   // 429: chờ retry_after rồi thử lại đúng 1 lần (như f4f.py)
            val wait = r.retryAfterSecs.coerceIn(3, MAX_INLINE_WAIT_SECS)
            trace("$tag: server báo thao tác nhanh — chờ ${wait}s")
            delay(wait * 1_000L)
            r = api.get(PATH_EARN, earnQuery(kind, uid, refresh))
        }
        if (r.isError) {
            trace("$tag: ${r.httpCode}${if (r.apiCode != null && r.apiCode != r.httpCode) "/json ${r.apiCode}" else ""} — ${r.describe()}")
            return Earn.Err(r)
        }
        val payload = r.dataObj
        val active = payload?.get("accountActive").asObj()
        val activeId = active?.get("id").asLong()?.toString()
        val activeName = active?.get("name").asStr() ?: active?.get("uid").asStr()
        val jobs = payload?.get("jobs").asArr()?.size ?: 0
        trace("$tag: đang active ${activeName ?: "(chưa có)"} · $jobs job")
        return Earn.Ok(payload, activeId, activeName, jobs)
    }

    private suspend fun fetchJobs(account: TaskAccount, kind: TaskJobKind): TaskResult<Unit> {
        // V1 — y hệt web: uid = tên acc; refresh=1 chỉ khi LẤY LẠI hoặc vừa đổi acc (lần đầu của phiên thì không).
        val changed = serverActiveId != null
        var payload: JsonObject? = null
        when (val first = earnOnce(kind, account.username, refresh = changed, tag = "Lấy job")) {
            is Earn.Ok -> if (first.activeId == account.id) payload = first.payload
            is Earn.Err -> if (stopsLadder(first.response) || first.response.isRateLimited) return failure(first.response, account)
        }

        // Fetch không cho đúng acc (accountActive null / acc khác / "Không tìm thấy dữ liệu") → thang đổi acc.
        if (payload == null) {
            val ladder = forceActivate(account, kind)
            payload = ladder.matched
            if (payload == null) return ladderFailure(account, ladder)
        }

        // V2 — acc đúng nhưng chưa có job: thử một lần fetch "trơn" (phòng cache/uid lệch), chỉ nhận nếu vẫn đúng acc.
        if (parseJobs(payload, kind).isEmpty() && account.id !in quotaHit) {
            val plain = earnOnce(kind, null, refresh = false, tag = "Lấy job (trơn)")
            if (plain is Earn.Ok && plain.activeId == account.id && plain.jobCount > 0) payload = plain.payload
        }

        serverActiveId = account.id
        fetchedOnce = true

        updateUsage(account, payload?.get("account_daily_usage") ?: payload?.get("daily_usage") ?: payload?.get("usage"))

        val key = queueKey(account, kind)
        queues[key] = ArrayDeque(parseJobs(payload, kind))
        queuedAt[key] = nowMs()
        return TaskResult.Success(Unit)
    }

    private class Ladder(val matched: JsonObject?, val otherActiveName: String?, val lastError: F4fResponse?)

    /**
     * Thang đổi acc (mirror `_switch_force` của f4f.py v2.8) — chạy khi fetch kèm `uid` không đưa server về đúng acc:
     *   S1 POST active-acc → fetch uid=tên · S2 fetch uid=số · S3 POST quick-switch (cũ) → fetch · S4 fetch uid=tên lại · S5 fetch trơn.
     * Dừng ngay khi khớp acc; dừng sớm nếu phiên chết (401).
     */
    private suspend fun forceActivate(account: TaskAccount, kind: TaskJobKind): Ladder {
        var matched: JsonObject? = null
        var otherName: String? = null
        var lastErr: F4fResponse? = null
        val name = account.username
        val numeric = uidOf(account).takeIf { it != name }

        suspend fun fetch(tag: String, uid: String?) {
            if (matched != null || stopsLadder(lastErr)) return
            when (val e = earnOnce(kind, uid, refresh = true, tag = tag)) {
                is Earn.Ok -> if (e.activeId == account.id) matched = e.payload
                            else if (e.activeId != null) otherName = e.activeName
                is Earn.Err -> lastErr = e.response
            }
        }
        suspend fun post(tag: String, path: String): Boolean {
            if (matched != null || stopsLadder(lastErr)) return false
            var r = api.post(path, buildJsonObject { put("account_id", idJson(account.id)); put("type", PLATFORM) })
            if (r.isRateLimited) {
                val wait = r.retryAfterSecs.coerceIn(3, MAX_INLINE_WAIT_SECS)
                trace("$tag: server báo thao tác nhanh — chờ ${wait}s")
                delay(wait * 1_000L)
                r = api.post(path, buildJsonObject { put("account_id", idJson(account.id)); put("type", PLATFORM) })
            }
            trace("$tag: ${if (r.isError) "${r.httpCode} — ${r.describe()}" else "OK"}")
            if (r.isError) lastErr = r
            return !r.isError
        }

        trace("Đổi tài khoản F4F sang $name")
        if (post("S1 active-acc", PATH_ACTIVE_ACC)) fetch("S1 lấy job", name)
        if (numeric != null) fetch("S2 uid số", numeric)
        if (matched == null && !stopsLadder(lastErr)) { post("S3 quick-switch cũ", PATH_QUICK_SWITCH); fetch("S3 lấy job", name) }
        fetch("S4 uid tên lại", name)
        fetch("S5 lấy trơn", null)

        trace(if (matched != null) "Đổi acc: KHỚP $name" else "Đổi acc: không khớp")
        return Ladder(matched, otherName, lastErr)
    }

    /** Phiên chết (401) hay mất mạng → đi tiếp thang cũng vô ích, dừng ngay. */
    private fun stopsLadder(r: F4fResponse?): Boolean = r != null && (r.isUnauthorized || r.networkError)

    /** Hết thang vẫn không khớp → lỗi có hướng xử lý (3 nguyên nhân như panel của f4f.py). */
    private fun ladderFailure(account: TaskAccount, ladder: Ladder): TaskResult.Error {
        ladder.lastError?.let { r ->
            if (r.isUnauthorized || r.isRateLimited || r.networkError) return failure(r, account)
        }
        val other = ladder.otherActiveName
        if (other != null) {
            return TaskResult.Error(
                "Server đang active tài khoản khác (@$other) thay vì @${account.username} — " +
                    "thử chạy lại nhiệm vụ; nếu vẫn vậy hãy đổi acc trên follow4follow.net rồi chạy lại"
            )
        }
        val serverMsg = ladder.lastError?.describe()
        return TaskResult.Error(
            "Không kích hoạt được @${account.username}: server vẫn trả accountActive = null" +
                (if (!serverMsg.isNullOrBlank()) " (\"$serverMsg\")" else "") +
                " — kiểm tra tài khoản trên follow4follow.net (xoá rồi thêm lại acc nếu lỗi lặp lại)"
        )
    }

    private fun parseJobs(payload: JsonObject?, requested: TaskJobKind): List<TaskJob> {
        val rows = payload?.get("jobs").asArr() ?: return emptyList()
        val jobs = ArrayList<TaskJob>(rows.size)
        for (el in rows) {
            val o = el.asObj() ?: continue
            val id = o["id"].asLong() ?: continue
            val link = o["link"].asStr() ?: o["original_link"].asStr() ?: continue
            val kind = when (o["type_job"].asStr()?.lowercase()) {
                "like"   -> TaskJobKind.LIKE
                "follow" -> TaskJobKind.FOLLOW
                else     -> requested
            }
            jobs.add(
                TaskJob(
                    id = id.toString(),
                    kind = kind,
                    link = link,
                    reward = o["reward_amount"].asDouble() ?: 0.0,
                    providerData = buildJsonObject {
                        put("object_id", o["object_id"].asStr() ?: id.toString())
                    }.toString(),
                )
            )
        }
        return jobs
    }

    private fun updateUsage(account: TaskAccount, el: JsonElement?) {
        val o = el.asObj() ?: return
        val usage = Usage(
            done = o["done"].asInt(), limit = o["limit"].asInt(),
            remaining = o["remaining"].asInt(), reached = o["reached"].asBool() == true,
        )
        val exhausted = usage.reached || (usage.limit != null && usage.limit > 0 && (usage.remaining ?: 1) <= 0)
        if (exhausted) {
            quotaHit.add(account.id)
            reasons[account.id] = "đã đạt giới hạn job hôm nay (${usage.done ?: "?"}/${usage.limit ?: "?"})"
        }
    }

    private fun popFresh(account: TaskAccount, kind: TaskJobKind): TaskJob? {
        val key = queueKey(account, kind)
        val q = queues[key] ?: return null
        if (q.isEmpty()) return null
        if (nowMs() - (queuedAt[key] ?: 0L) > QUEUE_TTL_MS) {   // job cũ → server có thể đã thu hồi
            q.clear()
            return null
        }
        return q.removeFirst()
    }

    /** Xoay vòng điểm bắt đầu để chế độ BOTH làm xen kẽ Follow/Like thay vì dồn 1 loại. */
    private fun orderedKinds(kinds: Set<TaskJobKind>): List<TaskJobKind> {
        val sorted = kinds.sortedBy { it.ordinal }
        val shift = rotation++ % sorted.size
        return sorted.drop(shift) + sorted.take(shift)
    }

    // ── Tiện ích ──────────────────────────────────────────────────────────────

    private fun resetSession() {
        serverActiveId = null
        fetchedOnce = false
        rotation = 0
        queues.clear(); queuedAt.clear(); quotaHit.clear(); reasons.clear(); openedAt.clear()
    }

    private fun parseAccount(el: JsonElement): TaskAccount? {
        val o = el.asObj() ?: return null
        val id = o["id"].asLong() ?: return null
        val uid = o["uid"].asStr()
        val name = o["name"].asStr()?.takeIf { it.isNotBlank() } ?: uid ?: return null
        return TaskAccount(
            id = id.toString(),
            username = name,
            providerData = buildJsonObject { put("uid", uid ?: name) }.toString(),
        )
    }

    private fun uidOf(account: TaskAccount): String =
        parseJson(account.providerData).asObj()?.get("uid").asStr() ?: account.username

    private fun objectIdOf(job: TaskJob): String =
        parseJson(job.providerData).asObj()?.get("object_id").asStr() ?: job.id

    /** Server dùng id kiểu số; nếu không ép được thì gửi nguyên chuỗi. */
    private fun idJson(id: String): JsonElement = id.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(id)

    private fun earnQuery(kind: TaskJobKind, uid: String?, refresh: Boolean): Map<String, String> {
        val q = LinkedHashMap<String, String>()
        q["platform"] = PLATFORM
        q["type"] = kind.name.lowercase()
        if (refresh) q["refresh"] = "1"      // web CHỈ gửi refresh khi refetch
        if (!uid.isNullOrBlank()) q["uid"] = uid
        return q
    }

    private fun queueKey(account: TaskAccount, kind: TaskJobKind) = "${account.id}:${kind.name}"

    private fun notLoggedIn(): TaskResult.Error =
        TaskResult.Error("Chưa đăng nhập Follow4Follow", isAuthError = true)

    /** Đổi response lỗi thành [TaskResult.Error]; riêng thông báo "hết quota" còn khoá acc cho tới phiên sau. */
    private fun failure(r: F4fResponse, account: TaskAccount? = null): TaskResult.Error {
        if (account != null && !r.networkError && !r.isUnauthorized && !r.isRateLimited &&
            F4fHints.isQuotaMessage(r.describe())
        ) {
            quotaHit.add(account.id)
            reasons[account.id] = "đã đạt giới hạn job hôm nay"
        }
        return r.toTaskError(onAuthExpired)
    }

    /** Lỗi của complete/skip: "Không tìm thấy dữ liệu" = job đã bị xoá/hết → đánh dấu `jobGone` (engine bỏ job, khỏi skip). */
    private fun failureOfJob(r: F4fResponse, account: TaskAccount): TaskResult.Error {
        val e = failure(r, account)
        return if (!r.networkError && !r.isUnauthorized && !r.isRateLimited && F4fHints.isJobGoneMessage(r.describe())) e.copy(jobGone = true)
        else e
    }

    companion object {
        const val PLATFORM = "tiktok"

        const val PATH_ACCOUNT_LIST  = "/user/account/list"
        /** GET = danh sách acc để đổi (build web mới); POST = cách đổi acc CŨ (web đã bỏ — chỉ dùng ở bước S3 của thang). */
        const val PATH_QUICK_SWITCH  = "/user/account/quick-switch"
        const val PATH_ACTIVE_ACC    = "/user/account/active-acc"
        const val PATH_EARN          = "/user/earn-coin"
        const val PATH_MARK_START    = "/user/earn-coin/tiktok-like/mark-start"
        const val PATH_COMPLETE      = "/user/earn-coin/complete-job"
        const val PATH_SKIP          = "/user/earn-coin/skip-job"

        const val MIN_DWELL_MS = 5_000L
        private const val QUEUE_TTL_MS = 5 * 60_000L
        private const val MAX_ACCOUNT_PAGES = 20
        /** Chờ tối đa khi server báo 429 giữa các bước đổi acc/lấy job (dài hơn thì trả cooldown cho engine). */
        private const val MAX_INLINE_WAIT_SECS = 30
        }
}
