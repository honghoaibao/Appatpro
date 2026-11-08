package com.atpro.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.atpro.accessibility.NodeTraverser.FollowState
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NodeTraverserFollowTest — bộ dò nút Follow trên hồ sơ (v1.9.0).
 *
 * Cây node dựng theo ảnh chụp hồ sơ thật (màn 1080×2408): hàng thống kê "35 Đã follow · 1.748 Follower · 732 Thích",
 * hàng nút [Follow | Nhắn tin | +người], chip "Cửa hàng". Bản cũ không tìm thấy nút vì chữ "Follow" nằm ở
 * TextView con KHÔNG clickable (cha mới clickable) và còn nhận nhầm nhãn thống kê.
 *
 * Bounds gán thẳng vào field của Rect (không gọi Rect.set) để chạy được trên android.jar "mockable".
 */
class NodeTraverserFollowTest {

    // ── Tree builder ──────────────────────────────────────────

    private fun n(
        text: String? = null,
        desc: String? = null,
        id: String? = null,
        clickable: Boolean = false,
        selected: Boolean = false,
        b: IntArray = intArrayOf(0, 0, 0, 0),
        kids: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.text }               returns text
        every { node.contentDescription } returns desc
        every { node.viewIdResourceName } returns id
        every { node.className }          returns "android.view.View"
        every { node.isClickable }        returns clickable
        every { node.isEnabled }          returns true
        every { node.isVisibleToUser }    returns true
        every { node.isSelected }         returns selected
        every { node.isChecked }          returns false
        every { node.childCount }         returns kids.size
        kids.forEachIndexed { i, k ->
            every { node.getChild(i) } returns k
            every { k.parent } returns node
        }
        every { node.getBoundsInScreen(any()) } answers {
            val r = firstArg<Rect>()
            r.left = b[0]; r.top = b[1]; r.right = b[2]; r.bottom = b[3]
        }
        return node
    }

    private fun stat(num: String, label: String, left: Int) = n(
        clickable = true, b = intArrayOf(left, 470, left + 200, 570),
        kids = listOf(
            n(text = num,   b = intArrayOf(left, 470, left + 150, 520)),
            n(text = label, b = intArrayOf(left, 535, left + 170, 568)),
        ),
    )

    /** Nút trong hàng nút: container clickable + chữ con KHÔNG clickable. */
    private fun btn(label: String?, desc: String?, l: Int, r: Int, clickableText: Boolean = false) = n(
        desc = desc, clickable = true, b = intArrayOf(l, 621, r, 741),
        kids = listOf(n(text = label, clickable = clickableText, b = intArrayOf(l + 100, 650, r - 100, 710))),
    )

    private fun profile(
        follow: AccessibilityNodeInfo?,
        msg: Boolean = true,
        followingStat: String = "Đã follow",
        followerStat: String = "Follower",
        extra: List<AccessibilityNodeInfo> = emptyList(),
        own: Boolean = false,
    ): AccessibilityNodeInfo {
        val kids = ArrayList<AccessibilityNodeInfo>()
        kids += n(text = "MIDALA", b = intArrayOf(36, 290, 320, 360))
        kids += stat("35", followingStat, 36)
        kids += stat("1.748", followerStat, 240)
        kids += stat("732", "Thích", 430)
        if (follow != null) kids += follow
        if (msg) kids += btn("Nhắn tin", null, 480, 900)
        kids += n(desc = "Gợi ý", clickable = true, b = intArrayOf(924, 621, 1044, 741))
        kids += n(clickable = true, b = intArrayOf(36, 766, 334, 851),
            kids = listOf(n(text = "Cửa hàng", b = intArrayOf(100, 790, 300, 830))))
        if (own) kids += n(text = "Sửa hồ sơ", clickable = true, b = intArrayOf(36, 621, 456, 741))
        kids += extra
        return n(b = intArrayOf(0, 0, 1080, 2408), kids = kids)
    }

    private fun suggestions() = (0 until 3).map { i ->
        n(clickable = true, b = intArrayOf(36 + i * 300, 1500, 300 + i * 300, 1580),
            kids = listOf(n(text = "Follow", b = intArrayOf(80 + i * 300, 1510, 250 + i * 300, 1570))))
    }

    // ── Tìm được nút Follow ───────────────────────────────────

    @Test
    fun `chu Follow o TextView con khong clickable van tim thay va bam vao cha clickable`() {
        val p = NodeTraverser.probeFollow(profile(btn("Follow", null, 36, 456)))
        assertEquals(FollowState.CAN_FOLLOW, p.state)
        assertNotNull(p.button)
        assertTrue("phải trỏ tới container clickable", p.button!!.isClickable)
        assertEquals(36, p.button!!.bounds.left)
        assertEquals(621, p.button!!.bounds.top)
    }

    @Test
    fun `nut chi co contentDescription Follow`() {
        val p = NodeTraverser.probeFollow(profile(btn(null, "Follow", 36, 456)))
        assertEquals(FollowState.CAN_FOLLOW, p.state)
        assertTrue(p.button!!.isClickable)
    }

    @Test
    fun `nhan Theo doi Follow lai va chu hoa co ky tu vo hinh deu duoc nhan`() {
        for (label in listOf("Theo dõi", "Follow lại", "FOLLOW\u200B", "Follow\u00A0")) {
            val p = NodeTraverser.probeFollow(profile(btn(label, null, 36, 456)))
            assertEquals("nhãn \"$label\"", FollowState.CAN_FOLLOW, p.state)
        }
    }

    @Test
    fun `UI tieng Anh nhan thong ke Following khong bi nham thanh nut da follow`() {
        val p = NodeTraverser.probeFollow(
            profile(btn("Follow", null, 36, 456), followingStat = "Following", followerStat = "Followers"),
        )
        assertEquals(FollowState.CAN_FOLLOW, p.state)
    }

    @Test
    fun `khong co Nhan tin van tim thay nho neo Follower`() {
        val p = NodeTraverser.probeFollow(profile(btn("Follow", null, 36, 456), msg = false))
        assertEquals(FollowState.CAN_FOLLOW, p.state)
    }

    @Test
    fun `chon nut chinh tren cung khi co goi y Follow phia duoi`() {
        val p = NodeTraverser.probeFollow(profile(btn("Follow", null, 36, 456), extra = suggestions()))
        assertEquals(FollowState.CAN_FOLLOW, p.state)
        assertEquals(621, p.button!!.bounds.top)
    }

    @Test
    fun `resource id dac thu duoc uu tien`() {
        val follow = n(id = "com.zhiliaoapp.musically:id/btn_follow", clickable = true,
            b = intArrayOf(36, 621, 456, 741),
            kids = listOf(n(text = "Follow", b = intArrayOf(120, 650, 350, 710))))
        val p = NodeTraverser.probeFollow(profile(follow))
        assertEquals(FollowState.CAN_FOLLOW, p.state)
        assertEquals("id", p.how)
    }

    @Test
    fun `nut khong lo nhan nao thi doan theo hinh hoc ben trai Nhan tin`() {
        val p = NodeTraverser.probeFollow(profile(n(clickable = true, b = intArrayOf(36, 621, 456, 741))))
        assertEquals(FollowState.CAN_FOLLOW, p.state)
        assertEquals("hình học", p.how)
        assertTrue(p.button!!.isClickable)
    }

    // ── Trạng thái đã theo dõi ────────────────────────────────

    @Test
    fun `Dang follow Ban be Da yeu cau la da xong`() {
        assertEquals(FollowState.FOLLOWING, NodeTraverser.probeFollow(profile(btn("Đang follow", null, 36, 456))).state)
        assertEquals(FollowState.FRIENDS,   NodeTraverser.probeFollow(profile(btn("Bạn bè", null, 36, 456))).state)
        assertEquals(FollowState.REQUESTED, NodeTraverser.probeFollow(profile(btn("Đã yêu cầu", null, 36, 456))).state)
    }

    @Test
    fun `nut Following tieng Anh nhan ra dung du nhan thong ke cung la Following`() {
        val p = NodeTraverser.probeFollow(
            profile(btn("Following", null, 36, 456), followingStat = "Following", followerStat = "Followers"),
        )
        assertEquals(FollowState.FOLLOWING, p.state)
    }

    @Test
    fun `da follow thi goi y Follow phia duoi khong bi coi la nut can bam`() {
        val p = NodeTraverser.probeFollow(profile(btn("Đang follow", null, 36, 456), extra = suggestions()))
        assertEquals(FollowState.FOLLOWING, p.state)
    }

    // ── Không có nút ──────────────────────────────────────────

    @Test
    fun `chi co nhan thong ke Da follow khong phai nut`() {
        assertEquals(FollowState.NOT_FOUND, NodeTraverser.probeFollow(profile(null)).state)
        assertEquals(FollowState.NOT_FOUND, NodeTraverser.probeFollow(profile(null, msg = false)).state)
    }

    @Test
    fun `ho so cua chinh minh khong co nut theo doi`() {
        assertEquals(FollowState.NOT_FOUND, NodeTraverser.probeFollow(profile(null, own = true)).state)
    }

    @Test
    fun `cay rong tra NOT_FOUND`() {
        assertEquals(FollowState.NOT_FOUND, NodeTraverser.probeFollow(n(b = intArrayOf(0, 0, 1080, 2408))).state)
    }

    // ── API cũ + hồ sơ khoá ───────────────────────────────────

    @Test
    fun `isFollowConfirmed chi dung khi nut da doi trang thai`() {
        assertTrue(NodeTraverser.isFollowConfirmed(profile(btn("Đang follow", null, 36, 456))))
        // Nhãn thống kê "Following" (UI tiếng Anh) trước đây khiến hàm luôn trả true.
        assertFalse(NodeTraverser.isFollowConfirmed(
            profile(btn("Follow", null, 36, 456), followingStat = "Following", followerStat = "Followers"),
        ))
    }

    @Test
    fun `findFollowButtonOnProfile chi tra nut chua bam`() {
        assertNotNull(NodeTraverser.findFollowButtonOnProfile(profile(btn("Follow", null, 36, 456))))
        assertEquals(null, NodeTraverser.findFollowButtonOnProfile(profile(btn("Đang follow", null, 36, 456))))
    }

    @Test
    fun `scanProfile nhan ra tai khoan rieng tu`() {
        val root = profile(
            btn("Follow", null, 36, 456),
            extra = listOf(n(text = "Tài khoản này ở chế độ riêng tư", b = intArrayOf(0, 900, 800, 960))),
        )
        assertTrue(NodeTraverser.scanProfile(root).blocked)
    }

    // ── Phân loại trang (feed thật?) ──────────────────────────

    private fun navBar(homeSelected: Boolean, profileSelected: Boolean = false) = listOf(
        n(text = "Trang chủ", selected = homeSelected,    b = intArrayOf(0, 2300, 200, 2400)),
        n(text = "Hộp thư",                               b = intArrayOf(400, 2300, 600, 2400)),
        n(text = "Hồ sơ",     selected = profileSelected, b = intArrayOf(800, 2300, 1000, 2400)),
    )
    private fun likeButton() = n(id = "com.x:id/like_layout", b = intArrayOf(900, 1200, 1000, 1300))

    @Test
    fun `feed chinh co tab Trang chu duoc chon la REAL_FEED`() {
        val root = n(b = intArrayOf(0, 0, 1080, 2408), kids = listOf(likeButton()) + navBar(homeSelected = true))
        assertEquals(NodeTraverser.FeedKind.REAL_FEED, NodeTraverser.classifyFeed(root))
        assertTrue(NodeTraverser.isRealFeed(root))
    }

    @Test
    fun `trang video mo tu link khong co thanh dieu huong la NO_NAV du isOnFeedTab van true`() {
        val root = n(b = intArrayOf(0, 0, 1080, 2408), kids = listOf(likeButton()))
        assertTrue("isOnFeedTab bản cũ coi trang video là feed", NodeTraverser.isOnFeedTab(root))
        assertEquals(NodeTraverser.FeedKind.NO_NAV, NodeTraverser.classifyFeed(root))
        assertFalse(NodeTraverser.isRealFeed(root))
    }

    @Test
    fun `trang ho so voi nhan thong ke o giua man hinh khong bi coi la thanh dieu huong`() {
        val root = profile(btn("Follow", null, 36, 456))
        assertEquals(NodeTraverser.FeedKind.NO_NAV, NodeTraverser.classifyFeed(root))
    }

    @Test
    fun `tab khac dang duoc chon la OTHER_PAGE`() {
        val root = n(b = intArrayOf(0, 0, 1080, 2408), kids = listOf(likeButton()) + navBar(homeSelected = false, profileSelected = true))
        assertEquals(NodeTraverser.FeedKind.OTHER_PAGE, NodeTraverser.classifyFeed(root))
    }

    @Test
    fun `nut Quay lai o goc tren trai la OTHER_PAGE`() {
        val back = n(desc = "Quay lại", clickable = true, b = intArrayOf(20, 90, 140, 210))
        val root = n(b = intArrayOf(0, 0, 1080, 2408), kids = listOf(likeButton(), back) + navBar(homeSelected = true))
        assertTrue(NodeTraverser.hasBackButton(root))
        assertEquals(NodeTraverser.FeedKind.OTHER_PAGE, NodeTraverser.classifyFeed(root))
    }
}
