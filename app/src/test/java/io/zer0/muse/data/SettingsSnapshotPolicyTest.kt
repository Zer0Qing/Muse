package io.zer0.muse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-04: 备份设置快照的密钥剔除策略。
 */
class SettingsSnapshotPolicyTest {

    @Test
    fun `sensitive keys are excluded from backup snapshot`() {
        val raw = mapOf(
            "providers_json" to """[{"apiKey":"sk-fake-123"}]""",
            "web_server_config_json" to """{"password":"secret"}""",
            "mcp_token_cache" to "token",
            "cloud_backup_config_json" to """{"webdavPassword":"p"}""",
            "theme_id" to "mono",
        )
        val safe = SettingsSnapshotPolicy.sanitize(raw)
        assertFalse(safe.containsKey("providers_json"))
        assertFalse(safe.containsKey("web_server_config_json"))
        assertFalse(safe.containsKey("mcp_token_cache"))
        assertFalse(safe.containsKey("cloud_backup_config_json"))
        assertTrue(safe["theme_id"] == "mono")
        assertFalse(safe.values.any { it.contains("sk-fake-123") || it.contains("secret") || it.contains("token") })
    }

    @Test
    fun `portable MCP config preserves identity but strips credentials`() {
        val config = io.zer0.muse.mcp.McpServerConfig(
            id = "server-1",
            name = "Files",
            url = "https://example.invalid/mcp",
            oauthConfig = io.zer0.muse.mcp.McpOAuthConfig(
                enabled = true,
                clientId = "public-client",
                authorizationEndpoint = "https://identity.invalid/authorize?client_secret=oauth-secret",
                tokenEndpoint = "https://identity.invalid/token",
            ),
            headers = mapOf("Authorization" to "Bearer secret"),
            authToken = "static-secret",
            feishuAuth = io.zer0.muse.mcp.McpFeishuAuthConfig(
                enabled = true,
                appId = "app-id",
                appSecret = "app-secret",
            ),
            environment = mapOf("GITHUB_TOKEN" to "env-secret"),
        )

        val portable = SettingsSnapshotPolicy.portableMcpConfig(config)

        assertEquals(config.id, portable.id)
        assertEquals(config.name, portable.name)
        assertEquals(config.url, portable.url)
        assertTrue(portable.headers.isEmpty())
        assertTrue(portable.authToken.isEmpty())
        assertFalse(portable.feishuAuth.enabled)
        assertTrue(portable.feishuAuth.appId.isEmpty())
        assertTrue(portable.feishuAuth.appSecret.isEmpty())
        assertEquals(mapOf("GITHUB_TOKEN" to ""), portable.environment)
        assertFalse(portable.toString().contains("env-secret"))
        assertTrue(portable.oauthConfig.enabled)
        assertTrue(portable.oauthConfig.authorizationEndpoint == "https://identity.invalid/authorize")
        assertFalse(portable.toString().contains("oauth-secret"))
    }

    @Test
    fun `portable MCP config removes URL credentials and disables STDIO`() {
        val remote = io.zer0.muse.mcp.McpServerConfig(
            id = "remote",
            name = "Remote",
            url = "https://user:password@example.invalid/mcp?access_token=url-secret",
        )
        val portableRemote = SettingsSnapshotPolicy.portableMcpConfig(remote)
        assertEquals("https://example.invalid/mcp", portableRemote.url)
        assertFalse(portableRemote.toString().contains("password"))
        assertFalse(portableRemote.toString().contains("url-secret"))

        val local = io.zer0.muse.mcp.McpServerConfig(
            id = "local",
            name = "Local",
            transportType = io.zer0.muse.mcp.McpTransportType.STDIO,
            command = "npx -y secret-package --token command-secret",
            environment = mapOf("API_TOKEN" to "env-secret"),
        )
        val portableLocal = SettingsSnapshotPolicy.portableMcpConfig(local)
        assertEquals("", portableLocal.command)
        assertFalse(portableLocal.enabled)
        assertFalse(portableLocal.toString().contains("command-secret"))
    }

    @Test
    fun `MCP credentials stay only with unchanged connection identity`() {
        val local = io.zer0.muse.mcp.McpServerConfig(
            id = "server",
            name = "Server",
            url = "https://one.invalid/mcp",
        )
        val same = SettingsSnapshotPolicy.portableMcpConfig(local)
        val changedUrl = same.copy(url = "https://two.invalid/mcp")
        val changedTransport = same.copy(transportType = io.zer0.muse.mcp.McpTransportType.SSE)

        assertTrue(SettingsSnapshotPolicy.sameMcpConnectionIdentity(local, same))
        assertFalse(SettingsSnapshotPolicy.sameMcpConnectionIdentity(local, changedUrl))
        assertFalse(SettingsSnapshotPolicy.sameMcpConnectionIdentity(local, changedTransport))
    }

    @Test
    fun `whitelisted keys pass through`() {
        assertTrue(SettingsSnapshotPolicy.isSafeKey(SettingsSnapshotPolicy.MCP_SERVERS_KEY))
        assertTrue(SettingsSnapshotPolicy.isSafeKey("theme_id"))
        assertTrue(SettingsSnapshotPolicy.isSafeKey("bool:keep_awake"))
        assertTrue(SettingsSnapshotPolicy.isSafeKey("int:default_home_page"))
        assertTrue(SettingsSnapshotPolicy.isSafeKey("long:account_login_at"))
        assertTrue(SettingsSnapshotPolicy.isSafeKey("chat_background"))
        assertTrue(SettingsSnapshotPolicy.isSafeKey("chat_background_dark"))
        assertTrue(SettingsSnapshotPolicy.isSafeKey("chat_background_assistant_alpha_1"))
        assertTrue(SettingsSnapshotPolicy.isSafeKey("chat_background_session_abc-123"))
        assertFalse(SettingsSnapshotPolicy.isSafeKey("chat_background_session_../../outside"))
    }

    @Test
    fun `stringSet favourites are whitelisted`() {
        // AUDIT §11.3-7: 这两项是用户资产,必须进备份。
        assertTrue(SettingsSnapshotPolicy.isSafeKey("stringSet:moment_favorite_ids"))
        assertTrue(SettingsSnapshotPolicy.isSafeKey("stringSet:mini_album_favorite_image_ids"))
        // 类型敏感:不带前缀 = string 类型,不能因此把 stringSet 白名单里的名字当 string 放行
        // (否则一份褐改的备份能把同名 key 以另一种类型写进 DataStore)。
        assertFalse(SettingsSnapshotPolicy.isSafeKey("moment_favorite_ids"))
        assertFalse(SettingsSnapshotPolicy.isSafeKey("bool:theme_id"))
        assertFalse(SettingsSnapshotPolicy.isSafeKey("int:keep_awake"))
    }

    @Test
    fun `scheduling and view state stringSets are excluded`() {
        // 刻意不进备份的三个 stringSet:
        //  - daily_summary_*: 调度去重状态,恢复旧值会让今天/本时点的总结被当成已完成;
        //  - miniphone_hidden_apps: "隐藏了哪些应用"是设备侧视图状态;
        //  - mini_album_hidden_image_ids: 隐藏列表,与相册派生数据耦合,跨设备无意义。
        assertFalse(SettingsSnapshotPolicy.isSafeKey("stringSet:daily_summary_claimed_slots"))
        assertFalse(SettingsSnapshotPolicy.isSafeKey("stringSet:daily_summary_completed_slots"))
        assertFalse(SettingsSnapshotPolicy.isSafeKey("stringSet:miniphone_hidden_apps"))
        assertFalse(SettingsSnapshotPolicy.isSafeKey("stringSet:mini_album_hidden_image_ids"))
    }

    @Test
    fun `sensitive fragments block future additions`() {
        assertFalse(SettingsSnapshotPolicy.isSafeKey("asr_config_json"))
        assertFalse(SettingsSnapshotPolicy.isSafeKey("web_search_config_json"))
        assertFalse(SettingsSnapshotPolicy.isSafeKey("api_keys_json"))
    }

    @Test
    fun `media config excluded from backup snapshot`() {
        // P0-2: MediaConfig 含 ttsApiKey 凭据,media_config_json 不得出现在备份快照中
        assertFalse("media_config_json 必须从备份快照排除", SettingsSnapshotPolicy.isSafeKey("media_config_json"))
        val raw = mapOf("media_config_json" to """{"ttsApiKey":"sk-tts-123","ttsEngine":"openai"}""")
        val safe = SettingsSnapshotPolicy.sanitize(raw)
        assertFalse(safe.containsKey("media_config_json"))
        assertFalse(safe.values.any { it.contains("sk-tts-123") })
    }
}
