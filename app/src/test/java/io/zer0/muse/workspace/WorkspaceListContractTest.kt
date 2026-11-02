package io.zer0.muse.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录列举的结果合同（对比清单 P2-01「目录游标」）。
 *
 * 要防的是"把几千项一次性倒进上下文"：用户看到的是一段没有尽头、也没法接着看的清单，
 * 模型则被挤掉了真正需要的信息。
 */
class WorkspaceListContractTest {

    private fun lines(count: Int) = (1..count).map { "f$it | file | 10 | 0" }

    @Test
    fun `an empty directory says so instead of returning nothing`() {
        val rendered = WorkspaceListContract.render(emptyList())

        assertEquals("(empty)", rendered.text)
        assertEquals(0, rendered.shown)
        assertFalse(rendered.truncated)
    }

    @Test
    fun `a small directory is listed in full`() {
        val rendered = WorkspaceListContract.render(lines(3))

        assertEquals(3, rendered.shown)
        assertFalse(rendered.truncated)
        assertFalse("没有截断就不要出现提示", rendered.text.contains("还有"))
    }

    @Test
    fun `a huge directory is capped and says how many are left`() {
        val rendered = WorkspaceListContract.render(lines(WorkspaceListContract.MAX_ENTRIES + 37))

        assertEquals(WorkspaceListContract.MAX_ENTRIES, rendered.shown)
        assertEquals(37, rendered.hidden)
        assertTrue(rendered.truncated)
        assertTrue("要说清还剩多少: ${rendered.text.takeLast(120)}", rendered.text.contains("还有 37 项"))
    }

    @Test
    fun `the truncation hint tells the model what to do next`() {
        val rendered = WorkspaceListContract.render(lines(WorkspaceListContract.MAX_ENTRIES + 1))

        assertTrue("要给出下一步: ${rendered.text.takeLast(160)}", rendered.text.contains("缩小 path"))
        assertTrue("也要指出另一条路: ${rendered.text.takeLast(160)}", rendered.text.contains("workspace_grep"))
    }

    @Test
    fun `the cap is applied to what is shown not to what is counted`() {
        val rendered = WorkspaceListContract.render(lines(5), maxEntries = 2)

        assertEquals(2, rendered.shown)
        assertEquals(3, rendered.hidden)
        assertTrue(rendered.text.startsWith("f1"))
    }

    @Test
    fun `a non positive cap still shows at least one entry`() {
        val rendered = WorkspaceListContract.render(lines(3), maxEntries = 0)

        assertEquals("上限写成 0 也不能什么都不给", 1, rendered.shown)
    }
}
