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
}
