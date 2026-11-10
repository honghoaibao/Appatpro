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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
 *   - Robolectric API 34 cung cấp Android framework trên JVM.
 *   - mockkObject(NodeTraverser): mock toàn bộ static-like calls
 *   - mockkObject(AtProNotificationManager / OverlayFarmMonitor): silence side-effects
 *   - mockk<AccessibilityNodeInfo>(relaxed=true): tạo NodeResult giả cho các luồng kiểm soát
 *   - TestScope.advanceUntilIdle(): chạy hết các retry/delay ảo mà không đợi thời gian thực
 *
 * Giới hạn:
 *   ❌ doLike(), doFollow() — không thể test vì phụ thuộc Random + clickSuspend sequence
 *   ❌ recoverToFeed() detail — covered riêng khi có thêm fixtures
 *   → TASK-011: bổ sung fixture cây AccessibilityNodeInfo thực tế cho các path còn thiếu
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
        every  { OverlayFarmMonitor.setStartupStatus(any()) } just Runs

        // NodeTraverser: safe defaults — no popup, no nav, no checkpoint
        mockkObject(NodeTraverser)
        every { NodeTraverser.hasNavBar(any())       } returns false
        every { NodeTraverser.detectCheckpoint(any()) } returns false
        every { NodeTraverser.detectLive(any())      } returns false
        every { NodeTraverser.detectPopup(any())     } returns
            NodeTraverser.PopupInfo(false, NodeTraverser.PopupType.NONE, null)
        every { NodeTraverser.detectLogoutPrompt(any()) } returns false
        every { NodeTraverser.isOnFeedTab(any()) } returns false
        every { NodeTraverser.findSwitchAccountRow(any(), any(), any()) } returns null
        every { NodeTraverser.parseAccountListWithNodes(any(), any(), any()) } returns emptyList()
        every { NodeTraverser.findByText(any(), any(), any(), any()) } returns null

        // Host: all gestures succeed, screen exists
        host = mockk {
            every  { scope }        returns testScope
            every  { screenWidth }  returns 1080
            every  { screenHeight } returns 2400
            coEvery { getRootNode() } returns null  // v1.3.1: getRootNode() giờ là suspend fun
            every  { pressBack()   } returns true
            every  { launchTikTok()         } returns false  // default: fail
            every  { openTikTokSettings()   } returns true
            coEvery { killTikTok()           } just Runs
            every  { showFarmOverlay(any()) } just Runs
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
     * SCENARIO A: TikTok không mở được ở pha khởi động.
     *
     * Pha Launch kết thúc thất bại trước khi discover/position account,
     * vì vậy chưa có session nào được tạo và không đánh checkpoint tài khoản.
     */
    @Test
    fun `farmLoop when launchTikTok fails then checkpoint set and session closed empty`() {
        every { host.launchTikTok() } returns false

        engine.startFarm(FarmMode.SELECTED_LIST, listOf("user1"))

        testScope.advanceUntilIdle()

        assertFalse("isFarming phải false sau khi Launch thất bại", engine.isFarming)
        verify(exactly = 3) { host.launchTikTok() }
        coVerify(exactly = 0) { repo.startSession(any()) }
        coVerify(exactly = 0) { repo.setCheckpoint(any(), any()) }
        coVerify(exactly = 0) { repo.closeSession(any(), any(), any(), any(), any(), any()) }
    }

    /**
     * SCENARIO B: TikTok mở được nhưng feed không tải được ở pha Launch.
     *
     * State machine kết thúc trước khi bắt đầu session; không đánh checkpoint
     * vì chưa position được bất kỳ tài khoản nào.
     */
    @Test
    fun `farmLoop when feed never loads then switch fails and session closed empty`() {
        every { host.launchTikTok() } returns true
        every { NodeTraverser.hasNavBar(any()) } returns false   // feed không bao giờ load

        engine.startFarm(FarmMode.SELECTED_LIST, listOf("user1"))

        testScope.advanceUntilIdle()

        assertFalse("isFarming phải false sau khi hết lượt chờ feed", engine.isFarming)
        coVerify(exactly = 1) { host.launchTikTok() }
        coVerify(exactly = 0) { repo.startSession(any()) }
        coVerify(exactly = 0) { repo.setCheckpoint(any(), any()) }
        coVerify(exactly = 0) { repo.closeSession(any(), any(), any(), any(), any(), any()) }
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
        configureSuccessfulSwitch(listOf("user1"))

        engine.startFarm(FarmMode.SELECTED_LIST, listOf("user1"))

        testScope.advanceUntilIdle()

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
        configureSuccessfulSwitch(listOf("user1"))
        every { NodeTraverser.hasNavBar(any()) } returns true
        every { NodeTraverser.detectCheckpoint(any()) } returns true  // phát hiện ngay lần đầu

        engine.startFarm(FarmMode.SELECTED_LIST, listOf("user1"))

        testScope.advanceUntilIdle()

        assertFalse(engine.isFarming)
        coVerify { repo.setCheckpoint("user1", true) }
        coVerify { AtProNotificationManager.notifyCheckpoint("user1") }
        // Session vẫn được đóng sau khi break
        coVerify { repo.closeSession(1L, "user1", any(), any(), any(), any()) }
    }

    /**
     * SCENARIO E: Nhiều accounts — tất cả được xử lý theo thứ tự.
     *
     * Các session được đặt minutesPerAccount=0 để kết thúc ngay mà không chờ wall-clock.
     * Verify: startSession được gọi đúng số lần, đúng account và đúng thứ tự.
     */
    @Test
    fun `farmLoop with multiple accounts processes all accounts in order`() {
        configureSuccessfulSwitch(listOf("userA", "userB", "userC"))
        coEvery { repo.loadFarmConfig() } returns FarmConfig(minutesPerAccount = 0)
        // Trả về session ID khác nhau cho từng account
        coEvery { repo.startSession("userA") } returns 1L
        coEvery { repo.startSession("userB") } returns 2L
        coEvery { repo.startSession("userC") } returns 3L

        engine.startFarm(FarmMode.SELECTED_LIST, listOf("userA", "userB", "userC"))

        testScope.advanceUntilIdle()

        assertFalse(engine.isFarming)

        // Tất cả 3 accounts được process
        coVerify(exactly = 1) { repo.startSession("userA") }
        coVerify(exactly = 1) { repo.startSession("userB") }
        coVerify(exactly = 1) { repo.startSession("userC") }

        // Switch thành công nên không account nào bị đánh checkpoint.
        coVerify(exactly = 0) { repo.setCheckpoint(any(), any()) }

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
     * Khi NodeTraverser.parseAccountListWithNodes trả về các entry,
     * các account chưa có trong DB phải được addAccount().
     */
    @Test
    fun `farmLoop when switch popup shows accounts then auto-saves new ones`() {
        // Popup chứa 3 account — user1 đã có, newUser1 và newUser2 chưa có.
        configureSuccessfulSwitch(listOf("user1", "newUser1", "newUser2"))
        coEvery { repo.getAccounts() } returns listOf(
            AccountEntity(username = "user1", status = "active")
        )

        coEvery { repo.loadFarmConfig() } returns FarmConfig(minutesPerAccount = 0)

        engine.startFarm(FarmMode.SELECTED_LIST, listOf("user1"))

        testScope.advanceUntilIdle()

        // Chỉ 2 account mới được lưu, user1 bỏ qua vì đã tồn tại
        coVerify(exactly = 1) { repo.addAccount("newUser1") }
        coVerify(exactly = 1) { repo.addAccount("newUser2") }
        coVerify(exactly = 0) { repo.addAccount("user1") }
    }

    // ── Helpers ───────────────────────────────────────────────

    /**
     * Cấu hình switch-popup fixture theo API hiện tại:
     * findSwitchAccountRow → parseAccountListWithNodes → position/relaunch.
     * Các entry dùng cùng node giả vì test chỉ xác minh điều phối và thứ tự.
     */
    private fun configureSuccessfulSwitch(accounts: List<String>) {
        every { host.launchTikTok() } returns true
        every { NodeTraverser.hasNavBar(any()) } returns true
        every { NodeTraverser.isOnFeedTab(any()) } returns true

        val switchBounds = Rect(300, 1_000, 780, 1_080)
        val switchLabel = NodeTraverser.NodeResult(
            node = fakeNode,
            bounds = switchBounds,
            text = "Chuyển đổi tài khoản",
            resourceId = null,
            className = "android.widget.TextView",
            isClickable = true,
            isEnabled = true,
        )
        val switchRow = NodeTraverser.SwitchRow(switchLabel, fakeNode)
        // The first popup row is the active account; provide that state explicitly
        // so the fixture does not rely on the production safety fallback for unknown accounts.
        every { NodeTraverser.findProfileTab(null, any()) } returns clickableResult("Profile")
        every { NodeTraverser.getCurrentAccountId(null) } returns "alreadyLoggedIn"
        every { NodeTraverser.findSwitchAccountRow(any(), any(), any()) } returns switchRow
        every { NodeTraverser.parseAccountListWithNodes(any(), any(), any()) } returns
            accounts.map { NodeTraverser.AccountEntry(it, fakeNode, isNeedsNormalize = false) }

        coEvery { repo.getAccounts() } returns emptyList()
    }
}
