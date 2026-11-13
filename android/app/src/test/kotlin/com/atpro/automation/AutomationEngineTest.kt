package com.atpro.automation

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
 * AutomationEngineTest — unit tests cho AutomationEngine state machine.
 *
 * Dùng:
 *   - MockK để mock IFarmHost, IFarmRepository, LanWebSocketServer, AtProNotificationManager
 *   - kotlinx-coroutines-test: UnconfinedTestDispatcher + runTest
 *
 * Scope của tests:
 *   ✅ State transitions: isFarming, isPaused
 *   ✅ Idempotent start guard
 *   ✅ Launch failure after retries → farming state is reset
 *   ✅ stop() → isFarming = false + overlay hidden
 *   ✅ pause() / resume() → isPaused flag
 *
 * NOT tested here (cần dữ liệu cây UI từ thiết bị hoặc fixture riêng):
 *   ❌ Full farmOneAccount() loop against a real AccessibilityNodeInfo tree
 *   ❌ Account switch flow against a real TikTok UI tree
 *   ❌ v1.3.1: isForegroundTikTok() / dismissFacebookBottomSheetIfPresent() /
 *      relaunchTikTokWithRetry() — cùng nhóm private suspend fun phụ thuộc
 *      host.getRootNode() + cây node thật, chưa có hạ tầng test cho loại
 *      này (không thêm kotlin-reflect vì chưa verify build được trong sandbox
 *      hiện tại). Logic ưu tiên ACCOUNT_UPDATE_PROMPT trước ACCOUNT_SWITCH —
 *      phần dễ sai và giá trị test cao nhất — ĐÃ được cover ở
 *      NodeTraverserTest (pure function, không cần mock host).
 *   → See TASK-008 in backlog for integration test expansion
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomationEngineTest {

    // ── Test doubles ──────────────────────────────────────────

    private lateinit var host:   IFarmHost
    private lateinit var repo:   IFarmRepository
    private lateinit var engine: AutomationEngine

    // TestScope controls retry delays deterministically through advanceUntilIdle().
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope      = TestScope(testDispatcher)

    @Before
    fun setUp() {
        // Silence static singletons — these aren't the subject under test
        mockkObject(LanWebSocketServer)
        every { LanWebSocketServer.broadcast(any(), any()) } just Runs

        mockkObject(OverlayFarmMonitor)
        every { OverlayFarmMonitor.setStartupStatus(any()) } just Runs

        mockkObject(AtProNotificationManager)
        every { AtProNotificationManager.notifyFarmStarted(any())            } just Runs
        every { AtProNotificationManager.notifyFarmCompleted(any(), any(), any(), any(), any()) } just Runs
        every { AtProNotificationManager.notifySessionDone(any(), any(), any(), any()) } just Runs
        every { AtProNotificationManager.notifyCheckpoint(any())             } just Runs

        // host: gesture/node calls return safe defaults
        host = mockk {
            every  { scope }       returns testScope
            every  { screenWidth } returns 1080
            every  { screenHeight } returns 2400
            coEvery { getRootNode() } returns null  // v1.3.1: getRootNode() giờ là suspend fun
            every  { pressBack()   } returns true
            every  { pressHome()   } returns true   // v1.12.5: về màn hình chính trước khi dừng
            every  { launchTikTok()         } returns false  // fail fast → farmOneAccount exits early
            every  { openTikTokSettings()   } returns false
            coEvery { killTikTok()           } just Runs
            every  { showFarmOverlay(any()) } just Runs
            every  { hideFarmOverlay()      } just Runs
            coEvery { clickNode(any())      } returns true
            coEvery { clickSuspend(any(), any()) } returns true
            coEvery { swipeSuspend(any(), any(), any(), any(), any()) } returns true
            every  { typeText(any(), any()) } returns true
        }

        // repo: minimal stubs to avoid lateinit crashes
        repo = mockk {
            coEvery { loadFarmConfig()  } returns FarmConfig()
            coEvery { getAccounts()     } returns emptyList()
            coEvery { addAccount(any()) } just Runs
            coEvery { startSession(any()) } returns 1L
            coEvery { closeSession(any(), any(), any(), any(), any(), any()) } just Runs
            coEvery { setCheckpoint(any(), any()) } just Runs
            coEvery { log(any(), any(), any()) } just Runs
        }

        engine = AutomationEngine(host, repo)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── Tests ─────────────────────────────────────────────────

    /**
     * SCENARIO: start/stop state
     *
     * Sau khi startFarm() được gọi, isFarming phải là true.
     * stop() phải set isFarming = false đồng bộ.
     */
    @Test
    fun `given idle when startFarm called then isFarming becomes true`() {
        assertFalse("Pre-condition: engine không farming", engine.isFarming)

        engine.startFarm(FarmMode.SELECTED_LIST, listOf("testuser"))

        assertTrue("isFarming phải true ngay sau startFarm()", engine.isFarming)
        engine.stop()
    }

    @Test
    fun `given farming when stop called then isFarming becomes false`() {
        engine.startFarm(FarmMode.SELECTED_LIST, listOf("testuser"))
        assertTrue(engine.isFarming)

        engine.stop()

        assertFalse("isFarming phải false sau stop()", engine.isFarming)
    }

    /**
     * SCENARIO: idempotent guard
     *
     * Gọi startFarm() hai lần phải là no-op lần thứ hai.
     * Kiểm tra: repo.loadFarmConfig() chỉ được gọi 1 lần (không phải 2).
     */
    @Test
    fun `given already farming when startFarm called again then second call is noop`() = runTest {
        engine.startFarm(FarmMode.SELECTED_LIST, listOf("userA"))
        assertTrue(engine.isFarming)

        // Lần thứ hai — phải bị guard `if (isFarming) return`
        engine.startFarm(FarmMode.SELECTED_LIST, listOf("userB"))
        assertTrue("isFarming vẫn true", engine.isFarming)

        // loadFarmConfig chỉ được gọi MỘT lần (bởi lần start đầu tiên)
        coVerify(exactly = 1) { repo.loadFarmConfig() }

        engine.stop()
    }

    /**
     * SCENARIO: pause / resume
     *
     * pause() → isPaused = true
     * resume() → isPaused = false
     */
    @Test
    fun `given farming when pause called then isPaused becomes true`() {
        engine.startFarm(FarmMode.SELECTED_LIST, listOf("testuser"))

        engine.pause()

        assertTrue("isPaused phải true sau pause()", engine.isPaused)
        engine.stop()
    }

    @Test
    fun `given paused when resume called then isPaused becomes false`() {
        engine.startFarm(FarmMode.SELECTED_LIST, listOf("testuser"))
        engine.pause()
        assertTrue(engine.isPaused)

        engine.resume()

        assertFalse("isPaused phải false sau resume()", engine.isPaused)
        engine.stop()
    }

    /**
     * SCENARIO: launch failure cleanup.
     *
     * Host mặc định không mở được TikTok. Cho test scheduler chạy hết
     * backoff để xác nhận engine dọn trạng thái sau đủ 3 lần thử.
     */
    @Test
    fun `given TikTok launch fails after retries when startFarm then farming state resets`() {
        engine.startFarm(FarmMode.SELECTED_LIST, listOf("testuser"))

        testScope.advanceUntilIdle()

        assertFalse("isFarming phải false sau khi hết lượt mở TikTok", engine.isFarming)
        verify(exactly = 3) { host.launchTikTok() }
    }

    /**
     * SCENARIO: overlay cleanup on stop
     *
     * stop() phải gọi host.hideFarmOverlay() để đảm bảo
     * overlay không bị leak khi user dừng farm giữa chừng.
     */
    @Test
    fun `when stop called then overlay is hidden`() {
        engine.startFarm(FarmMode.SELECTED_LIST, listOf("testuser"))

        engine.stop()

        verify(atLeast = 1) { host.hideFarmOverlay() }
    }

    /**
     * SCENARIO: stop khi không farming
     *
     * stop() khi idle không được crash hoặc set isFarming = true.
     */
    @Test
    fun `given idle when stop called then no crash and isFarming stays false`() {
        assertFalse(engine.isFarming)

        engine.stop()   // phải không crash

        assertFalse("isFarming vẫn false", engine.isFarming)
    }

    // ── v1.12.5: về màn hình chính trước khi dừng ─────────────

    /** Dừng giữa chừng (người dùng bấm Dừng) → máy phải về Home, đúng MỘT lần dù stop() và finally cùng gọi stopInternal. */
    @Test
    fun `given farming when stop called then phone goes home exactly once`() {
        engine.startFarm(FarmMode.SELECTED_LIST, listOf("testuser"))
        assertTrue(engine.isFarming)

        engine.stop()

        verify(exactly = 1) { host.pressHome() }
        assertFalse(engine.isFarming)
    }

    /** Không có phiên nào đang chạy thì dừng KHÔNG đẩy người dùng về Home. */
    @Test
    fun `given idle when stop called then phone does not go home`() {
        engine.stop()

        verify(exactly = 0) { host.pressHome() }
    }

    /** Dừng hai lần liên tiếp (vd bấm Dừng rồi service bị tắt) chỉ về Home một lần. */
    @Test
    fun `given farming when stop called twice then phone goes home once`() {
        engine.startFarm(FarmMode.SELECTED_LIST, listOf("testuser"))

        engine.stop()
        engine.stop()

        verify(exactly = 1) { host.pressHome() }
    }

    /**
     * Tool tự tắt vì lỗi ngay lúc khởi động (nạp cấu hình lỗi) → vẫn đi qua finally: nhả cờ isFarming và về màn hình chính.
     * Trước v1.12.5 lỗi ở bước này làm phiên chết mà không qua finally (cờ isFarming kẹt true, không về Home).
     */
    @Test
    fun `given config load fails when startFarm then engine stops itself and goes home`() {
        coEvery { repo.loadFarmConfig() } throws RuntimeException("db lỗi")

        engine.startFarm(FarmMode.SELECTED_LIST, listOf("testuser"))

        assertFalse("isFarming phải về false sau lỗi khởi động", engine.isFarming)
        verify(exactly = 1) { host.pressHome() }
    }
}
