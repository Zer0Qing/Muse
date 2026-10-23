package io.zer0.muse.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression coverage for the single-session export entry point.
 *
 * Full backup export remains available; this guard verifies the settings page
 * also wires a session-scoped export that can load complete history.
 */
class BackupSessionExportWiringTest {

    @Test
    fun `backup settings exposes single session export using complete history`() {
        val backupSource = locate("src/main/java/io/zer0/muse/ui/settings/BackupSection.kt")
        val pageSource = locate("src/main/java/io/zer0/muse/ui/settings/SettingsSubPages.kt")

        assertTrue(backupSource.contains("settings_backup_export_session"))
        assertTrue(backupSource.contains("getAllMessagesForBackfill"))
        assertTrue(pageSource.contains("sessionRepository = sessionRepository"))
        assertTrue(backupSource.contains("sessionExportQuery"))
        assertTrue(backupSource.contains("filteredExportSessions"))
        assertTrue(!backupSource.contains("exportableSessions.take(50)"))
        assertTrue("导出会话列表必须占满弹窗内容宽度，避免标题被测成零宽只显示省略号", backupSource.contains(".fillMaxWidth()"))
        assertTrue("导出会话列表仍应保留高度上限", backupSource.contains(".heightIn(max = 420.dp)"))
        assertTrue("导出会话标题至少显示两行，长标题仍可区分", backupSource.contains("titleMaxLines = 2"))

        val itemRowSource = locate("src/main/java/io/zer0/muse/ui/common/settings/SettingsItemRow.kt")
        assertTrue("SettingsItemRow 应允许导出列表放宽标题行数", itemRowSource.contains("titleMaxLines: Int = 1"))
    }

    private fun locate(relativePath: String): String {
        val candidates =
            listOf(
                Path.of(relativePath),
                Path.of("app").resolve(relativePath.removePrefix("src/")),
            )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
