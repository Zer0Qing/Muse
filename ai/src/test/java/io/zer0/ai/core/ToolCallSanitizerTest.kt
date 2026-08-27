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
        val tc = ToolCall(id = "1", name = "web_search", arguments = """{"query":"hello""" )
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
}
