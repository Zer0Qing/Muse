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
