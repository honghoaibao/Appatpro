package com.atpro.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.*
import org.junit.*
import org.junit.Assert.*

/**
 * NodeTraverserTest — unit tests cho NodeTraverser traversal + detection logic.
 *
 * Kỹ thuật: MockK để tạo AccessibilityNodeInfo fake trees.
 * Không cần Robolectric — tất cả Android class calls đều được mock.
 *
 * Cây node được xây dựng bằng helper [node()] — tạo một MockK
 * AccessibilityNodeInfo với text, resourceId, clickable, children.
 * `traverseAll()` bên trong NodeTraverser dùng BFS qua childCount + getChild(i),
 * cả hai đều được mock đúng.
 *
 * Tests:
 *   - findByText: match, no match, ignoreCase, exact
 *   - findByResourceId: match, partial match
 *   - hasNavBar: detected via text, detected via resourceId, not detected
 *   - detectCheckpoint: positive, negative
 *   - parseAccountList: extracts @usernames
 *   - detectPopup: VERIFY_1234 (4 EditText), ACCOUNT_SWITCH (≥2 @usernames), NONE
 *   - detectLive: via resourceId, not detected
 */
class NodeTraverserTest {

    // ── Tree builder ──────────────────────────────────────────

    /**
     * Tạo mock AccessibilityNodeInfo.
     *
     * Mô phỏng đủ interface để NodeTraverser.traverseAll() hoạt động:
     *   - childCount / getChild(i)
     *   - text / contentDescription / viewIdResourceName / className
     *   - isClickable / isEnabled / getBoundsInScreen
     */
    private fun node(
        text:      String?                       = null,
        desc:      String?                       = null,
        id:        String?                       = null,
        cls:       String                        = "android.widget.TextView",
        clickable: Boolean                       = false,
        children:  List<AccessibilityNodeInfo>   = emptyList(),
    ): AccessibilityNodeInfo = mockk(relaxed = true) {
        every { this@mockk.text }                returns text?.toCharSequence()
        every { contentDescription }             returns desc?.toCharSequence()
        every { viewIdResourceName }             returns id
        every { className }                      returns cls.toCharSequence()
        every { isClickable }                    returns clickable
        every { isEnabled }                      returns true
        every { childCount }                     returns children.size
        children.forEachIndexed { i, child ->
            every { getChild(i) } returns child
        }
        every { getBoundsInScreen(any()) }       just Runs
    }

    private fun String.toCharSequence(): CharSequence = this

    // ── findByText ────────────────────────────────────────────

    @Test
    fun `findByText returns node when text matches case-insensitive`() {
        val target = node(text = "Follow", clickable = true)
        val root   = node(children = listOf(target))

        val result = NodeTraverser.findByText(root, "follow")

        assertNotNull(result)
        assertEquals("Follow", result!!.text)
        assertTrue(result.isClickable)
    }

    @Test
    fun `findByText returns null when no node matches`() {
        val child = node(text = "Like")
        val root  = node(children = listOf(child))

        val result = NodeTraverser.findByText(root, "NonExistent")

        assertNull(result)
    }

    @Test
    fun `findByText with exact=true only matches full text`() {
        val child = node(text = "Follow now")
        val root  = node(children = listOf(child))

        // Partial text should NOT match with exact=true
        val partial = NodeTraverser.findByText(root, "follow", exact = true)
        assertNull("Partial text must not match in exact mode", partial)

        // Full exact text must match
        val exact = NodeTraverser.findByText(root, "Follow now", exact = true, ignoreCase = false)
        assertNotNull("Exact text must match", exact)
    }

    @Test
    fun `findByText searches contentDescription when text is null`() {
        val target = node(text = null, desc = "Chuyển đổi tài khoản")
        val root   = node(children = listOf(target))

        val result = NodeTraverser.findByText(root, "chuyển đổi tài khoản")
        assertNotNull(result)
    }

    @Test
    fun `findByText returns null for null root`() {
        assertNull(NodeTraverser.findByText(null, "anything"))
    }

    // ── findByResourceId ──────────────────────────────────────

    @Test
    fun `findByResourceId finds node by partial resource id`() {
        val target = node(id = "com.zhiliaoapp.musically:id/tab_bar")
        val root   = node(children = listOf(target))

        val result = NodeTraverser.findByResourceId(root, "tab_bar")
        assertNotNull(result)
        assertEquals("com.zhiliaoapp.musically:id/tab_bar", result!!.resourceId)
    }

    @Test
    fun `findByResourceId returns null when no id matches`() {
        val child = node(id = "com.pkg:id/some_other_view")
        val root  = node(children = listOf(child))

        assertNull(NodeTraverser.findByResourceId(root, "nav_bar"))
    }

    // ── hasNavBar ─────────────────────────────────────────────

    @Test
    fun `hasNavBar returns true when at least 2 nav tab texts found`() {
        val forYou    = node(text = "For You")
        val following = node(text = "Following")
        val profile   = node(text = "Profile")
        val root      = node(children = listOf(forYou, following, profile))

        assertTrue(NodeTraverser.hasNavBar(root))
    }

    @Test
    fun `hasNavBar returns true when nav resourceId found`() {
        val navBar = node(id = "com.zhiliaoapp.musically:id/tab_bar")
        val root   = node(children = listOf(navBar))

        assertTrue(NodeTraverser.hasNavBar(root))
    }

    @Test
    fun `hasNavBar returns false when fewer than 2 nav items`() {
        val oneTab = node(text = "For You")
        val root   = node(children = listOf(oneTab))

        assertFalse(NodeTraverser.hasNavBar(root))
    }

    @Test
    fun `hasNavBar returns false for null root`() {
        assertFalse(NodeTraverser.hasNavBar(null))
    }

    // ── detectCheckpoint ──────────────────────────────────────

    @Test
    fun `detectCheckpoint returns true when checkpoint keyword found`() {
        val warning = node(text = "Verify your account to continue")
        val root    = node(children = listOf(warning))

        assertTrue(NodeTraverser.detectCheckpoint(root))
    }

    @Test
    fun `detectCheckpoint returns true for Vietnamese keyword`() {
        val warning = node(text = "Tài khoản bị khóa, vui lòng xác minh")
        val root    = node(children = listOf(warning))

        assertTrue(NodeTraverser.detectCheckpoint(root))
    }

    @Test
    fun `detectCheckpoint returns false for normal feed screen`() {
        val video = node(text = "Check out this amazing video!")
        val root  = node(children = listOf(video))

        assertFalse(NodeTraverser.detectCheckpoint(root))
    }

    @Test
    fun `detectCheckpoint returns false for null root`() {
        assertFalse(NodeTraverser.detectCheckpoint(null))
    }

    // ── parseAccountList ──────────────────────────────────────

    @Test
    fun `parseAccountList extracts usernames starting with @`() {
        val u1   = node(text = "@alice")
        val u2   = node(text = "@bob_farm")
        val u3   = node(text = "Chuyển đổi tài khoản")  // non-username — ignored
        val root = node(children = listOf(u1, u2, u3))

        val result = NodeTraverser.parseAccountList(root)

        assertEquals(2, result.size)
        assertTrue("alice" in result)
        assertTrue("bob_farm" in result)
    }

    @Test
    fun `parseAccountList ignores short @ strings`() {
        val short = node(text = "@a")    // length ≤ 2 including @ — ignored
        val valid = node(text = "@xyz")
        val root  = node(children = listOf(short, valid))

        val result = NodeTraverser.parseAccountList(root)
        assertEquals(1, result.size)
        assertEquals("xyz", result[0])
    }

    @Test
    fun `parseAccountList deduplicates usernames`() {
        val u1   = node(text = "@alice")
        val u2   = node(text = "@alice")  // duplicate
        val root = node(children = listOf(u1, u2))

        assertEquals(1, NodeTraverser.parseAccountList(root).size)
    }

    // ── detectPopup ───────────────────────────────────────────

    @Test
    fun `detectPopup returns VERIFY_1234 when exactly 4 EditTexts found`() {
        val edits = (1..4).map { node(cls = "android.widget.EditText") }
        val root  = node(children = edits)

        val info = NodeTraverser.detectPopup(root)

        assertTrue(info.detected)
        assertEquals(NodeTraverser.PopupType.VERIFY_1234, info.type)
    }

    @Test
    fun `detectPopup returns ACCOUNT_SWITCH when 2+ @usernames AND switch container present`() {
        // Switch popup thật: có @username nodes + label "Chuyển đổi tài khoản"
        // isOnFeedTab() = false (không có nav bar / video layout ID)
        val u1   = node(text = "@alice_farm")
        val u2   = node(text = "@bob_farm")
        val btn  = node(text = "Chuyển đổi tài khoản")
        val root = node(children = listOf(u1, u2, btn))

        val info = NodeTraverser.detectPopup(root)

        assertTrue(info.detected)
        assertEquals(NodeTraverser.PopupType.ACCOUNT_SWITCH, info.type)
    }

    @Test
    fun `detectPopup returns NONE for feed screen with @username in video description`() {
        // Feed false-positive: video caption và duet tag có @username nhưng không có switch container.
        // [FIX v1.0.9] Trước kia trả về ACCOUNT_SWITCH — giờ phải là NONE.
        val caption  = node(text = "@creator_name check this out!")
        val duetTag  = node(text = "@another_user duet")
        val likeBtn  = node(text = "thích", clickable = true)
        val shareBtn = node(text = "chia sẻ", clickable = true)
        val root     = node(children = listOf(caption, duetTag, likeBtn, shareBtn))

        val info = NodeTraverser.detectPopup(root)

        // Không có "switch account" / "chuyển đổi tài khoản" container → không phải switch popup
        assertFalse(info.detected)
        assertNotEquals(NodeTraverser.PopupType.ACCOUNT_SWITCH, info.type)
    }

    @Test
    fun `detectPopup returns NONE when 2+ @usernames found but user is on feed tab`() {
        // Edge case: switch container có mặt nhưng isOnFeedTab() = true (overlay chưa fully dismissed).
        // hasSwitchContainer=true AND onFeed=true → không trả ACCOUNT_SWITCH.
        val u1         = node(text = "@user_one")
        val u2         = node(text = "@user_two")
        val switchNode = node(id = "com.zhiliaoapp.musically:id/switch_account")
        val homeTab    = node(text = "Trang chủ", desc = "Trang chủ")
        val profileTab = node(text = "Hồ sơ")
        val root       = node(children = listOf(u1, u2, switchNode, homeTab, profileTab))

        val info = NodeTraverser.detectPopup(root)

        assertNotEquals(NodeTraverser.PopupType.ACCOUNT_SWITCH, info.type)
    }

    @Test
    fun `detectPopup returns NONE for normal feed screen`() {
        val video = node(text = "Watch this cool video")
        val like  = node(text = "Like", clickable = true)
        val root  = node(children = listOf(video, like))

        val info = NodeTraverser.detectPopup(root)

        assertFalse(info.detected)
        assertEquals(NodeTraverser.PopupType.NONE, info.type)
    }

    // ── detectLive ────────────────────────────────────────────

    @Test
    fun `detectLive returns true when live resourceId found`() {
        val liveView = node(id = "com.zhiliaoapp.musically:id/liveroom_gift_panel")
        val root     = node(children = listOf(liveView))

        assertTrue(NodeTraverser.detectLive(root))
    }

    @Test
    fun `detectLive returns false for non-live content`() {
        val video = node(text = "Regular video content")
        val root  = node(children = listOf(video))

        assertFalse(NodeTraverser.detectLive(root))
    }

    // ── findAllByClass ────────────────────────────────────────

    @Test
    fun `findAllByClass returns all nodes of given class`() {
        val btn1 = node(cls = "android.widget.Button", text = "OK")
        val btn2 = node(cls = "android.widget.Button", text = "Cancel")
        val txt  = node(cls = "android.widget.TextView", text = "Title")
        val root = node(children = listOf(btn1, btn2, txt))

        val buttons = NodeTraverser.findAllByClass(root, "Button")

        assertEquals(2, buttons.size)
    }
}
