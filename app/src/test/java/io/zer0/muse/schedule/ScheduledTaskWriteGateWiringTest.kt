package io.zer0.muse.schedule

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ScheduledTaskWriteGateWiringTest {

    @Test
    fun `scheduled task rechecks restore gate before action and durable writes`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/schedule/ScheduledTaskRunner.kt"),
                Path.of("app/src/main/java/io/zer0/muse/schedule/ScheduledTaskRunner.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("ScheduledTaskRunner.kt not found")

        val executeBody = source.substringAfter("suspend fun executeTask")
            .substringBefore("private suspend fun evaluateCondition")
        val pendingBody = source.substringAfter("private suspend fun deliverPendingMessage")
            .substringBefore("private fun createNotificationChannel")

        assertTrue(executeBody.contains("ProcessWriteGate.restoring"))
        assertTrue(executeBody.contains("restoreBlocksScheduledWrites"))
        assertTrue(pendingBody.contains("ProcessWriteGate.restoring"))
    }
}
