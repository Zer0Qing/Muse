package io.zer0.muse.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-25: 单 JSON 备份体量上限的纯函数校验。
 *
 * 覆盖 [BackupService.withinSingleJsonLimit](逐行读取累计时防 OOM)
 * 与 [BackupService.singleJsonTooLargeMessage](超限时的明确错误信息)。
 */
class BackupJsonLimitTest {

    @Test
    fun `within limit returns true`() {
        // 刚好等于上限:视为未超限
        assertTrue(
            BackupService.withinSingleJsonLimit(
                BackupService.MAX_SINGLE_JSON_BACKUP_BYTES - 1,
                1L,
            ),
        )
        assertTrue(BackupService.withinSingleJsonLimit(1024L, 100L))
    }

    @Test
    fun `over limit returns false`() {
        val cap = BackupService.MAX_SINGLE_JSON_BACKUP_BYTES
        assertFalse(BackupService.withinSingleJsonLimit(cap, 1L))
        assertFalse(BackupService.withinSingleJsonLimit(cap + 1, 0L))
        // 单行本身就超过上限时,即便此前累计为 0 也应判超限
        assertFalse(BackupService.withinSingleJsonLimit(0L, cap + 1000L))
    }

    @Test
    fun `limit message includes upper bound`() {
        val msg = BackupService.singleJsonTooLargeMessage(70L * 1024 * 1024)
        assertTrue(msg.contains("64MB"))
        assertTrue(msg.contains("已拒绝导入"))
        assertEquals("单 JSON 备份体量 70MB 超过上限 64MB,已拒绝导入", msg)
    }
}
