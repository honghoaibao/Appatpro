package com.atpro.automation

import com.atpro.network.LanWebSocketServer
import com.atpro.data.FarmConfig
import com.atpro.data.IFarmRepository
import com.atpro.db.entity.AccountEntity
import com.atpro.notification.AtProNotificationManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

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
 *   ✅ Empty account list → farm completes cleanly
 *   ✅ stop() → isFarming = false + overlay hidden
 *   ✅ pause() / resume() → isPaused flag
 *
 * NOT tested here (cần Robolectric / instrumentation):
 *   ❌ Full farmOneAccount() loop (requires AccessibilityNodeInfo)
 *   ❌ Account switch flow (requires TikTok UI tree)
 *   → See TASK-008 in backlog for integration test expansion
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationEngineTest {

    // ── Test doubles ──────────────────────────────────────────

    private lateinit var host:   IFarmHost
    private lateinit var repo:   IFarmRepository
    private lateinit var engine: AutomationEngine

    // UnconfinedTestDispatcher: coroutines run eagerly without needing advanceUntilIdle()
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope      = TestScope(testDispatcher)

    @Before
    fun setUp() {
        // Silence static singletons — these aren't the subject under test
        mockkObject(LanWebSocketServer)
        every { LanWebSocketServer.broadcast(any(), any()) } just Runs

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
            every  { getRootNode() } returns null
            every  { pressBack()   } returns true
            every  { launchTikTok()         } returns false  // fail fast → farmOneAccount exits early
            every  { openTikTokSettings()   } returns false
            every  { killTikTok()           } just Runs
            every  { showFarmOverlay()      } just Runs
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

        engine.startFarm(listOf("testuser"))

        assertTrue("isFarming phải true ngay sau startFarm()", engine.isFarming)
        engine.stop()
    }

    @Test
    fun `given farming when stop called then isFarming becomes false`() {
        engine.startFarm(listOf("testuser"))
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
        engine.startFarm(listOf("userA"))
        assertTrue(engine.isFarming)

        // Lần thứ hai — phải bị guard `if (isFarming) return`
        engine.startFarm(listOf("userB"))
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
        engine.startFarm(listOf("testuser"))

        engine.pause()

        assertTrue("isPaused phải true sau pause()", engine.isPaused)
        engine.stop()
    }

    @Test
    fun `given paused when resume called then isPaused becomes false`() {
        engine.startFarm(listOf("testuser"))
        engine.pause()
        assertTrue(engine.isPaused)

        engine.resume()

        assertFalse("isPaused phải false sau resume()", engine.isPaused)
        engine.stop()
    }

    /**
     * SCENARIO: empty account list
     *
     * Nếu accounts rỗng → coroutine chạy qua loop ngay lập tức
     * → stopInternal("completed") → isFarming = false.
     *
     * Với UnconfinedTestDispatcher, coroutine chạy eager →
     * không cần advanceUntilIdle().
     */
    @Test
    fun `given empty account list when startFarm then farm completes and isFarming becomes false`() {
        engine.startFarm(emptyList())

        // Với UnconfinedTestDispatcher, coroutine đã chạy xong
        assertFalse("isFarming phải false sau khi farm rỗng hoàn thành", engine.isFarming)
    }

    /**
     * SCENARIO: overlay cleanup on stop
     *
     * stop() phải gọi host.hideFarmOverlay() để đảm bảo
     * overlay không bị leak khi user dừng farm giữa chừng.
     */
    @Test
    fun `when stop called then overlay is hidden`() {
        engine.startFarm(listOf("testuser"))

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
}
