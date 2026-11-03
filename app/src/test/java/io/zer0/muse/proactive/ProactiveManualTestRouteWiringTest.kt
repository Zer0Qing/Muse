package io.zer0.muse.proactive

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ProactiveManualTestRouteWiringTest {
    @Test
    fun `cycle runner uses the agent-only route for manual tests`() {
        val source = readProactive("ProactiveCycleRunner.kt")

        assertTrue(source.contains("trigger == ProactiveScheduler.TRIGGER_MANUAL"))
        assertTrue(source.contains("loader.resolveManualTestRoute(assistant)"))
    }

    private fun readProactive(relative: String): String {
        val candidates =
            listOf(
                Path.of("src/main/java/io/zer0/muse/proactive/$relative"),
                Path.of("app/src/main/java/io/zer0/muse/proactive/$relative"),
            )
        val path =
            candidates.firstOrNull(Files::exists)
                ?: error("Unable to locate $relative")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
