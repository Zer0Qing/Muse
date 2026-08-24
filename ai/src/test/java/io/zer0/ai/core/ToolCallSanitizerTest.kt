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
    fun `blank arguments are rejected`() {
        val tc = ToolCall(id = "1", name = "web_search", arguments = " ")
        assertFalse(ToolCallSanitizer.isValid(tc))
        assertTrue(ToolCallSanitizer.sanitize(listOf(tc)).isEmpty())
    }

    @Test
    fun `mixed list keeps only valid calls`() {
        val valid = ToolCall(id = "1", name = "web_search", arguments = """{"query":"hello"}""")
        val bad = ToolCall(id = "2", name = "", arguments = """{"query":"bad"}""")
        assertEquals(listOf(valid), ToolCallSanitizer.sanitize(listOf(bad, valid)))
    }

    @Test
    fun `invalid json arguments are repaired to empty object`() {
        val broken = ToolCall(id = "1", name = "speak_text", arguments = """{"text": "你好""")
        val repaired = ToolCallSanitizer.sanitize(listOf(broken))
        assertEquals(1, repaired.size)
        assertEquals("{}", repaired[0].arguments)
        assertEquals("speak_text", repaired[0].name)
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
}
