package io.zer0.muse.data

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
            "mcp_servers_json" to """[{"authToken":"token"}]""",
            "cloud_backup_config_json" to """{"webdavPassword":"p"}""",
            "theme_id" to "mono",
        )
        val safe = SettingsSnapshotPolicy.sanitize(raw)
        assertFalse(safe.containsKey("providers_json"))
        assertFalse(safe.containsKey("web_server_config_json"))
        assertFalse(safe.containsKey("mcp_servers_json"))
        assertFalse(safe.containsKey("cloud_backup_config_json"))
        assertTrue(safe["theme_id"] == "mono")
        assertFalse(safe.values.any { it.contains("sk-fake-123") || it.contains("secret") || it.contains("token") })
    }

    @Test
    fun `whitelisted keys pass through`() {
        assertTrue(SettingsSnapshotPolicy.isSafeKey("theme_id"))
        assertTrue(SettingsSnapshotPolicy.isSafeKey("bool:keep_awake"))
        assertTrue(SettingsSnapshotPolicy.isSafeKey("int:default_home_page"))
        assertTrue(SettingsSnapshotPolicy.isSafeKey("long:account_login_at"))
    }

    @Test
    fun `sensitive fragments block future additions`() {
        assertFalse(SettingsSnapshotPolicy.isSafeKey("asr_config_json"))
        assertFalse(SettingsSnapshotPolicy.isSafeKey("web_search_config_json"))
        assertFalse(SettingsSnapshotPolicy.isSafeKey("api_keys_json"))
    }
}
