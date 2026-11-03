package io.zer0.muse.automation.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AutomationSettingsAutonomousWiringTest {
    @Test
    fun `automation page exposes persisted plan task confirmation and safe action controls`() {
        val source = readAutomationPage()

        assertTrue(source.contains("AutonomousAccessibilitySettingsCard"))
        assertTrue(source.contains("autonomousAccessibilityTaskPreview"))
        assertTrue(source.contains("saveAutonomousAccessibilityWindow"))
        assertTrue(source.contains("clearAutonomousAccessibilityGrant"))
        assertTrue(source.contains("settings_agent_autonomous") || source.contains("automation_autonomous"))
    }

    private fun readAutomationPage(): String {
        val candidates =
            listOf(
                Path.of("src/main/java/io/zer0/muse/automation/ui/AutomationSettingsPage.kt"),
                Path.of("app/src/main/java/io/zer0/muse/automation/ui/AutomationSettingsPage.kt"),
            )
        val path =
            candidates.firstOrNull(Files::exists)
                ?: error("AutomationSettingsPage.kt not found from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
