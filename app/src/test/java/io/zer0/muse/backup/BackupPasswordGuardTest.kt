package io.zer0.muse.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-1 回归:备份密码失效守卫 [BackupService.isBackupPasswordUnavailable]。
 *
 * 覆盖"已设密码 + 密码读空(Keystore 失效)→ 必须判定不可用并拒绝导出/上传",
 * 防止备份被静默降级为明文写盘。判定与本地导出([BackupService.export]/
 * [BackupService.exportStreaming])和云上传([BackupService.exportToCloud])共用,
 * 三处行为一致。
 */
class BackupPasswordGuardTest {

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
