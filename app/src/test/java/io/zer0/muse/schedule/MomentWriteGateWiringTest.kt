package io.zer0.muse.schedule

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class MomentWriteGateWiringTest {

    @Test
    fun `moment scheduler gates worker and manual generation during restore`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/schedule/MomentScheduler.kt"),
                Path.of("app/src/main/java/io/zer0/muse/schedule/MomentScheduler.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("MomentScheduler.kt not found")

        assertTrue(source.contains("ProcessWriteGate.restoring"))
        val generateBody = source.substringAfter("suspend fun generateNow")
            .substringBefore("private suspend fun checkAndGenerate")
        assertTrue(generateBody.contains("restoreBlocksMomentWrites"))
        val scheduledBody = source.substringAfter("private suspend fun checkAndGenerate")
            .substringBefore("private suspend fun pickAssistant")
        assertTrue(scheduledBody.contains("restoreBlocksMomentWrites"))
    }
}
