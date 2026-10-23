package io.zer0.muse.backup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class BackupCloudWriteGateWiringTest {

    @Test
    fun `cloud imports enter the process write gate before applying data`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/backup/BackupService.kt"),
                Path.of("app/src/main/java/io/zer0/muse/backup/BackupService.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("BackupService.kt not found")

        val latestBlock = source.substringAfter("suspend fun importFromCloud(): Pair<Int, Int>?")
            .substringBefore("suspend fun importFromCloudFile")
        val archiveBlock = source.substringAfter("suspend fun importFromCloudFile")
            .substringBefore("suspend fun restoreFromAutoBackup")

        assertTrue(latestBlock.contains("withBackupImportWriteGate"))
        assertTrue(archiveBlock.contains("withBackupImportWriteGate"))
    }
}
