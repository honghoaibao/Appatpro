package com.atpro.automation

import org.junit.Assert.*
import org.junit.Test

/** PickSwitchEntryTest — v1.11.0: chọn dòng cần bấm trong popup chuyển tài khoản. */
class PickSwitchEntryTest {

    @Test fun `danh sach chi co dong dich va acc dang dung la acc khac thi bam dong do`() {
        // Ca thật từ nhật ký: "Discover 1 acc" = [hoaibao.2009], acc đang dùng @hoaibao.209.13
        assertEquals(0, pickSwitchEntry(listOf("hoaibao.2009"), "hoaibao.2009", "hoaibao.209.13"))
    }

    @Test fun `dich la acc dang dung thi khong bam`() {
        assertNull(pickSwitchEntry(listOf("a", "b"), "a", "a"))
        assertNull(pickSwitchEntry(listOf("a", "b"), "@A", "a"))
    }

    @Test fun `khong biet acc dang dung - dong dau 2 dong tro len duoc coi la acc dang login`() {
        assertNull(pickSwitchEntry(listOf("a", "b"), "a", null))
        assertEquals(1, pickSwitchEntry(listOf("a", "b"), "b", null))
    }

    @Test fun `khong biet acc dang dung - danh sach 1 dong thi bam`() {
        assertEquals(0, pickSwitchEntry(listOf("a"), "a", null))
    }

    @Test fun `khong thay dich`() {
        assertNull(pickSwitchEntry(listOf("a", "b"), "c", "a"))
        assertNull(pickSwitchEntry(emptyList(), "c", null))
    }

    @Test fun `bo qua hoa thuong va tien to at`() {
        assertEquals(1, pickSwitchEntry(listOf("Foo", "Bar.Baz"), "@bar.baz", "foo"))
    }
}
