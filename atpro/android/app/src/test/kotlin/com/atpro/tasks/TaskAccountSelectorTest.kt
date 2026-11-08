package com.atpro.tasks

import org.junit.Assert.*
import org.junit.Test

/** TaskAccountSelectorTest — v1.12.0: chọn tài khoản nhận nhiệm vụ. */
class TaskAccountSelectorTest {

    private fun acc(id: String, name: String) = TaskAccount(id, name)

    private val linked = listOf(acc("1", "alpha"), acc("2", "Beta"), acc("3", "gamma"))

    @Test fun `AUTO lay moi acc da lien ket va co tren may theo thu tu tren may`() {
        val r = TaskAccountSelector.select(listOf("gamma", "@alpha", "zeta"), linked, TaskAccountMode.AUTO, emptyList())
        assertEquals(listOf("gamma", "alpha"), r.matched.map { it.first })
        assertEquals(listOf("3", "1"), r.matched.map { it.second.id })
        assertEquals(listOf("zeta"), r.deviceNotLinked)
        assertTrue(r.notSelected.isEmpty())
    }

    @Test fun `SELECTED chi lay acc duoc chon`() {
        val r = TaskAccountSelector.select(listOf("alpha", "beta", "gamma"), linked, TaskAccountMode.SELECTED, listOf("BETA", "@gamma"))
        assertEquals(listOf("beta", "gamma"), r.matched.map { it.first })
        assertEquals(listOf("alpha"), r.notSelected)
    }

    @Test fun `SELECTED bao acc da chon nhung khong co tren may hoac chua lien ket`() {
        val r = TaskAccountSelector.select(listOf("alpha", "omega"), linked, TaskAccountMode.SELECTED, listOf("alpha", "gamma", "omega", "ghost"))
        assertEquals(listOf("alpha"), r.matched.map { it.first })
        assertEquals(listOf("gamma", "ghost"), r.selectedNotOnDevice)          // gamma có liên kết nhưng không có trên máy
        assertEquals(listOf("omega", "ghost"), r.selectedNotLinked)
    }

    @Test fun `SELECTED rong thi khong acc nao duoc lam`() {
        val r = TaskAccountSelector.select(listOf("alpha", "beta"), linked, TaskAccountMode.SELECTED, emptyList())
        assertTrue(r.matched.isEmpty())
        assertEquals(listOf("alpha", "beta"), r.notSelected)
    }

    @Test fun `khong phan biet hoa thuong va tien to at va trung lap`() {
        val r = TaskAccountSelector.select(listOf("ALPHA", "@alpha", " alpha "), linked, TaskAccountMode.AUTO, emptyList())
        assertEquals(1, r.matched.size)
        assertEquals("ALPHA", r.matched.single().first)       // giữ tên hiển thị trên máy (lần gặp đầu)
    }

    @Test fun `danh sach rong`() {
        assertTrue(TaskAccountSelector.select(emptyList(), linked, TaskAccountMode.AUTO, emptyList()).matched.isEmpty())
        assertTrue(TaskAccountSelector.select(listOf("alpha"), emptyList(), TaskAccountMode.AUTO, emptyList()).matched.isEmpty())
    }

    @Test fun `parseList va parseMode`() {
        assertEquals(listOf("a", "b", "c"), TaskAccountSelector.parseList(" A, @b;c\n\n@A "))
        assertEquals(emptyList<String>(), TaskAccountSelector.parseList(null))
        assertEquals(TaskAccountMode.SELECTED, TaskAccountMode.parse("selected"))
        assertEquals(TaskAccountMode.AUTO, TaskAccountMode.parse("rác"))
        assertEquals(TaskAccountMode.AUTO, TaskAccountMode.parse(null))
    }
}
