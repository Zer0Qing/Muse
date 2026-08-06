package io.zer0.muse.backup

import io.zer0.muse.data.SettingsSnapshotPolicy
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-04: 备份产物扫描 — 序列化后的备份 JSON 不得包含任何 API Key 明文。
 */
class BackupKeyExclusionTest {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Test
    fun `serialized backup does not contain api key plaintext`() {
        val rawSettings = mapOf(
            "providers_json" to """[{"id":"openai","apiKey":"sk-fake-123"}]""",
            "web_server_config_json" to """{"password":"secret-pin"}""",
            "theme_id" to "mono",
        )
        val backup = BackupService.Backup(
            version = 3,
            exportedAt = 0L,
            sessions = emptyList(),
            messages = emptyList(),
            settingsSnapshot = SettingsSnapshotPolicy.sanitize(rawSettings),
        )
        val text = json.encodeToString(BackupService.Backup.serializer(), backup)
        assertFalse(text.contains("sk-fake-123"))
        assertFalse(text.contains("secret-pin"))
        assertTrue(text.contains("mono"))
    }
}
