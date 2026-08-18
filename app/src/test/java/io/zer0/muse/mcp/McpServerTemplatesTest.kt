package io.zer0.muse.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerTemplatesTest {

    @Test
    fun `template ids are unique and include custom entry`() {
        val ids = McpServerTemplates.all.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertTrue(McpServerTemplates.find(McpServerTemplates.CUSTOM_ID) === McpServerTemplates.custom)
    }

    @Test
    fun `feishu and cli templates use their intended transports`() {
        val remote = McpServerTemplates.find("feishu_remote")
        val bridge = McpServerTemplates.find("feishu_cli_bridge")

        assertEquals(McpTransportType.STREAMABLE_HTTP, remote.transportType)
        assertEquals(McpTransportType.SSE, bridge.transportType)
        assertTrue(remote.defaultHeaders.containsKey("X-Lark-MCP-Allowed-Tools"))
    }

    @Test
    fun `unknown template falls back to custom`() {
        assertEquals(McpServerTemplates.custom, McpServerTemplates.find("missing-template"))
    }
}
