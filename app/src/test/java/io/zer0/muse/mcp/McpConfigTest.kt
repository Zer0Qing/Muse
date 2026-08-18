package io.zer0.muse.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-20: MCP 配置 DTO 纯逻辑测试。
 */
class McpConfigTest {

    @Test
    fun `token expires at deadline with thirty second margin`() {
        val token = McpTokenInfo(accessToken = "a", expiresAt = 1_000_000L)
        assertFalse(token.isExpired(now = 969_000L))
        assertTrue(token.isExpired(now = 970_000L))
        assertTrue(token.isExpired(now = 1_000_000L))
    }

    @Test
    fun `token with zero expiry never expires`() {
        val token = McpTokenInfo(accessToken = "a", expiresAt = 0L)
        assertFalse(token.isExpired(now = System.currentTimeMillis() + 1_000_000L))
    }

    @Test
    fun `token is valid only when access token present and not expired`() {
        assertFalse(McpTokenInfo(accessToken = "", expiresAt = 0L).isValid(now = 0L))
        assertTrue(McpTokenInfo(accessToken = "a", expiresAt = 1_000_000L).isValid(now = 969_000L))
        assertFalse(McpTokenInfo(accessToken = "a", expiresAt = 1_000_000L).isValid(now = 970_000L))
    }

    @Test
    fun `token can refresh only with refresh token`() {
        assertTrue(McpTokenInfo(refreshToken = "r").canRefresh())
        assertFalse(McpTokenInfo(refreshToken = "").canRefresh())
    }

    @Test
    fun `resolved headers adds bearer auth when token present`() {
        val config = McpServerConfig(id = "m1", name = "M1", authToken = "secret")
        val headers = config.resolvedHeaders()
        assertEquals("Bearer secret", headers["Authorization"])
    }

    @Test
    fun `resolved headers keeps explicit authorization header`() {
        val config = McpServerConfig(
            id = "m1",
            name = "M1",
            authToken = "fallback",
            headers = mapOf("Authorization" to "Custom abc"),
        )
        assertEquals("Custom abc", config.resolvedHeaders()["Authorization"])
    }

    @Test
    fun `resolved headers omits auth when token blank`() {
        val config = McpServerConfig(id = "m1", name = "M1")
        assertFalse(config.resolvedHeaders().containsKey("Authorization"))
    }

    @Test
    fun `server config serialization round trips`() {
        val config = McpServerConfig(
            id = "m1",
            name = "M1",
            transportType = McpTransportType.SSE,
            url = "https://example.com/sse",
            headers = mapOf("X-Test" to "1"),
            authToken = "token",
            enabled = false,
            maxReconnectAttempts = 9,
            requestTimeoutMs = 7_000L,
            oauthConfig = McpOAuthConfig(enabled = true, clientId = "cid", scopes = "tools:read"),
        )
        val json = io.zer0.common.AppJson.encodeToString(McpServerConfig.serializer(), config)
        val decoded = io.zer0.common.AppJson.decodeFromString(McpServerConfig.serializer(), json)
        assertEquals(config, decoded)
    }

    @Test
    fun `feishu auth requires both app id and app secret`() {
        assertFalse(McpFeishuAuthConfig(enabled = true, appId = "id").isConfigured())
        assertFalse(McpFeishuAuthConfig(enabled = true, appSecret = "secret").isConfigured())
        assertTrue(
            McpFeishuAuthConfig(
                enabled = true,
                appId = "id",
                appSecret = "secret",
            ).isConfigured(),
        )
    }

    @Test
    fun `feishu tenant token response uses expire seconds`() {
        val token = McpFeishuTenantTokenClient().parseTokenResponse(
            body = """{"tenant_access_token":"t-test","expire":7200}""",
            now = 1_000L,
        )

        assertEquals("t-test", token?.accessToken)
        assertEquals(7_201_000L, token?.expiresAt)
    }

    @Test
    fun `feishu tenant token response without token is rejected`() {
        assertEquals(
            null,
            McpFeishuTenantTokenClient().parseTokenResponse(
                body = """{"code":999,"msg":"invalid app"}""",
                now = 1_000L,
            ),
        )
    }
}
