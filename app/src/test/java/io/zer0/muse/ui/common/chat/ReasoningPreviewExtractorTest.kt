package io.zer0.muse.ui.common.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningPreviewExtractorTest {

    @Test
    fun `extractCurrentFocus prefers latest bold or markdown heading`() {
        val text = """
            ### 第一步：读取配置文件
            先检查本地是否有残留。

            **核对数据库连接与迁移**
            再看下 Room 的 schema 导出路径。
        """.trimIndent()

        assertEquals("核对数据库连接与迁移", ReasoningPreviewExtractor.extractCurrentFocus(text))
    }

    @Test
    fun `extractCurrentFocus falls back to latest non-empty clause`() {
        val text = "先确认用户意图。再对比两种实现方案的取舍。"
        assertEquals("再对比两种实现方案的取舍", ReasoningPreviewExtractor.extractCurrentFocus(text))
    }

    @Test
    fun `extractLivePreviewWindow keeps aligned trailing window`() {
        val longText = (1..30).joinToString("\n") { "第 $it 步：验证边界条件与状态同步逻辑" }
        val preview = ReasoningPreviewExtractor.extractLivePreviewWindow(longText, maxChars = 120)
        assertTrue(preview.startsWith("…"))
        assertTrue(preview.contains("第 30 步"))
        assertTrue(preview.length <= 122)
    }
}
