package com.atpro.automation

import org.junit.Assert.*
import org.junit.Test

/**
 * TaskNurtureTest — unit tests cho `[taskPostJobNurtureSecs]` (v1.3.3).
 *
 * Pure function — không cần MockK/Robolectric, chỉ JUnit thuần. `randomInt`
 * được inject để test kiểm soát được kết quả "ngẫu nhiên" thay vì phụ thuộc
 * kotlin.random.Random thật.
 */
class TaskNurtureTest {

    @Test
    fun `moc dung boi so N tra ve long duration co dinh`() {
        val result = taskPostJobNurtureSecs(
            jobsDone = 3, everyNJobs = 3, longDurationSecs = 180,
            shortMinSecs = 5, shortMaxSecs = 20,
            randomInt = { fail("Không được gọi randomInt ở nhánh nuôi lâu") ; 0 },
        )
        assertEquals(180, result)
    }

    @Test
    fun `boi so lon hon cung tinh la moc nuoi lau`() {
        val result = taskPostJobNurtureSecs(
            jobsDone = 6, everyNJobs = 3, longDurationSecs = 180,
            shortMinSecs = 5, shortMaxSecs = 20,
        )
        assertEquals(180, result)
    }

    @Test
    fun `khong phai boi so tra ve nuoi ngan random trong khoang`() {
        var capturedRange: IntRange? = null
        val result = taskPostJobNurtureSecs(
            jobsDone = 2, everyNJobs = 3, longDurationSecs = 180,
            shortMinSecs = 5, shortMaxSecs = 20,
            randomInt = { range -> capturedRange = range; 12 },
        )
        assertEquals(12, result)
        assertEquals(5..20, capturedRange)
    }

    @Test
    fun `jobsDone bang 0 chua tinh la moc dai du chia het`() {
        // 0 % everyNJobs == 0 về mặt toán học, nhưng chưa hoàn thành job nào
        // thì không thể coi là "đã đạt mốc" — phải rơi vào nhánh nuôi ngắn.
        val result = taskPostJobNurtureSecs(
            jobsDone = 0, everyNJobs = 3, longDurationSecs = 180,
            shortMinSecs = 5, shortMaxSecs = 20,
            randomInt = { 7 },
        )
        assertEquals(7, result)
    }

    @Test
    fun `everyNJobs bang 0 tat han nuoi lau du dung moc`() {
        val result = taskPostJobNurtureSecs(
            jobsDone = 9, everyNJobs = 0, longDurationSecs = 180,
            shortMinSecs = 5, shortMaxSecs = 20,
            randomInt = { 15 },
        )
        assertEquals(15, result)
    }

    @Test
    fun `everyNJobs am cung tat han nuoi lau`() {
        val result = taskPostJobNurtureSecs(
            jobsDone = 9, everyNJobs = -1, longDurationSecs = 180,
            shortMinSecs = 5, shortMaxSecs = 20,
            randomInt = { 15 },
        )
        assertEquals(15, result)
    }

    @Test
    fun `config loi max nho hon min duoc coerce ve mot gia tri`() {
        var capturedRange: IntRange? = null
        val result = taskPostJobNurtureSecs(
            jobsDone = 1, everyNJobs = 3, longDurationSecs = 180,
            shortMinSecs = 20, shortMaxSecs = 5,
            randomInt = { range -> capturedRange = range; range.first },
        )
        assertEquals(20..20, capturedRange)
        assertEquals(20, result)
    }

    @Test
    fun `mac dinh khong truyen randomInt van tra ve gia tri trong khoang`() {
        // Dùng default randomInt thật (kotlin.random) — chỉ kiểm tra không
        // crash và kết quả nằm trong khoảng cấu hình.
        repeat(20) {
            val result = taskPostJobNurtureSecs(
                jobsDone = 1, everyNJobs = 3, longDurationSecs = 180,
                shortMinSecs = 5, shortMaxSecs = 20,
            )
            assertTrue(result in 5..20)
        }
    }
}
