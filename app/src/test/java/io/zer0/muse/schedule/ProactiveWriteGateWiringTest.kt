package io.zer0.muse.schedule

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ProactiveWriteGateWiringTest {

    @Test
    fun `proactive cycle checks restore gate before refreshing schedules`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/schedule/ProactiveMessageRunner.kt"),
                Path.of("app/src/main/java/io/zer0/muse/schedule/ProactiveMessageRunner.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("ProactiveMessageRunner.kt not found")

        val cycleBody = source.substringAfter("private suspend fun executeProactiveCycle")
            .substringBefore("private suspend fun checkHasNewMemories")
        assertTrue(cycleBody.contains("ProcessWriteGate.restoring"))
        assertTrue(cycleBody.contains("restoreBlocksProactiveWrites"))
        assertTrue(
            cycleBody.windowed("restoreBlocksProactiveWrites".length)
                .count { it == "restoreBlocksProactiveWrites" } >= 3,
        )
        assertTrue(
            source.substringAfter("private suspend fun saveProactiveSchedule")
                .substringBefore("private suspend fun writePatrolLog")
                .contains("ProcessWriteGate.restoring"),
        )
    }
}
