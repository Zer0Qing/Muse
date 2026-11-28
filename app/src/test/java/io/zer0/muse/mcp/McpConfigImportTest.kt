package io.zer0.muse.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpConfigImportTest {
    @Test
    fun `imports a collection without starting servers and keeps native command arguments`() {
        val input = """
            {"mcpServers":{
              "Remote":{"url":"https://example.com/mcp","type":"streamable-http","headers":{"X-Custom":"value"}},
              "Local":{"command":"npx","args":["two words","","a\"b"],"env":{"SAMPLE":"test"}}
            }}
        """.trimIndent()
        val ids = listOf("remote", "local").iterator()
        val configs = checkNotNull(McpConfigImport.parse(input, ids::next))
        val remote = configs[0]
        val local = configs[1]

        assertEquals(McpTransportType.STREAMABLE_HTTP, remote.transportType)
        assertEquals(mapOf("X-Custom" to "value"), remote.headers)
        assertEquals(McpTransportType.STDIO, local.transportType)
        assertEquals(listOf("npx", "two words", "", "a\"b"), McpStdioCommand.tokenize(local.command))
        assertEquals(mapOf("SAMPLE" to "test"), local.environment)
    }

    @Test
    fun `rejects ambiguous addresses invalid environment names and malformed config`() {
        assertNull(McpConfigImport.parse("""{"url":"ftp://example.com/mcp"}"""))
        assertNull(McpConfigImport.parse("""{"url":"https://user:pass@example.com/mcp"}"""))
        assertNull(McpConfigImport.parse("""{"command":"node","env":{"BAD NAME":"value"}}"""))
        assertNull(McpConfigImport.parse("""{"mcpServers":[]}"""))
    }
}
