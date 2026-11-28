package io.zer0.muse.proactive

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.data.ProactiveMessageConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.proactive.ProactiveBacklogPublisher
import io.zer0.muse.data.proactive.ProactiveBacklogRepository
import io.zer0.muse.data.proactive.ProactiveOutcomeRepository
import io.zer0.muse.data.proactive.ProactiveRelationRepository
import io.zer0.muse.data.proactive.ProactiveThoughtRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.schedule.ChatGenerationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProactiveBacklogDispatcherTest {
    @Test
    fun persistedThrottleBlocksAt329999AndAllowsAClaimAt330000() = runTest {
        val repository = mockk<ProactiveBacklogRepository>()
        val settings = mockk<SettingsRepository>()
        val runtime = ProactiveRuntime(
            settings = settings,
            sessions = mockk<SessionRepository>(),
            assistants = mockk<AssistantRepository>(),
            notifier = mockk<MuseNotificationManager>(),
            generations = mockk<ChatGenerationManager>(),
            proactiveSessionPreferences = mockk(),
        )
        val dispatcher = ProactiveBacklogDispatcher(
            backlog = repository,
            stores = ProactiveStores(
                thoughts = mockk<ProactiveThoughtRepository>(),
                relations = mockk<ProactiveRelationRepository>(),
                outcomes = mockk<ProactiveOutcomeRepository>(),
            ),
            runtime = runtime,
            loader = mockk(),
            composer = mockk(),
            publisher = mockk<ProactiveBacklogPublisher>(),
        )
        every { settings.scheduleWorkEnabledFlow } returns flowOf(true)
        every { settings.proactiveMessageConfigFlow } returns flowOf(ProactiveMessageConfig(enabled = true))
        coEvery { repository.nextBacklogDispatchAt() } returns 330_000L
        coEvery { repository.claimOne(330_000L) } returns null

        assertEquals(BacklogDispatchOutcome.NotDue, dispatcher.dispatchOne(329_999L))
        coVerify(exactly = 0) { repository.claimOne(any()) }

        assertEquals(BacklogDispatchOutcome.Empty, dispatcher.dispatchOne(330_000L))
        coVerify(exactly = 1) { repository.claimOne(330_000L) }
    }

    @Test
    fun workDelayUsesThePersistedAllowedTime() {
        assertEquals(330_000L, ProactiveBacklogWorkScheduler.initialDelayMs(10L, 330_010L))
        assertEquals(0L, ProactiveBacklogWorkScheduler.initialDelayMs(20L, 10L))
    }

    @Test
    fun openingSessionOrHandlingMessageFeedbackDoesNotRunAProactiveCycle() = runTest {
        val outcomes = mockk<ProactiveOutcomeRepository>()
        val cycle = mockk<ProactiveCycleRunner>(relaxed = true)
        val stores = ProactiveStores(
            thoughts = mockk<ProactiveThoughtRepository>(),
            relations = mockk<ProactiveRelationRepository>(),
            outcomes = outcomes,
        )
        coEvery { outcomes.findPendingInSession(any()) } returns null
        coEvery { outcomes.resolve(any(), any(), any()) } returns 0
        val scheduler = ProactiveScheduler(
            stores = stores,
            cycle = cycle,
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        scheduler.onSessionOpened("session-1")
        scheduler.onProactiveOpened("outcome-1")
        scheduler.onUserSpoke("session-1")

        coVerify(exactly = 0) { cycle.execute(any(), any()) }
    }
}
