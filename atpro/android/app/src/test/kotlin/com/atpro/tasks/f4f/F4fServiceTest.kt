package com.atpro.tasks.f4f

import com.atpro.tasks.TaskResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class F4fServiceTest {

    private fun service(
        transport: FakeF4fTransport,
        token: String? = "tok",
        onAuthExpired: () -> Unit = {},
    ) = F4fService(
        api = F4fApiClient(transport, tokenProvider = { token }, nowSecs = { 1_793_087_078L }),
        onAuthExpired = onAuthExpired,
    )

    private inline fun <reified T> TaskResult<T>.ok(): T = (this as TaskResult.Success).data

    // ── Tổng quan ─────────────────────────────────────────────────────────────

    @Test
    fun dashboardParsesNumbersAndStringsAndLevelObject() = runTest {
        val t = FakeF4fTransport {
            FakeReply(200, envelope("""{"balance":"1250 coin","balance_pending":230,"total_coin":9000,
                "level":{"name":"Bạc","icon":"x"},"today_earned":"104","tasks_done_today":4}"""))
        }
        val d = service(t).dashboard().ok()
        assertEquals("1250 coin", d.balance); assertEquals("230", d.balancePending)
        assertEquals(9000L, d.totalCoin); assertEquals("Bạc", d.levelName); assertEquals(4, d.tasksDoneToday)
        assertEquals("/user/dashboard", t.seen.single().path)
    }

    @Test
    fun levelCanBeAPlainString() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope("""{"balance":"0","level":"Vàng"}""")) }
        assertEquals("Vàng", service(t).dashboard().ok().levelName)
    }

    @Test
    fun earnStatsSendsTypeAndParses() = runTest {
        val t = FakeF4fTransport {
            FakeReply(200, envelope("""{"total_jobs":10,"today_jobs":2,"total_pending":"5","today_pending":"1","total_approved":"99","today_approved":"7"}"""))
        }
        val s = service(t).earnStats().ok()
        assertEquals("tiktok", t.seen.single().query["type"])
        assertEquals(10, s.totalJobs); assertEquals("99", s.totalApproved)
    }

    @Test
    fun jobRewardsAndRate() = runTest {
        val t = FakeF4fTransport { req ->
            if (req.path == "/user/rate") FakeReply(200, envelope("""{"rate":26000,"from":"USD","to":"COIN"}"""))
            else FakeReply(200, envelope("""[{"platform":"tiktok","type_job":"follow","key":"tiktok-follow","jobs":50,"remains":47,"max_reward_formatted":"26"}]"""))
        }
        val s = service(t)
        val rewards = s.jobRewards().ok()
        assertEquals(1, rewards.size); assertEquals(47, rewards[0].remains); assertEquals("26", rewards[0].maxRewardFormatted)
        assertEquals(26000L, s.rate().ok().rate)
    }

    // ── Bảng xếp hạng ─────────────────────────────────────────────────────────

    @Test
    fun leaderboardSendsLimitNotPaging() = runTest {
        val t = FakeF4fTransport {
            FakeReply(200, envelope("""{"leaderboard":[
                {"rank":1,"username":"alpha","coins":5000,"avatar":"a.png"},
                {"rank":2,"username":"beta","coins":"4200","avatar":""}],"my_rank":7}"""))
        }
        val lb = service(t).leaderboard(10).ok()
        val q = t.seen.single().query
        assertEquals("10", q["limit"]); assertFalse(q.containsKey("page")); assertFalse(q.containsKey("per_page"))
        assertEquals(2, lb.entries.size)
        assertEquals("alpha", lb.entries[0].username); assertEquals(5000L, lb.entries[0].coins)
        assertEquals(4200L, lb.entries[1].coins); assertNull(lb.entries[1].avatar)    // avatar rỗng → null
        assertEquals(7, lb.myRank)
    }

    @Test
    fun leaderboardAcceptsCapitalisedKeysAndBareArray() = runTest {
        val t1 = FakeF4fTransport {
            FakeReply(200, envelope("""{"Leaderboard":[{"Rank":1,"Username":"x","Coins":10}],"MyRank":3}"""))
        }
        val a = service(t1).leaderboard().ok()
        assertEquals("x", a.entries[0].username); assertEquals(3, a.myRank)

        val t2 = FakeF4fTransport { FakeReply(200, envelope("""[{"username":"y","coins":5},{"username":"z","coins":4}]""")) }
        val b = service(t2).leaderboard().ok()
        assertEquals(listOf(1, 2), b.entries.map { it.rank })      // thiếu rank → đánh số theo thứ tự
        assertNull(b.myRank)
    }

    // ── Lịch sử ───────────────────────────────────────────────────────────────

    @Test
    fun earnHistoryAlwaysSendsAction() = runTest {
        val t = FakeF4fTransport {
            FakeReply(200, envelope("""{"data":[{"id":1,"job_id":55,"object_id_job":"kim","link_job":"https://t/","coin_received":"26",
                "status_label":"Chờ duyệt","created_at":"2026-10-28 10:00"}],"per_page":10,"total_page":3,"current_page":2,"total":25}"""))
        }
        val p = service(t).earnHistory("follow", page = 2).ok()
        val q = t.seen.single().query
        assertEquals("follow", q["action"]); assertEquals("2", q["page"]); assertEquals("tiktok", q["type"])
        assertEquals(1, p.items.size); assertEquals("26", p.items[0].coin); assertEquals("Chờ duyệt", p.items[0].statusLabel)
        assertEquals(2, p.page); assertEquals(3, p.totalPages); assertTrue(p.hasMore)
    }

    @Test
    fun emptyPageIsValid() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope("""{"data":[],"total_page":0,"current_page":1,"total":0}""")) }
        val p = service(t).earnHistory("like").ok()
        assertTrue(p.items.isEmpty()); assertFalse(p.hasMore)
    }

    // ── Rút tiền ──────────────────────────────────────────────────────────────

    @Test
    fun withdrawStatsAndHistory() = runTest {
        val t = FakeF4fTransport { req ->
            if (req.path == "/user/withdraw/statistical")
                FakeReply(200, envelope("""{"amountPendingWithdraw":100,"amountCompletedWithdraw":2500,"amountRejectedWithdraw":0}"""))
            else FakeReply(200, envelope("""{"data":[{"id":9,"type":"bank","bank_name":"VCB","account_name":"A","account_number":"123",
                "amount":"500","amount_received":"480","status":"pending","created_at":"2026-10-28"}],"total_page":1,"current_page":1,"total":1}"""))
        }
        val s = service(t)
        val st = s.withdrawStats().ok()
        assertEquals(100L, st.pending); assertEquals(2500L, st.completed); assertEquals(0L, st.rejected)
        val h = s.withdrawHistory().ok()
        assertEquals("VCB", h.items[0].bankName); assertEquals("pending", h.items[0].status)
        assertEquals("10", t.requestsTo("/user/withdraw/list").single().query["per_page"])
    }

    @Test
    fun withdrawInfoReadsOptionsLeniently() = runTest {
        val t = FakeF4fTransport {
            FakeReply(200, envelope("""{"banks":[{"name":"Vietcombank"},{"label":"ACB"}],"networks":["bep20","trc20"],"telcos":{"viettel":1,"vina":2}}"""))
        }
        val i = service(t).withdrawInfo().ok()
        assertEquals(listOf("Vietcombank", "ACB"), i.banks)
        assertEquals(listOf("bep20", "trc20"), i.networks)
        assertEquals(listOf("viettel", "vina"), i.telcos)
    }

    @Test
    fun withdrawInfoWithUnknownSchemaIsEmptyNotAnError() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope("""{"something":"else"}""")) }
        val i = service(t).withdrawInfo().ok()
        assertTrue(i.banks.isEmpty() && i.networks.isEmpty() && i.telcos.isEmpty())
    }

    @Test
    fun bankWithdrawBodyAndPath() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope(message = "Đã gửi yêu cầu rút tiền thành công")) }
        val r = service(t).withdraw(F4fWithdrawRequest.Bank(" Vietcombank ", "NGUYEN VAN A", "0123456789", 500)).ok()
        assertEquals("Đã gửi yêu cầu rút tiền thành công", r)
        val req = t.seen.single()
        assertEquals("/user/withdraw/bank", req.path)
        assertEquals(setOf("bank_name", "account_name", "account_number", "amount"), req.body!!.keys)
        assertEquals("Vietcombank", req.body!!["bank_name"].asStr())          // đã trim
        assertEquals(500L, req.body!!["amount"].asLong())
        assertFalse((req.body!!["amount"] as kotlinx.serialization.json.JsonPrimitive).isString)   // số nguyên
    }

    @Test
    fun zaloPayUsesBankShape() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope()) }
        service(t).withdraw(F4fWithdrawRequest.ZaloPay("A", "0901234567", 300))
        val req = t.seen.single()
        assertEquals("/user/withdraw/zalopay", req.path)
        assertEquals(setOf("bank_name", "account_name", "account_number", "amount"), req.body!!.keys)
        assertEquals("0901234567", req.body!!["account_number"].asStr())
    }

    @Test
    fun payPalUsesEmailField() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope()) }
        service(t).withdraw(F4fWithdrawRequest.PayPal("a@b.com", 200))
        val req = t.seen.single()
        assertEquals("/user/withdraw/paypal", req.path)
        assertEquals(setOf("email", "amount"), req.body!!.keys)
    }

    @Test
    fun cryptoUsesWalletAddressField() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope()) }
        service(t).withdraw(F4fWithdrawRequest.Crypto("bep20", "0x1234567890abcdef1234", 400))
        val req = t.seen.single()
        assertEquals("/user/withdraw/crypto", req.path)
        assertEquals(setOf("network", "wallet_address", "amount"), req.body!!.keys)
    }

    @Test
    fun cardUsesTelco() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope()) }
        service(t).withdraw(F4fWithdrawRequest.Card("Viettel", 100))
        val req = t.seen.single()
        assertEquals("/user/withdraw/card", req.path)
        assertEquals(setOf("telco", "amount"), req.body!!.keys)
    }

    @Test
    fun invalidRequestsNeverReachTheNetwork() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope()) }
        val s = service(t)
        val bad = listOf(
            F4fWithdrawRequest.Bank("", "A", "1", 100),
            F4fWithdrawRequest.Bank("VCB", "A", "1", 0),
            F4fWithdrawRequest.Bank("VCB", "A", "1", -5),
            F4fWithdrawRequest.PayPal("khong-phai-email", 100),
            F4fWithdrawRequest.Crypto("bep20", "0x12", 100),
            F4fWithdrawRequest.Card("", 100),
            F4fWithdrawRequest.ZaloPay("A", "", 100),
        )
        bad.forEach { assertTrue(s.withdraw(it) is TaskResult.Error) }
        assertTrue(t.seen.isEmpty())
    }

    @Test
    fun withdrawIsNeverRetriedAfterANetworkError() = runTest {
        val t = FakeF4fTransport { null }
        val r = service(t).withdraw(F4fWithdrawRequest.PayPal("a@b.com", 200))
        assertTrue(r is TaskResult.Error)
        assertEquals(1, t.seen.size)           // đúng 1 lần gửi → không thể rút trùng
    }

    @Test
    fun withdrawServerRejectionShowsServerMessage() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope(code = 400, message = "Số dư không đủ")) }
        val r = service(t).withdraw(F4fWithdrawRequest.Card("Viettel", 100)) as TaskResult.Error
        assertEquals("Số dư không đủ", r.message)
    }

    @Test
    fun confirmationSummaryListsEveryField() {
        val s = F4fWithdrawRequest.Bank("VCB", "A", "123", 500).summary().toMap()
        assertEquals("VCB", s["Ngân hàng"]); assertEquals("123", s["Số tài khoản"]); assertEquals("500", s["Số coin"])
        assertEquals("BEP20", F4fWithdrawRequest.Crypto("bep20", "0xabcdefghij", 1).summary().toMap()["Mạng"])
    }

    // ── Giới thiệu / thông báo ────────────────────────────────────────────────

    @Test
    fun referralSummaryAndClaimAll() = runTest {
        val t = FakeF4fTransport { req ->
            if (req.path == "/user/referral/summary")
                FakeReply(200, envelope("""{"ref_code":"ABC123","ref_link":"https://f/ref/ABC123","f1_count":4,"f2_count":9,
                    "total_commission":"300","pending_commission":"120","claimed_commission":"180","enabled":true}"""))
            else FakeReply(200, envelope(message = "Đã nhận 120 coin"))
        }
        val s = service(t)
        val r = s.referralSummary().ok()
        assertEquals("ABC123", r.refCode); assertEquals(4, r.f1Count); assertEquals("120", r.pendingCommission); assertTrue(r.enabled)
        assertEquals("Đã nhận 120 coin", s.claimAllCommissions().ok())
        val claim = t.requestsTo("/user/referral/claim-all").single()
        assertEquals("POST", claim.method)
    }

    @Test
    fun notificationsParseListAndUnread() = runTest {
        val t = FakeF4fTransport {
            FakeReply(200, envelope("""{"data":[{"id":3,"type":"system","title":"Chào","message":"Hi","read":false,"created_at":"2026-10-28"},
                {"id":2,"type":"system","title":"Cũ","message":"x","read":true,"created_at":"2026-10-27"}],"unread":1}"""))
        }
        val n = service(t).notifications().ok()
        assertEquals(2, n.items.size); assertEquals(1, n.unread)
        assertFalse(n.items[0].read); assertTrue(n.items[1].read)
    }

    // ── Lỗi chung ─────────────────────────────────────────────────────────────

    @Test
    fun notLoggedInNeverCallsNetwork() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope()) }
        val s = service(t, token = null)
        assertTrue((s.dashboard() as TaskResult.Error).isAuthError)
        assertTrue((s.withdraw(F4fWithdrawRequest.Card("Viettel", 100)) as TaskResult.Error).isAuthError)
        assertTrue(t.seen.isEmpty())
    }

    @Test
    fun unauthorizedExpiresSession() = runTest {
        var expired = 0
        val t = FakeF4fTransport { FakeReply(401, envelope(code = 401, message = "Chức năng này cần đăng nhập")) }
        val r = service(t, onAuthExpired = { expired++ }).leaderboard() as TaskResult.Error
        assertTrue(r.isAuthError); assertEquals(1, expired)
    }

    @Test
    fun rateLimitSurfacesCooldown() = runTest {
        val t = FakeF4fTransport { FakeReply(429, envelope("""{"retry_after":12}""", code = 429, message = "Bạn thao tác hơi nhanh")) }
        assertEquals(12, (service(t).notifications() as TaskResult.Error).cooldownSecs)
    }

    @Test
    fun unexpectedShapeIsAReadableError() = runTest {
        val t = FakeF4fTransport { FakeReply(200, envelope("\"just a string\"")) }
        val r = service(t).dashboard() as TaskResult.Error
        assertNotNull(r.message); assertTrue(r.message.contains("/user/dashboard"))
    }
}
