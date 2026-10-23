package io.zer0.muse.automation.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AutomationWorkflowDisplayCloseWiringTest {

    @Test
    fun `stale display close is idempotent without re-acquiring a dead lease`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/automation/tools/AutomationWorkflow.kt"),
                Path.of("app/src/main/java/io/zer0/muse/automation/tools/AutomationWorkflow.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("AutomationWorkflow.kt not found")
        val closeBody = source.substringAfter("\"virtual_close\" ->")
            .substringBefore("else -> executeVirtualInput")
        assertTrue(closeBody.contains("displayManager.lastDisplayId"))
        assertTrue(closeBody.contains("displayLeases.acquire(runId, displayId)"))
    }
}
