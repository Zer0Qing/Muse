package io.zer0.muse.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件读取的结果合同（对比清单 P2-01）。
 *
 * 要解决的是"读到的到底是哪一版、哪一段"：之前返回的正文看起来永远完整，
 * 同一份文件改过之后再读，模型也无从判断拿到的是不是旧内容。
 */
class WorkspaceReadContractTest {

    private fun meta(
        offset: Int = 0,
        returned: Int = 100,
        total: Int = WorkspaceReadContract.TOTAL_UNKNOWN,
        revision: String = "1200:1699999999",
    ) = WorkspaceReadContract.Meta(
        displayPath = "notes.md",
        revision = revision,
        offsetChars = offset,
        returnedChars = returned,
        totalChars = total,
    )

    @Test
    fun `the header carries path revision and range`() {
        val header = WorkspaceReadContract.header(meta(total = 500))

        assertEquals("--- notes.md | revision=1200:1699999999 | chars 0..100 of 500 ---", header)
    }

    @Test
    fun `an unknown total is omitted rather than printed as minus one`() {
        val header = WorkspaceReadContract.header(meta())

        assertTrue("不能出现 -1: $header", !header.contains("-1"))
        assertTrue("要保留范围: $header", header.contains("chars 0..100"))
        assertFalse("总数未知时不该说 of: $header", header.contains("of "))
    }

    @Test
    fun `a blank revision is left out`() {
        val header = WorkspaceReadContract.header(meta(revision = ""))

        assertFalse("没有版本信息就不要写 revision=: $header", header.contains("revision="))
    }

    @Test
    fun `a paged read reports its own window`() {
        val header = WorkspaceReadContract.header(meta(offset = 4000, returned = 2000, total = 12000))

        assertTrue("要能看出读的是第二段: $header", header.contains("chars 4000..6000 of 12000"))
    }

    @Test
    fun `render puts the header before the content`() {
        val rendered = WorkspaceReadContract.render(meta(total = 500), "正文")

        assertTrue(rendered.startsWith("--- notes.md"))
        assertTrue(rendered.endsWith("正文"))
        assertEquals(2, rendered.lines().size)
    }

    @Test
    fun `the unknown sentinel stays negative so it cannot collide with a real length`() {
        assertTrue(WorkspaceReadContract.TOTAL_UNKNOWN < 0)
        assertFalse(meta(total = 0).totalKnown.not())
    }
}
