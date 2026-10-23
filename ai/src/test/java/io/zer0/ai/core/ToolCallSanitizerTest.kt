package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallSanitizerTest {
    @Test
    fun `valid tool call is accepted`() {
        val tc = ToolCall(id = "1", name = "web_search", arguments = """{"query":"hello"}""")
        assertTrue(ToolCallSanitizer.isValid(tc))
        assertEquals(listOf(tc), ToolCallSanitizer.sanitize(listOf(tc)))
    }

    @Test
    fun `blank name is rejected`() {
        val tc = ToolCall(id = "1", name = "  ", arguments = """{"query":"hello"}""")
        assertFalse(ToolCallSanitizer.isValid(tc))
        assertTrue(ToolCallSanitizer.sanitize(listOf(tc)).isEmpty())
    }

    @Test
    fun `blank arguments are preserved as empty object`() {
        val tc = ToolCall(id = "1", name = "web_search", arguments = " ")
        val sanitized = ToolCallSanitizer.sanitize(listOf(tc))
        assertEquals(1, sanitized.size)
        assertEquals("{}", sanitized.single().arguments)
    }

    @Test
    fun `truncated json is balanced and preserved`() {
        val tc = ToolCall(id = "1", name = "web_search", arguments = """{"query":"hello""")
        val sanitized = ToolCallSanitizer.sanitize(listOf(tc)).single()
        assertEquals("""{"query":"hello"}""", sanitized.arguments)
    }

    @Test
    fun `concatenated objects are merged`() {
        val tc = ToolCall(id = "1", name = "web_search", arguments = """{"query":"x"}{"max_results":5}""")
        val sanitized = ToolCallSanitizer.sanitize(listOf(tc)).single()
        assertTrue(sanitized.arguments.contains("\"query\""))
        assertTrue(sanitized.arguments.contains("\"max_results\""))
        assertTrue(ToolCallSanitizer.isValidJson(sanitized.arguments))
    }

    @Test
    fun `unrecoverable arguments remain callable with empty object`() {
        val tc = ToolCall(id = "1", name = "web_search", arguments = "not json")
        val sanitized = ToolCallSanitizer.sanitize(listOf(tc)).single()
        assertEquals("{}", sanitized.arguments)
    }

    @Test
    fun `valid json arguments are untouched`() {
        val ok = ToolCall(id = "1", name = "speak_text", arguments = """{"text":"hello"}""")
        assertEquals(ok, ToolCallSanitizer.sanitize(listOf(ok))[0])
    }

    @Test
    fun `json array arguments are accepted`() {
        val arr = ToolCall(id = "1", name = "x", arguments = """[1,2,3]""")
        assertTrue(ToolCallSanitizer.isValidJson(arr.arguments))
        assertEquals(arr, ToolCallSanitizer.sanitize(listOf(arr))[0])
    }

    @Test
    fun `diagnostic argument preview does not expose sensitive input`() {
        val preview = ToolCallSanitizer.safeArgumentsForLog(
            toolName = "browser_type",
            arguments = """{"selector":"#password","text":"super-secret-password"}""",
        )

        assertFalse(preview.contains("super-secret-password"))
        assertTrue(preview.contains("[REDACTED]"))
        assertTrue(preview.contains("browser_type"))
    }

    // ── v2.6.7: 模型把正文写进 function.name 的保护 ──────────────────

    /**
     * 实测日志：GLM-4-9B 返回 name="好的，子奇！我会创建一个交互式图表…show_card"。
     * 旧实现只查 isNotBlank()，这条调用会一路通过清洗、进审批、被当成工具执行，
     * 最终以“未知工具”失败，用户感知就是“卡片根本生成不了”。
     */
    @Test
    fun `chinese prose in tool name is rejected`() {
        val tc = ToolCall(
            id = "1",
            name = "好的，子奇！我会创建一个简单的交互式图表来展示不同水果的数量。请稍等，我马上为你生成。show_card",
            arguments = """{"title":"x"}""",
        )
        assertFalse(ToolCallSanitizer.isValidName(tc.name))
        assertFalse(ToolCallSanitizer.isValid(tc))
        assertTrue(ToolCallSanitizer.sanitize(listOf(tc)).isEmpty())
    }

    @Test
    fun `tool name with spaces or punctuation is rejected`() {
        assertFalse(ToolCallSanitizer.isValidName("web search"))
        assertFalse(ToolCallSanitizer.isValidName("web.search"))
        assertFalse(ToolCallSanitizer.isValidName("web搜索"))
    }

    @Test
    fun `legitimate tool names are accepted`() {
        assertTrue(ToolCallSanitizer.isValidName("web_search"))
        assertTrue(ToolCallSanitizer.isValidName("show_card"))
        assertTrue(ToolCallSanitizer.isValidName("mcp-server-tool"))
        assertTrue(ToolCallSanitizer.isValidName("tool2"))
    }

    @Test
    fun `overlong tool name is rejected`() {
        assertFalse(ToolCallSanitizer.isValidName("a".repeat(65)))
    }
}
