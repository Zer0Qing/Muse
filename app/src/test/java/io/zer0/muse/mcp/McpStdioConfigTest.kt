package io.zer0.muse.mcp

import io.zer0.common.AppJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-A: stdio 传输配置序列化与 SSRF 边界测试（纯逻辑）。
 */
class McpStdioConfigTest {

    @Test
    fun stdioConfigRoundTrips() {
        val config = McpServerConfig(
            id = "s1",
            name = "local",
            transportType = McpTransportType.STDIO,
            command = "npx -y @modelcontextprotocol/server-memory",
        )
        val json = AppJson.encodeToString(McpServerConfig.serializer(), config)
        val decoded = AppJson.decodeFromString(McpServerConfig.serializer(), json)
        assertEquals(McpTransportType.STDIO, decoded.transportType)
        assertEquals("npx -y @modelcontextprotocol/server-memory", decoded.command)
    }

    @Test
    fun legacyConfigWithoutCommandStillParses() {
        // 旧配置 JSON（无 command 字段）向后兼容
        val legacy = """{"id":"old","name":"old","transportType":"STREAMABLE_HTTP","url":"https://example.com/mcp"}"""
        val decoded = AppJson.decodeFromString(McpServerConfig.serializer(), legacy)
        assertEquals("", decoded.command)
        assertEquals(McpTransportType.STREAMABLE_HTTP, decoded.transportType)
    }

    @Test
    fun stdioIsNeverSsrfBlocked() {
        val config = McpServerConfig(
            id = "s1",
            name = "local",
            transportType = McpTransportType.STDIO,
            command = "npx -y foo",
        )
        assertFalse(config.isSsrfBlocked())
    }

    @Test
    fun remoteTransportStillSsrfChecked() {
        val config = McpServerConfig(id = "s1", name = "r", url = "http://192.168.1.1:8080/mcp")
        assertTrue(config.isSsrfBlocked())
    }

    /**
     * STDIO 的合法配置是 command 非空、url 为空。
     * 以前五处连接判定都写死 `url.isNotBlank()`，于是从界面添加一个 stdio server、
     * 或冷启动自动连接时，它**根本不会被发起连接**（只有手点"重连"那条路不带这个判定），
     * 表现就是"MCP 工具永远不出现在聊天里"，而且没有任何报错。
     */
    @Test
    fun stdioConfigWithCommandIsConnectableWithoutUrl() {
        val stdio = McpServerConfig(
            id = "s-stdio",
            name = "local",
            transportType = McpTransportType.STDIO,
            command = "npx -y @modelcontextprotocol/server-memory",
        )
        assertTrue("stdio 只填 command 就该可连接（url 本来就该是空的）", stdio.isConnectable())

        val noCommand = stdio.copy(command = "   ")
        assertFalse("没有启动命令的 stdio 配置不算可连接", noCommand.isConnectable())
    }

    @Test
    fun remoteAndDisabledConfigsFollowTheirOwnRequiredField() {
        val remote = McpServerConfig(id = "r1", name = "remote", url = "https://example/mcp")
        assertTrue(remote.isConnectable())
        assertFalse("HTTP 缺 url 不算可连接", remote.copy(url = "").isConnectable())
        assertFalse("已停用的配置一律不自动连接", remote.copy(enabled = false).isConnectable())
    }
}
