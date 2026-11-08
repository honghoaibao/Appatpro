package com.atpro.tasks.f4f

import com.atpro.tasks.TaskAccount
import com.atpro.tasks.TaskJob
import com.atpro.tasks.TaskJobKind
import com.atpro.tasks.TaskResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Follow4FollowProviderTest {

    private val acc = TaskAccount("8589", "hoaibao.2009", """{"uid":"6983264181085832219"}""")

    // ── JSON mẫu theo response thật (xem API_reference_v2.txt mục 4.3/4.4) ────

    private fun accountPage(page: Int, totalPages: Int, id: Int, name: String) = envelope(
        """{"data":[{"id":$id,"uid":"698$id","name":"$name","video":3,"status":"Hoạt động","country":"vn"}],
            "per_page":1,"total_page":$totalPages,"current_page":$page,"total":$totalPages}"""
    )

    private fun earn(
        activeId: Int? = 8589,
        jobs: String = "[]",
        usage: String = """{"done":3,"limit":50,"remaining":47,"reached":false}""",
        activeName: String = "hoaibao.2009",
    ) = envelope(
        """{"accountActive":${if (activeId == null) "null" else """{"id":$activeId,"uid":"6983264181085832219","name":"$activeName"}"""},
            "jobs":$jobs,"account_daily_usage":$usage}"""
    )

    /** Response của GET /user/account/quick-switch (build web mới): data.accounts[]. */
    private fun quickSwitch(vararg names: Pair<Int, String>) = envelope(
        """{"accounts":[${names.joinToString(",") { (id, n) -> """{"id":$id,"name":"$n","uid":"698$id","avatar":"x"}""" }}]}"""
    )

    private fun job(id: Int, type: String, reward: String = "26", objectId: String = "kim_truong.28") =
        """{"id":$id,"link":"https://www.tiktok.com/@$objectId","type_job":"$type","object_id":"$objectId",
            "original_link":"https://www.tiktok.com/@$objectId","quantity":100,"run":1,"remains":99,"reward_amount":"$reward"}"""

    private fun provider(
        transport: FakeF4fTransport,
        token: String? = "tok",
        onBalance: (String?, String?) -> Unit = { _, _ -> },
        onAuthExpired: () -> Unit = {},
        clock: () -> Long = { 1_000_000L },
    ) = Follow4FollowProvider(
        api = F4fApiClient(transport, tokenProvider = { token }, nowSecs = { 1_793_087_078L }),
        onBalance = onBalance, onAuthExpired = onAuthExpired,
        nowMs = clock, minDwellMs = 0L,
    )

    private val both = setOf(TaskJobKind.LIKE, TaskJobKind.FOLLOW)

    // ── listAccounts ──────────────────────────────────────────────────────────

    @Test
    fun listAccountsUsesQuickSwitchListFirst() = runTest {
        val t = FakeF4fTransport { FakeReply(200, quickSwitch(8589 to "acc1", 8590 to "acc2")) }
        val r = provider(t).listAccounts() as TaskResult.Success
        assertEquals(listOf("acc1", "acc2"), r.data.map { it.username })
        assertEquals(listOf("8589", "8590"), r.data.map { it.id })
        val req = t.seen.single()
        assertEquals("GET", req.method)
        assertEquals(Follow4FollowProvider.PATH_QUICK_SWITCH, req.path)
        assertEquals("tiktok", req.query["type"])
    }

    @Test
    fun quickSwitchListAcceptsOtherResponseShapes() = runTest {
        // data là mảng thẳng
        val flat = FakeF4fTransport { FakeReply(200, envelope("""[{"id":1,"name":"a"},{"name":"không có id"}]""")) }
        assertEquals(listOf("a"), (provider(flat).listAccounts() as TaskResult.Success).data.map { it.username })
        // data.data
        val nested = FakeF4fTransport { FakeReply(200, envelope("""{"data":[{"id":2,"name":"b"}]}""")) }
        assertEquals(listOf("b"), (provider(nested).listAccounts() as TaskResult.Success).data.map { it.username })
    }

    @Test
    fun listAccountsFallsBackToPagedAccountListEveryPage() = runTest {
        val t = FakeF4fTransport { req ->
            if (req.path == Follow4FollowProvider.PATH_QUICK_SWITCH) FakeReply(200, envelope("""{"accounts":[]}"""))
            else {
                val page = req.query["page"]!!.toInt()
                FakeReply(200, accountPage(page, 2, 8588 + page, "acc$page"))
            }
        }
        val r = provider(t).listAccounts() as TaskResult.Success
        assertEquals(listOf("acc1", "acc2"), r.data.map { it.username })
        assertEquals(listOf("8589", "8590"), r.data.map { it.id })
        val paged = t.requestsTo(Follow4FollowProvider.PATH_ACCOUNT_LIST)
        assertFalse(paged.first().query.containsKey("search"))     // web KHÔNG gửi search
        assertEquals(2, paged.size)
    }

    @Test
    fun deadSessionOnQuickSwitchListIsAuthErrorWithoutFallback() = runTest {
        var expired = 0
        val t = FakeF4fTransport { FakeReply(401, envelope(code = 401, message = "Phiên đăng nhập không hợp lệ")) }
        val r = provider(t, onAuthExpired = { expired++ }).listAccounts() as TaskResult.Error
        assertTrue(r.isAuthError)
        assertEquals(1, expired)
        assertTrue(t.requestsTo(Follow4FollowProvider.PATH_ACCOUNT_LIST).isEmpty())
    }

    @Test
    fun notLoggedInIsAuthErrorWithoutNetwork() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope()) }
        val p = provider(t, token = null)
        val r = p.listAccounts() as TaskResult.Error
        assertTrue(r.isAuthError)
        assertTrue(p.nextJob(acc, both) is TaskResult.Error)
        assertTrue(t.seen.isEmpty())
    }

    // ── nextJob ───────────────────────────────────────────────────────────────

    @Test
    fun nextJobAlwaysSendsUidAndNeverPostsQuickSwitch() = runTest {
        val t = FakeF4fTransport { FakeReply(200, earn(jobs = "[${job(90001, "follow")}]")) }
        val r = provider(t).nextJob(acc, setOf(TaskJobKind.FOLLOW)) as TaskResult.Success
        val j = r.data!!
        assertEquals("90001", j.id)
        assertEquals(TaskJobKind.FOLLOW, j.kind)
        assertEquals(26.0, j.reward, 0.0)
        assertTrue(t.requestsTo(Follow4FollowProvider.PATH_QUICK_SWITCH).isEmpty())
        val q = t.seen.single().query
        assertEquals("tiktok", q["platform"]); assertEquals("follow", q["type"])
        assertFalse(q.containsKey("refresh"))           // lần tải ĐẦU của phiên không gửi refresh
        assertEquals("hoaibao.2009", q["uid"])          // build web mới: LUÔN kèm uid = tên acc
    }

    @Test
    fun switchingToAnotherAccountFetchesWithRefreshAndUidInsteadOfPosting() = runTest {
        // Server đổi accountActive THEO uid (không cần POST): acc đầu rồi acc thứ hai.
        val other = TaskAccount("9000", "acc2", """{"uid":"698000"}""")
        val t = FakeF4fTransport { req ->
            val uid = req.query["uid"]
            if (uid == "acc2") FakeReply(200, earn(activeId = 9000, activeName = "acc2", jobs = "[${job(7, "like")}]"))
            else FakeReply(200, earn(jobs = "[${job(1, "like")}]"))
        }
        val p = provider(t)
        p.nextJob(acc, setOf(TaskJobKind.LIKE))
        val r = p.nextJob(other, setOf(TaskJobKind.LIKE)) as TaskResult.Success
        assertEquals("7", r.data!!.id)
        assertEquals(2, t.seen.size)                    // không có POST nào
        val q = t.seen.last().query
        assertEquals("1", q["refresh"]); assertEquals("acc2", q["uid"])
    }

    @Test
    fun mismatchedActiveAccountRunsLadderStartingWithActiveAcc() = runTest {
        var activated = false
        val t = FakeF4fTransport { req ->
            if (req.method == "POST" && req.path == Follow4FollowProvider.PATH_ACTIVE_ACC) { activated = true; FakeReply(200, envelope()) }
            else FakeReply(200, earn(activeId = if (activated) 8589 else 7777, jobs = "[${job(5, "like")}]"))
        }
        val r = provider(t).nextJob(acc, setOf(TaskJobKind.LIKE)) as TaskResult.Success
        assertEquals("5", r.data!!.id)
        val body = t.requestsTo(Follow4FollowProvider.PATH_ACTIVE_ACC).single().body!!
        assertEquals("8589", body["account_id"].asStr()); assertEquals("tiktok", body["type"].asStr())
        assertTrue(t.requestsTo(Follow4FollowProvider.PATH_QUICK_SWITCH).none { it.method == "POST" })   // chưa cần cách cũ
        val refetch = t.requestsTo(Follow4FollowProvider.PATH_EARN).last().query
        assertEquals("1", refetch["refresh"]); assertEquals("hoaibao.2009", refetch["uid"])
    }

    @Test
    fun ladderFallsBackToNumericUidThenLegacyQuickSwitch() = runTest {
        var legacy = false
        val t = FakeF4fTransport { req ->
            when {
                req.method == "POST" && req.path == Follow4FollowProvider.PATH_ACTIVE_ACC ->
                    FakeReply(200, envelope(code = 404, message = "Không tìm thấy dữ liệu"))
                req.method == "POST" && req.path == Follow4FollowProvider.PATH_QUICK_SWITCH -> { legacy = true; FakeReply(200, envelope()) }
                else -> FakeReply(200, earn(activeId = if (legacy) 8589 else null, jobs = "[${job(9, "follow")}]"))
            }
        }
        val lines = ArrayList<String>()
        val p = provider(t)
        p.setTraceListener { lines.add(it) }
        val r = p.nextJob(acc, setOf(TaskJobKind.FOLLOW)) as TaskResult.Success
        assertEquals("9", r.data!!.id)
        // thứ tự: fetch → S1 POST active-acc → S2 fetch uid số → S3 POST quick-switch → fetch
        val order = t.seen.map { it.method + " " + it.path + (it.query["uid"]?.let { u -> "?uid=$u" } ?: "") }
        assertEquals("GET /user/earn-coin?uid=hoaibao.2009", order[0])
        assertEquals("POST /user/account/active-acc", order[1])
        assertEquals("GET /user/earn-coin?uid=6983264181085832219", order[2])
        assertEquals("POST /user/account/quick-switch", order[3])
        assertTrue(lines.any { it.startsWith("S1 active-acc") })
        assertTrue(lines.any { it.contains("KHỚP") })
        assertTrue(lines.none { it.lowercase().contains("bearer") })            // trace không lộ token
    }

    @Test
    fun stillNullAfterTheWholeLadderIsAnErrorNotACrash() = runTest {
        val t = FakeF4fTransport { req ->
            if (req.method == "POST") FakeReply(200, envelope()) else FakeReply(200, earn(activeId = null))
        }
        val r = provider(t).nextJob(acc, setOf(TaskJobKind.LIKE))
        assertTrue(r is TaskResult.Error)
        assertTrue((r as TaskResult.Error).message.contains("accountActive = null"))
        // đã đi hết thang: V1 + S1 + S2 + S3 + S4 + S5
        assertEquals(6, t.requestsTo(Follow4FollowProvider.PATH_EARN).size)
    }

    @Test
    fun serverKeepingAnotherAccountNamesThatAccount() = runTest {
        val t = FakeF4fTransport { req ->
            if (req.method == "POST") FakeReply(200, envelope()) else FakeReply(200, earn(activeId = 7777, activeName = "someone_else"))
        }
        val r = provider(t).nextJob(acc, setOf(TaskJobKind.LIKE)) as TaskResult.Error
        assertTrue(r.message.contains("@someone_else"))
        assertTrue(r.message.contains("@hoaibao.2009"))
    }

    @Test
    fun deadSessionDuringLadderStopsImmediately() = runTest {
        val t = FakeF4fTransport { FakeReply(401, envelope(code = 401, message = "Phiên đăng nhập không hợp lệ")) }
        val r = provider(t).nextJob(acc, setOf(TaskJobKind.LIKE)) as TaskResult.Error
        assertTrue(r.isAuthError)
        assertEquals(1, t.seen.size)                    // không đi tiếp thang khi token đã chết
    }

    @Test
    fun rateLimitOnFetchWaitsRetryAfterAndRetriesOnce() = runTest {
        var n = 0
        val t = FakeF4fTransport {
            if (n++ == 0) FakeReply(429, envelope("""{"retry_after":4}""", code = 429, message = "Bạn thao tác hơi nhanh"))
            else FakeReply(200, earn(jobs = "[${job(1, "like")}]"))
        }
        val before = currentTime
        val r = provider(t).nextJob(acc, setOf(TaskJobKind.LIKE)) as TaskResult.Success
        assertEquals("1", r.data!!.id)
        assertTrue("phải chờ ≥ 4s, thực tế ${currentTime - before}ms", currentTime - before >= 4_000)
        assertEquals(2, t.seen.size)
    }

    @Test
    fun emptyJobsWithUidTriesOnePlainFetchAndAcceptsItWhenSameAccount() = runTest {
        val t = FakeF4fTransport { req ->
            if (req.query.containsKey("uid")) FakeReply(200, earn(jobs = "[]"))
            else FakeReply(200, earn(jobs = "[${job(3, "follow")}]"))
        }
        val r = provider(t).nextJob(acc, setOf(TaskJobKind.FOLLOW)) as TaskResult.Success
        assertEquals("3", r.data!!.id)
        assertFalse(t.seen.last().query.containsKey("uid"))
    }

    @Test
    fun plainFetchOfAnotherAccountIsNeverUsed() = runTest {
        val t = FakeF4fTransport { req ->
            if (req.query.containsKey("uid")) FakeReply(200, earn(jobs = "[]"))
            else FakeReply(200, earn(activeId = 7777, activeName = "x", jobs = "[${job(3, "follow")}]"))
        }
        val r = provider(t).nextJob(acc, setOf(TaskJobKind.FOLLOW)) as TaskResult.Success
        assertNull(r.data)                              // jobs của acc khác tuyệt đối không được dùng
    }

    @Test
    fun jobsQueueServesNextJobWithoutNetwork() = runTest {
        val t = FakeF4fTransport { FakeReply(200, earn(jobs = "[${job(1, "follow")},${job(2, "follow")}]")) }
        val p = provider(t)
        val kinds = setOf(TaskJobKind.FOLLOW)
        assertEquals("1", ((p.nextJob(acc, kinds) as TaskResult.Success).data)!!.id)
        assertEquals("2", ((p.nextJob(acc, kinds) as TaskResult.Success).data)!!.id)
        assertEquals(1, t.seen.size)            // job thứ 2 lấy từ hàng đợi
        // Hết hàng đợi → gọi lại, lần này có refresh=1 + uid
        p.nextJob(acc, kinds)
        val q = t.seen.last().query
        assertEquals(2, t.seen.size)
        assertEquals("1", q["refresh"]); assertEquals("hoaibao.2009", q["uid"])
    }

    @Test
    fun staleQueueIsDiscarded() = runTest {
        var now = 1_000_000L
        val t = FakeF4fTransport { FakeReply(200, earn(jobs = "[${job(1, "follow")},${job(2, "follow")}]")) }
        val p = provider(t, clock = { now })
        p.nextJob(acc, setOf(TaskJobKind.FOLLOW))
        now += 6 * 60_000L                       // quá 5 phút → job cũ có thể đã bị thu hồi
        p.nextJob(acc, setOf(TaskJobKind.FOLLOW))
        assertEquals(2, t.requestsTo(Follow4FollowProvider.PATH_EARN).size)
    }

    @Test
    fun bothKindsAreAlternated() = runTest {
        val t = FakeF4fTransport { req ->
            val type = req.query["type"]
            FakeReply(200, earn(jobs = "[${job(if (type == "like") 11 else 22, type!!)}]"))
        }
        val p = provider(t)
        val first = (p.nextJob(acc, both) as TaskResult.Success).data!!
        val second = (p.nextJob(acc, both) as TaskResult.Success).data!!
        assertTrue(first.kind != second.kind)
    }

    @Test
    fun emptyJobsIsSuccessNullWithReason() = runTest {
        val t = FakeF4fTransport { FakeReply(200, earn(jobs = "[]")) }
        val p = provider(t)
        val r = p.nextJob(acc, setOf(TaskJobKind.LIKE)) as TaskResult.Success
        assertNull(r.data)
        assertTrue(p.noJobReason(acc).contains("like"))
    }

    @Test
    fun dailyLimitReachedStopsFetchingForThatAccount() = runTest {
        val t = FakeF4fTransport {
            FakeReply(200, earn(jobs = "[${job(1, "follow")}]", usage = """{"done":50,"limit":50,"remaining":0,"reached":true}"""))
        }
        val p = provider(t)
        val r = p.nextJob(acc, setOf(TaskJobKind.FOLLOW)) as TaskResult.Success
        assertNull(r.data)
        assertTrue(p.noJobReason(acc).contains("50/50"))
        p.nextJob(acc, setOf(TaskJobKind.FOLLOW))
        assertEquals(1, t.seen.size)            // lần 2 không gọi mạng nữa
    }

    @Test
    fun listAccountsResetsSessionState() = runTest {
        val t = FakeF4fTransport { req ->
            if (req.path == Follow4FollowProvider.PATH_QUICK_SWITCH) FakeReply(200, quickSwitch(8589 to "hoaibao.2009"))
            else FakeReply(200, earn(jobs = "[${job(1, "follow")}]", usage = """{"done":50,"limit":50,"remaining":0,"reached":true}"""))
        }
        val p = provider(t)
        p.nextJob(acc, setOf(TaskJobKind.FOLLOW))
        p.listAccounts()                          // phiên mới (vd sang ngày mới) → hết trạng thái "đã hết lượt"
        p.nextJob(acc, setOf(TaskJobKind.FOLLOW))
        assertEquals(2, t.requestsTo(Follow4FollowProvider.PATH_EARN).size)
    }

    // ── onJobOpening / complete / skip ────────────────────────────────────────

    private val likeJob = TaskJob("90001", TaskJobKind.LIKE, "https://www.tiktok.com/@a/video/1", 10.0, """{"object_id":"770137211464803581"}""")
    private val followJob = TaskJob("90002", TaskJobKind.FOLLOW, "https://www.tiktok.com/@kim", 26.0, """{"object_id":"kim_truong.28"}""")

    @Test
    fun markStartOnlyForLikeJobs() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope()) }
        val p = provider(t)
        p.onJobOpening(acc, followJob)
        assertTrue(t.seen.isEmpty())
        p.onJobOpening(acc, likeJob)
        val body = t.requestsTo(Follow4FollowProvider.PATH_MARK_START).single().body!!
        assertEquals("90001", body["job_id"].asStr()); assertEquals("8589", body["account_id"].asStr())
        assertEquals(2, body.size)
    }

    @Test
    fun markStartFailureNeverBreaksTheFlow() = runTest {
        val t = FakeF4fTransport { null }          // mất mạng
        provider(t).onJobOpening(acc, likeJob)       // không ném exception là đạt
    }

    @Test
    fun completeBodyMatchesWebAndReportsBalance() = runTest {
        var bal: Pair<String?, String?>? = null
        // Response THẬT của complete-job (API reference v2.7): coin_received/current_balance/balance_pending là SỐ.
        val t = FakeF4fTransport {
            FakeReply(200, envelope("""{"coin_received":24,"current_balance":1250,"balance_pending":230,
                "account_daily_usage":{"done":4,"limit":150,"remaining":146}}""", message = "Bạn đã nhận được 24 xu thành công"))
        }
        val p = provider(t, onBalance = { b, pend -> bal = b to pend })
        val r = p.complete(acc, followJob) as TaskResult.Success
        assertEquals(24.0, r.data, 0.0)               // lấy coin_received của server, KHÔNG phải reward lúc hiển thị (26)
        val body = t.requestsTo(Follow4FollowProvider.PATH_COMPLETE).single().body!!
        assertEquals(setOf("job_id", "account_id", "type", "type_job"), body.keys)   // KHÔNG gửi uid
        assertEquals("90002", body["job_id"].asStr()); assertEquals("8589", body["account_id"].asStr())
        assertEquals("tiktok", body["type"].asStr()); assertEquals("follow", body["type_job"].asStr())
        assertEquals("1250" to "230", bal)
    }

    @Test
    fun completeFallsBackToJobRewardWhenServerSendsNoCoinReceived() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope("""{"current_balance":10}""")) }
        val r = provider(t).complete(acc, followJob) as TaskResult.Success
        assertEquals(26.0, r.data, 0.0)
    }

    @Test
    fun idsAreSentAsNumbers() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope()) }
        provider(t).complete(acc, followJob)
        val body = t.seen.single().body!!
        assertFalse((body["job_id"] as JsonPrimitive).isString)
        assertFalse((body["account_id"] as JsonPrimitive).isString)
    }

    @Test
    fun skipBodyMatchesWeb() = runTest {
        val t = FakeF4fTransport { FakeReply(200, """{"status_code":200,"success":true,"message":"Đã bỏ qua"}""") }
        val r = provider(t).skip(acc, followJob)
        assertTrue(r is TaskResult.Success)
        val body = t.requestsTo(Follow4FollowProvider.PATH_SKIP).single().body!!
        assertEquals(setOf("job_id", "account_id", "uid", "object_id", "type"), body.keys)
        assertEquals("6983264181085832219", body["uid"].asStr())
        assertEquals("kim_truong.28", body["object_id"].asStr())
    }

    @Test
    fun completeWaitsForMinimumDwellAfterOpening() = runTest {
        val now = 1_000_000L
        val t = FakeF4fTransport { FakeReply(200, envelope()) }
        val p = Follow4FollowProvider(
            api = F4fApiClient(t, tokenProvider = { "tok" }, nowSecs = { 1_793_087_078L }),
            nowMs = { now }, minDwellMs = 40L,
        )
        p.onJobOpening(acc, followJob)
        val before = currentTime                 // thời gian ẢO của runTest — delay() không đợi thật
        p.complete(acc, followJob)
        val waitedMs = currentTime - before
        assertTrue("phải đợi đủ 40ms kể từ lúc mở link, thực tế $waitedMs", waitedMs >= 40)
    }

    // ── Lỗi ───────────────────────────────────────────────────────────────────

    @Test
    fun businessErrorGetsHint() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope(code = 400, message = "Tài khoản @x chưa làm job này, vui lòng thử lại!")) }
        val r = provider(t).complete(acc, followJob) as TaskResult.Error
        assertFalse(r.isAuthError)
        assertEquals(0, r.cooldownSecs)
        assertTrue(r.message.contains("chưa làm job này"))
        assertTrue(r.message.contains("chưa follow/like thật"))
    }

    @Test
    fun rateLimitBecomesCooldown() = runTest {
        val t = FakeF4fTransport { FakeReply(429, envelope("""{"retry_after":9}""", code = 429, message = "Bạn thao tác hơi nhanh")) }
        val r = provider(t).complete(acc, followJob) as TaskResult.Error
        assertEquals(9, r.cooldownSecs)
        assertFalse(r.isAuthError)
    }

    @Test
    fun unauthorizedIsAuthErrorAndExpiresSession() = runTest {
        var expired = 0
        val t = FakeF4fTransport { FakeReply(401, envelope(code = 401, message = "Chức năng này cần đăng nhập")) }
        val r = provider(t, onAuthExpired = { expired++ }).nextJob(acc, setOf(TaskJobKind.LIKE)) as TaskResult.Error
        assertTrue(r.isAuthError)
        assertEquals(1, expired)
    }

    @Test
    fun quotaMessageOnCompleteStopsTheAccount() = runTest {
        val t = FakeF4fTransport { req ->
            if (req.path == Follow4FollowProvider.PATH_COMPLETE) FakeReply(200, envelope(code = 400, message = "Bạn đã hết quota hôm nay"))
            else FakeReply(200, earn(jobs = "[${job(1, "follow")}]"))
        }
        val p = provider(t)
        p.complete(acc, followJob)
        val r = p.nextJob(acc, setOf(TaskJobKind.FOLLOW)) as TaskResult.Success
        assertNull(r.data)
        assertEquals(0, t.requestsTo(Follow4FollowProvider.PATH_EARN).size)
    }

    @Test
    fun jobGoneIsFlaggedOnCompleteAndSkip() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope(code = 404, message = "Không tìm thấy dữ liệu")) }
        val p = provider(t)
        assertTrue((p.complete(acc, followJob) as TaskResult.Error).jobGone)
        assertTrue((p.skip(acc, followJob) as TaskResult.Error).jobGone)
    }

    @Test
    fun otherBusinessErrorsAreNotJobGone() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope(code = 400, message = "Tài khoản @x chưa làm job này")) }
        assertFalse((provider(t).complete(acc, followJob) as TaskResult.Error).jobGone)
    }

    @Test
    fun networkErrorIsNotAnAuthError() = runTest {
        val t = FakeF4fTransport { null }
        val r = provider(t).nextJob(acc, setOf(TaskJobKind.LIKE)) as TaskResult.Error
        assertFalse(r.isAuthError)
        assertTrue(r.message.startsWith("Lỗi mạng"))
        assertEquals(1, t.seen.size)                    // mất mạng → không chạy thang đổi acc
    }
}
