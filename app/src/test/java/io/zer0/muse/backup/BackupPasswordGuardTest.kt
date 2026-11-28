package io.zer0.muse.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-1 回归:备份密码失效守卫 [BackupService.isBackupPasswordUnavailable]。
 *
 * 覆盖"已设密码 + 密码读空(Keystore 失效)→ 云端或密码保护单 JSON 导出必须拒绝",
 * 防止加密备份被静默降级为明文写盘。流式本地 NDJSON 是用户确认后的明文格式，
 * 不依赖云备份密码。
 */
class BackupPasswordGuardTest {

    @Test
    fun cloudProviderSecretsRequireAnEncryptionPassword() {
        val unencrypted = CloudBackupConfig()
        assertTrue(BackupService.requiresCloudBackupPassword(unencrypted, includeSecrets = true))
        assertFalse(BackupService.requiresCloudBackupPassword(unencrypted, includeSecrets = false))
        assertFalse(BackupService.requiresCloudBackupPassword(unencrypted.copy(backupPassword = "test"), includeSecrets = true))
    }

    @Test
    fun passwordSetButBlank_isUnavailable() {
        // 用户曾设置密码,但 Keystore 密钥丢失后 decrypt 读回为空串
        val config = CloudBackupConfig(backupPassword = "", backupPasswordSet = true)
        assertTrue(BackupService.isBackupPasswordUnavailable(config))
    }

    @Test
    fun passwordSetAndPresent_isAvailable() {
        val config = CloudBackupConfig(backupPassword = "secret", backupPasswordSet = true)
        assertFalse(BackupService.isBackupPasswordUnavailable(config))
    }

    @Test
    fun neverSetAndBlank_isAvailable() {
        // 从未设置密码:允许明文导出(产品语义)
        val config = CloudBackupConfig(backupPassword = "", backupPasswordSet = false)
        assertFalse(BackupService.isBackupPasswordUnavailable(config))
    }

    @Test
    fun blankWithWhitespace_isUnavailable() {
        // 空格串同样视为不可用(与 isBlank 语义一致,防 trim 后空串绕过)
        val config = CloudBackupConfig(backupPassword = "   ", backupPasswordSet = true)
        assertTrue(BackupService.isBackupPasswordUnavailable(config))
    }
}
