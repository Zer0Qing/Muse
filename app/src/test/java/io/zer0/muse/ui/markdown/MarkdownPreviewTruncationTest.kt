package io.zer0.muse.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏卡片预览的结构感知截断（审计 §11-9）。
 *
 * 原来的实现是 `body.take(280)`，切在未闭合代码围栏里会让之后的全部内容
 * 都渲染成代码块；切在 `**` / `[N]` 中间会漏出半截标记。
 */
class MarkdownPreviewTruncationTest {

    private fun fences(text: String): Int = Regex("```").findAll(text).count()

    @Test
    fun `short text is returned untouched without ellipsis`() {
        val body = "一句很短的话"

        assertEquals(body, MarkdownPreviewTruncation.preview(body))
    }

    @Test
    fun `long text ends with ellipsis and stays within limit plus ellipsis`() {
        val body = (1..80).joinToString(" ") { "第${it}段内容都在这里" }

        val preview = MarkdownPreviewTruncation.preview(body, limit = 120)

        assertTrue("超长文本必须带省略号", preview.endsWith(MarkdownPreviewTruncation.ELLIPSIS))
        assertTrue(
            "预览长度不能明显超出上限: ${preview.length}",
            preview.length <= 120 + MarkdownPreviewTruncation.ELLIPSIS.length,
        )
    }

    @Test
    fun `unclosed code fence is dropped instead of leaking into following text`() {
        // 围栏开在 280 字符以内、闭合在 280 字符以外 —— 旧的字符级截断会留下一个孤零零的 ```
        val filler = "a".repeat(200)
        val body = "$filler\n\n```kotlin\n" + "val x = 1\n".repeat(40) + "```\n"

        val preview = MarkdownPreviewTruncation.preview(body, limit = 280)

        assertEquals("预览里不能残留未闭合围栏", 0, fences(preview))
    }

    @Test
    fun `closed code fence inside the window is preserved as a whole block`() {
        val code = "```\ncode line\n```"
        // 代码块整块在窗口内，后面的长文超出窗口被舍 —— 结果里围栏必须成对。
        val body = "$code\n\n" + "后面还有很多很多内容".repeat(40)

        val preview = MarkdownPreviewTruncation.preview(body, limit = 200)

        assertEquals("成对围栏必须整体保留", 2, fences(preview))
        assertTrue("代码块内容应完整保留", preview.contains("code line"))
    }

    @Test
    fun `truncation does not split a bold marker`() {
        val body = "x".repeat(276) + "**重点**"

        val preview = MarkdownPreviewTruncation.preview(body, limit = 280)

        assertFalse("不能把 ** 切开后留在文本里", preview.dropLast(1).endsWith("*"))
    }

    @Test
    fun `truncation does not split a citation bracket`() {
        val body = "x".repeat(278) + "[12] 结尾"

        val preview = MarkdownPreviewTruncation.preview(body, limit = 280)

        assertFalse("不能把 [N] 切一半留在文本里", preview.contains("[1" + MarkdownPreviewTruncation.ELLIPSIS))
    }

    @Test
    fun `truncates on block boundary instead of mid paragraph`() {
        val blockOne = "第一段".repeat(20) // 60 字
        val blockTwo = "第二段".repeat(20)
        val body = "$blockOne\n\n$blockTwo"

        val preview = MarkdownPreviewTruncation.preview(body, limit = 80)

        assertTrue("应完整保留第一段", preview.startsWith(blockOne))
        assertFalse("不应把第二段切一半塞进来", preview.contains(blockTwo.take(5)))
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", MarkdownPreviewTruncation.preview(""))
    }
}
