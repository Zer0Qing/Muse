package io.zer0.muse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IntegrationWiringContractTest {

    @Test
    fun `theme host consumes decisions without changing the base mode`() {
        val activity = source("src/main/java/io/zer0/muse/MainActivity.kt")
        val quickCapture = source("src/main/java/io/zer0/muse/ui/quicknotes/QuickCaptureTheme.kt")
        val scheduler = source("src/main/java/io/zer0/muse/ui/theme/ThemeScheduler.kt")
        val appModule = source("src/main/java/io/zer0/muse/AppKoinModule.kt")
        val manifest = source("src/main/AndroidManifest.xml")

        assertTrue(activity.contains("themeScheduler.decisionFlow"))
        assertTrue(quickCapture.contains("effectiveDark = decision?.isDark"))
        assertFalse("scheduler decisions must not overwrite theme_mode", scheduler.contains("saveThemeMode("))
        assertTrue(appModule.contains("ThemeScheduler.create"))
        assertTrue(manifest.contains(".ui.theme.ThemeAlarmReceiver"))
        assertTrue(manifest.contains(".ui.theme.ThemeTimeChangeReceiver"))
    }

    @Test
    fun `runtime ready resumes only resource parked agent runs through persisted state`() {
        val scheduler = source("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt")
        val runtimeService = source("src/main/java/io/zer0/muse/runtime/RuntimeInstallService.kt")
        val toolContext = source("src/main/java/io/zer0/muse/tools/ToolExecutionContext.kt")

        assertTrue(toolContext.contains("onRuntimeMissing"))
        assertTrue(scheduler.contains("TaskRunStatus.WAITING_RESOURCE"))
        assertTrue(scheduler.contains("RuntimeMissing.CODE"))
        assertTrue(scheduler.contains("RESUME_FROM_RESOURCE"))
        assertTrue(runtimeService.contains("result.isSuccess && FeatureFlags.AGENT_BOT"))
        assertTrue(runtimeService.contains("resumeRuntimeMissingTasks()"))
    }

    @Test
    fun `agent restart reconciliation remains under its feature gate`() {
        val app = source("src/main/java/io/zer0/muse/MuseApp.kt")
        val gate = app.indexOf("if (io.zer0.muse.feature.FeatureFlags.AGENT_BOT)")
        val reconcile = app.indexOf("botTaskScheduler.reconcileAfterRestart()")

        assertTrue(gate >= 0)
        assertTrue(reconcile > gate)
    }

    @Test
    fun `session and assistant deletion clean AgentBot rows through the database`() {
        val sessionRepository = source("src/main/java/io/zer0/muse/data/session/SessionRepository.kt")
        val assistantRepository = source("src/main/java/io/zer0/muse/data/assistant/AssistantRepository.kt")
        val appModule = source("src/main/java/io/zer0/muse/AppKoinModule.kt")

        assertTrue(sessionRepository.contains("deleteSessionRowsInCurrentTransaction(id)"))
        assertTrue(assistantRepository.contains("deleteAssistantRowsInCurrentTransaction(id)"))
        assertTrue(appModule.contains("AssistantRepository(get(), androidContext(), get(), get(), get(), get())"))
    }

    private fun source(path: String): String = File(path).readText()
}
