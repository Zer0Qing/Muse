package io.zer0.muse.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换行分割要覆盖三种历史写法：Unix `\n`、Windows `\r\n`、旧 Mac 单独 `\r`。
 *
 * 分割不全的后果不是「少一个空行」，而是**整段被当成一行**：
 * 围栏代码块的结束 ``` 识别不到，后面的正文全被吞进代码块里 ——
 * 用户看到的是「回复突然变成一大块代码」。
 */
class MarkdownLineSplitTest {

    private fun split(text: String): List<String> = MARKDOWN_LINE_SPLIT.split(text)

    @Test
    fun `Unix 换行`() {
        assertEquals(listOf("第一行", "第二行"), split("第一行\n第二行"))
    }

    @Test
    fun `Windows 换行`() {
        assertEquals(listOf("第一行", "第二行"), split("第一行\r\n第二行"))
    }

    @Test
    fun `旧 Mac 单独回车也要能分`() {
        assertEquals(listOf("第一行", "第二行"), split("第一行\r第二行"))
    }

    @Test
    fun `混合换行不会留下空串`() {
        val parts = split("甲\r\n乙\r丙\n丁")
        assertEquals(listOf("甲", "乙", "丙", "丁"), parts)
        assertTrue("不该切出空串: $parts", parts.none { it.isEmpty() })
    }

    @Test
    fun `围栏代码块在三种换行下都能闭合`() {
        val fence = "`".repeat(3)
        listOf("\n", "\r\n", "\r").forEach { nl ->
            val text = "说明${nl}${fence}${nl}代码${nl}${fence}${nl}之后的话"
            val lines = split(text)
            assertEquals("换行=$nl 时应切出 5 行，实际 $lines", 5, lines.size)
            assertEquals("结尾围栏要能被单独识别（换行=$nl）", fence, lines[3])
        }
    }
}
