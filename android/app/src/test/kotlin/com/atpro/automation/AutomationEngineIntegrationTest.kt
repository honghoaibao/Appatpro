package com.atpro.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.atpro.accessibility.NodeTraverser
import com.atpro.network.LanWebSocketServer
import com.atpro.data.FarmConfig
import com.atpro.data.IFarmRepository
import com.atpro.data.OverlayFarmMonitor
import com.atpro.db.entity.AccountEntity
import com.atpro.notification.AtProNotificationManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/**
 * AutomationEngineIntegrationTest — farm loop behavior tests.
 *
 * Phạm vi (khác AutomationEngineTest):
 *   ✅ farmOneAccount: switch fail → checkpoint + close empty session
 *   ✅ farmOneAccount: feed load fail → switch fail path
 *   ✅ farmOneAccount: switch success → video loop → checkpoint detected → break
 *   ✅ farmOneAccount: switch success + minutesPerAccount=0 → immediate close
 *   ✅ Multiple accounts: all processed in order
 *
 * Kỹ thuật:
 *   - mockkObject(NodeTraverser): mock toàn bộ static-like calls
 *   - mockkObject(AtProNotificationManager / OverlayFarmMonitor): silence side-effects
 *   - mockk<AccessibilityNodeInfo>(relaxed=true): tạo NodeResult mà không cần Android runtime
 *   - UnconfinedTestDispatcher: coroutines chạy eager, delay() không block
 *
 * Giới hạn:
 *   ❌ doLike(), doFollow() — không thể test vì phụ thuộc Random + clickSuspend sequence
 *   ❌ recoverToFeed() detail — covered riêng khi có thêm fixtures
 *   → TASK-011: Robolectric tests cho các path cần AccessibilityNodeInfo tree thật
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationEngineIntegrationTest {

    // ── Doubles ───────────────────────────────────────────────

    private lateinit var host:   IFarmHost
    private lateinit var repo:   IFarmRepository
    private lateinit var engine: AutomationEngine

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope      = TestScope(testDispatcher)

    /** Fake AccessibilityNodeInfo — chỉ dùng làm placeholder trong NodeResult */
    private val fakeNode: AccessibilityNodeInfo = mockk(relaxed = true)

    /** NodeResult clickable — dùng cho switch button và account button */
    private fun clickableResult(text: String) = NodeTraverser.NodeResult(
        node        = fakeNode,
        bounds      = Rect(0, 0, 400, 80),
        text        = text,
        resourceId  = null,
        className   = "android.widget.TextView",
        isClickable = true,
        isEnabled   = true,
    )

    @Before
    fun setUp() {
        // Silence all singletons
        mockkObject(LanWebSocketServer)
        every { LanWebSocketServer.broadcast(any(), any()) } just Runs

        mockkObject(AtProNotificationManager)
        coEvery { AtProNotificationManager.notifyFarmStarted(any())                              } just Runs
        coEvery { AtProNotificationManager.notifyFarmCompleted(any(), any(), any(), any(), any()) } just Runs
        coEvery { AtProNotificationManager.notifySessionDone(any(), any(), any(), any())         } just Runs
        coEvery { AtProNotificationManager.notifyCheckpoint(any())                               } just Runs

        mockkObject(OverlayFarmMonitor)
        every  { OverlayFarmMonitor.show(any())          } just Runs
        every  { OverlayFarmMonitor.hide()               } just Runs
        every  { OverlayFarmMonitor.update(any(), any(), any(), any(), any(), any()) } just Runs

        // NodeTraverser: safe defaults — no popup, no nav, no checkpoint
        mockkObject(NodeTraverser)
        every { NodeTraverser.hasNavBar(any())       } returns false
        every { NodeTraverser.detectCheckpoint(any()) } returns false
        every { NodeTraverser.detectLive(any())      } returns false
        every { NodeTraverser.detectPopup(any())     } returns
            NodeTraverser.PopupInfo(false, NodeTraverser.PopupType.NONE, null)
        every { NodeTraverser.findByText(any(), any(), any(), any()) } returns null
        every { NodeTraverser.parseAccountList(any()) } returns emptyList()

        // Host: all gestures succeed, screen exists
        host = mockk {
            every  { scope }        returns testScope
            every  { screenWidth }  returns 1080
            every  { screenHeight } returns 2400
            every  { getRootNode() } returns null
            every  { pressBack()   } returns true
            every  { launchTikTok()         } returns false  // default: fail
            every  { openTikTokSettings()   } returns true
            coEvery { killTikTok()           } just Runs
            every  { showFarmOverlay()      } just Runs
            every  { hideFarmOverlay()      } just Runs
            coEvery { clickNode(any())           } returns true
            coEvery { clickSuspend(any(), any()) } returns true
            coEvery { swipeSuspend(any(), any(), any(), any(), any()) } returns true
            every  { typeText(any(), any()) } returns true
        }

        repo = mockk {
            coEvery { loadFarmConfig()  } returns FarmConfig()   // default: minutesPerAccount=30
            coEvery { getAccounts()     } returns emptyList()
            coEvery { addAccount(any()) } just Runs
            coEvery { startSession(any()) } returns 1L
            coEvery { closeSession(any(), any(), any(), any(), any(), any()) } just Runs
            coEvery { setCheckpoint(any(), any()) } just Runs
            coEvery { log(any(), any(), any())    } just Runs
        }

        engine = AutomationEngine(host, repo)
    }

    @After
    fun tearDown() { unmockkAll() }

    // ── Tests ─────────────────────────────────────────────────

    /**
     * SCENARIO A: launchTikTok() trả false ngay lúc mở TikTok để switch account.
     *
     * Mong đợi:
     *   - Session được bắt đầu (startSession called) vì session tracking bắt đầu trước khi switch
     *   - setCheckpoint(true) được gọi vì switch fail
     *   - closeSession được gọi với likes=0, follows=0, videos=0
     *   - isFarming = false sau khi farm hoàn thành
     */
    @Test
    fun `farmLoop when launchTikTok fails then checkpoint set and session closed empty`() {
        every { host.launchTikTok() } returns false

        engine.startFarm(listOf("user1"))

        // UnconfinedTestDispatcher → coroutine chạy xong synchronously
        assertFalse("isFarming phải false", engine.isFarming)
        coVerify { repo.startSession("user1") }
        coVerify { repo.setCheckpoint("user1", true) }
        coVerify { AtProNotificationManager.notifyCheckpoint("user1") }
        coVerify { repo.closeSession(1L, "user1", 0, 0, 0, 0) }
    }

    /**
     * SCENARIO B: TikTok mở được (launchTikTok=true) nhưng feed không load.
     *
     * waitFeedLoad() lặp 6 lần, mỗi lần check hasNavBar → false.
     * → switchToAccountViaSettings returns false
     * → checkpoint + empty session.
     *
     * Với UnconfinedTestDispatcher, 6 × delay(3000) chạy ngay lập tức.
     */
    @Test
    fun `farmLoop when feed never loads then switch fails and session closed empty`() {
        every { host.launchTikTok() } returns true
        every { NodeTraverser.hasNavBar(any()) } returns false   // feed không bao giờ load

        engine.startFarm(listOf("user1"))

        assertFalse(engine.isFarming)
        coVerify { repo.setCheckpoint("user1", true) }
        coVerify { repo.closeSession(1L, "user1", 0, 0, 0, 0) }
    }

    /**
     * SCENARIO C: Switch thành công, minutesPerAccount=0.
     *
     * deadline = System.currentTimeMillis() + 0 = now
     * → video while loop: `System.currentTimeMillis() < deadline` immediately false
     * → closeSession gọi với stats thực tế (0 videos vì loop không chạy)
     * → KHÔNG gọi setCheckpoint
     */
    @Test
    fun `farmLoop when switch succeeds and minutesPerAccount is 0 then session closes cleanly`() {
        coEvery { repo.loadFarmConfig() } returns FarmConfig(minutesPerAccount = 0)
        configureSuccessfulSwitch("user1")

        engine.startFarm(listOf("user1"))

        assertFalse(engine.isFarming)
        coVerify { repo.closeSession(1L, "user1", 0, 0, 0, 0) }
        // Không set checkpoint — bình thường
        coVerify(exactly = 0) { repo.setCheckpoint(any(), true) }
    }

    /**
     * SCENARIO D: Switch thành công, checkpoint phát hiện trong video loop.
     *
     * hasNavBar → true (feed OK, not lost)
     * detectCheckpoint → true (phát hiện ngay lần đầu)
     * → loop break → setCheckpoint(true) + closeSession
     *
     * minutesPerAccount = 1 → deadline 1 phút tương lai → loop chạy ít nhất 1 lần.
     */
    @Test
    fun `farmLoop when checkpoint detected in video loop then breaks and marks checkpoint`() {
        coEvery { repo.loadFarmConfig() } returns FarmConfig(minutesPerAccount = 1)
        configureSuccessfulSwitch("user1")
        every { NodeTraverser.hasNavBar(any()) } returns true
        every { NodeTraverser.detectCheckpoint(any()) } returns true  // phát hiện ngay lần đầu

        engine.startFarm(listOf("user1"))

        assertFalse(engine.isFarming)
        coVerify { repo.setCheckpoint("user1", true) }
        coVerify { AtProNotificationManager.notifyCheckpoint("user1") }
        // Session vẫn được đóng sau khi break
        coVerify { repo.closeSession(1L, "user1", any(), any(), any(), any()) }
    }

    /**
     * SCENARIO E: Nhiều accounts — tất cả được xử lý theo thứ tự.
     *
     * Dùng launchTikTok=false để mỗi account fail nhanh.
     * Verify: startSession và setCheckpoint được gọi đúng số lần, đúng account.
     */
    @Test
    fun `farmLoop with multiple accounts processes all accounts in order`() {
        every { host.launchTikTok() } returns false
        // Trả về session ID khác nhau cho từng account
        coEvery { repo.startSession("userA") } returns 1L
        coEvery { repo.startSession("userB") } returns 2L
        coEvery { repo.startSession("userC") } returns 3L

        engine.startFarm(listOf("userA", "userB", "userC"))

        assertFalse(engine.isFarming)

        // Tất cả 3 accounts được process
        coVerify(exactly = 1) { repo.startSession("userA") }
        coVerify(exactly = 1) { repo.startSession("userB") }
        coVerify(exactly = 1) { repo.startSession("userC") }

        // Tất cả đều được đánh checkpoint vì switch fail
        coVerify(exactly = 1) { repo.setCheckpoint("userA", true) }
        coVerify(exactly = 1) { repo.setCheckpoint("userB", true) }
        coVerify(exactly = 1) { repo.setCheckpoint("userC", true) }

        // Thứ tự đúng: A trước B trước C
        coVerifyOrder {
            repo.startSession("userA")
            repo.startSession("userB")
            repo.startSession("userC")
        }
    }

    /**
     * SCENARIO F: Auto-save accounts từ popup switch.
     *
     * Khi NodeTraverser.parseAccountList trả về accounts,
     * các account chưa có trong DB phải được addAccount().
     */
    @Test
    fun `farmLoop when switch popup shows accounts then auto-saves new ones`() {
        every { host.launchTikTok() } returns true
        // Feed load thành công (lần 1 cho waitFeedLoad ban đầu)
        every { NodeTraverser.hasNavBar(any()) } returns true

        // Switch button tìm thấy
        every { NodeTraverser.findByText(any(), any(), any(), any()) } answers {
            val text = secondArg<String>().lowercase()
            when {
                "chuyển đổi" in text -> clickableResult("Chuyển đổi tài khoản")
                text == "user1"      -> clickableResult("@user1")
                else                 -> null
            }
        }

        // Popup trả về 3 accounts — user1 đã có, newUser1 và newUser2 chưa có
        every { NodeTraverser.parseAccountList(any()) } returns listOf("user1", "newUser1", "newUser2")
        coEvery { repo.getAccounts() } returns listOf(
            AccountEntity(username = "user1", status = "active")
        )

        coEvery { repo.loadFarmConfig() } returns FarmConfig(minutesPerAccount = 0)

        engine.startFarm(listOf("user1"))

        // Chỉ 2 account mới được lưu, user1 bỏ qua vì đã tồn tại
        coVerify(exactly = 1) { repo.addAccount("newUser1") }
        coVerify(exactly = 1) { repo.addAccount("newUser2") }
        coVerify(exactly = 0) { repo.addAccount("user1") }
    }

    // ── Helpers ───────────────────────────────────────────────

    /**
     * Cấu hình mock để switch account thành công.
     *
     * Flow mô phỏng:
     *   1. launchTikTok() → true
     *   2. waitFeedLoad() → hasNavBar → true (lần 1)
     *   3. findByText("chuyển đổi tài khoản") → trả về nút switch
     *   4. parseAccountList → emptyList (không auto-save)
     *   5. findByText(account) → trả về account node
     *   6. killTikTok + launchTikTok + waitFeedLoad lần 2 → hasNavBar → true
     */
    private fun configureSuccessfulSwitch(account: String) {
        every { host.launchTikTok() } returns true
        every { NodeTraverser.hasNavBar(any()) } returns true  // feed loads OK

        every { NodeTraverser.findByText(any(), any(), any(), any()) } answers {
            val text = secondArg<String>().lowercase()
            when {
                "chuyển đổi" in text  -> clickableResult("Chuyển đổi tài khoản")
                "switch account" in text -> null
                "manage accounts" in text -> null
                text == account.lowercase() -> clickableResult("@$account")
                else -> null
            }
        }

        every { NodeTraverser.parseAccountList(any()) } returns emptyList()
        coEvery { repo.getAccounts() } returns emptyList()
    }
}
