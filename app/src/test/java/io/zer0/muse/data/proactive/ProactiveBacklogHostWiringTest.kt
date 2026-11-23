package io.zer0.muse.data.proactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProactiveBacklogHostWiringTest {

    @Test
    fun `backlog runtime is registered and recovered without changing normal lane cadence`() {
        val appModule = source("src/main/java/io/zer0/muse/AppKoinModule.kt")
        val app = source("src/main/java/io/zer0/muse/MuseApp.kt")
        val cycle = source("src/main/java/io/zer0/muse/proactive/ProactiveCycleRunner.kt")
        val timing = source("src/main/java/io/zer0/muse/data/proactive/ProactiveTimingEngine.kt")

        assertTrue(appModule.contains("ProactiveBacklogRepository(get(), get(), get())"))
        assertTrue(appModule.contains("ProactiveBacklogPublisher("))
        assertTrue(appModule.contains("ProactiveBacklogDispatcher("))
        assertTrue(appModule.contains("ProactiveBacklogWorkScheduler("))
        assertTrue(app.contains("startProactiveBacklogRecovery()"))
        assertTrue(app.contains("proactiveBacklogRepository.releaseExpiredClaims(now)"))
        assertTrue(cycle.contains("backlog.reconcile(now, assistant.id, sessionId.orEmpty())"))
        assertFalse("normal timing must not use the backlog dispatch gap", timing.contains("330_000L"))
    }

    @Test
    fun `opening or sending in chat does not directly invoke backlog dispatch`() {
        val controller = source("src/main/java/io/zer0/muse/ui/chat/ChatSessionController.kt")
        val chat = source("src/main/java/io/zer0/muse/ui/chat/ChatScreen.kt")

        assertFalse(controller.contains("ProactiveBacklogDispatcher"))
        assertFalse(controller.contains("dispatchOne("))
        assertFalse(chat.contains("ProactiveBacklogDispatcher"))
        assertFalse(chat.contains("dispatchOne("))
    }

    @Test
    fun `dispatcher uses a unique keep work item with a persistent minimum gap`() {
        val scheduler = source("src/main/java/io/zer0/muse/proactive/ProactiveBacklogWorkScheduler.kt")
        val dispatcher = source("src/main/java/io/zer0/muse/proactive/ProactiveBacklogDispatcher.kt")

        assertTrue(scheduler.contains("muse_proactive_backlog_dispatch"))
        assertTrue(scheduler.contains("ExistingWorkPolicy.KEEP"))
        assertTrue(dispatcher.contains("DISPATCH_GAP_MS"))
        assertTrue(dispatcher.contains("nextBacklogDispatchAt"))
    }

    private fun source(path: String): String = File(path).readText()
}
