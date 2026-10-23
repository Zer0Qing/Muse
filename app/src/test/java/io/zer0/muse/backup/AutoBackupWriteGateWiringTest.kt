package io.zer0.muse.backup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AutoBackupWriteGateWiringTest {

    @Test
    fun `automatic backup rechecks restore gate during snapshot and before result persistence`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/data/stats/AutoBackupHelper.kt"),
                Path.of("app/src/main/java/io/zer0/muse/data/stats/AutoBackupHelper.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("AutoBackupHelper.kt not found")

        val body = source.substringAfter("suspend fun backupNow")
            .substringBefore("private fun vacuumInto")
        assertTrue(body.contains("ProcessWriteGate.restoring"))
        assertTrue(body.contains("backupBlocksRestore"))
    }
}
