package io.zer0.muse.proactive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.proactive.BacklogDispatchState
import io.zer0.muse.data.proactive.ProactiveBacklogDispatchEntity
import io.zer0.muse.data.proactive.ProactiveBacklogRepository
import io.zer0.muse.data.proactive.ProactiveNotificationState
import io.zer0.muse.data.proactive.ProactiveOutcomeEntity
import io.zer0.muse.data.proactive.ProactiveOutcomeRepository
import io.zer0.muse.data.proactive.ProactiveRelationRepository
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationIdPolicy
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.schedule.ChatGenerationManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProactiveRestartRecoveryTest {
    private lateinit var context: Context
    private lateinit var db: MuseDb

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        db = openDb()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun claimedLeaseThrottleAndNotificationOutboxSurviveDatabaseReopen() = runTest {
        val now = 1_000_000L
        val claimUntil = now + ProactiveBacklogRepository.CLAIM_LEASE_MS
        val nextAt = now + ProactiveBacklogRepository.DISPATCH_GAP_MS
        val outcomeId = "stable-outcome"
        val notificationId = MuseNotificationIdPolicy.notificationIdForOutcome(outcomeId)
        db.proactiveBacklogDao().insertIfAbsent(
            ProactiveBacklogDispatchEntity(
                dispatchId = "dispatch-1", thoughtId = "thought-1", assistantId = "assistant-1",
                sessionId = "session-1", state = BacklogDispatchState.CLAIMED,
                claimToken = "old-process-token", claimUntil = claimUntil, createdAt = now, updatedAt = now,
            ),
        )
        db.proactiveBacklogScheduleDao().deferUntil(nextAt, now)
        db.proactiveOutcomeDao().insertIfAbsent(
            ProactiveOutcomeEntity(
                id = outcomeId, assistantId = "assistant-1", sessionId = "session-1", sentAt = now,
                messageId = "message-1", notificationState = ProactiveNotificationState.PENDING,
                notificationId = notificationId, notificationTitle = "Muse", notificationPreview = "hello",
            ),
        )
        db.close()
        db = openDb()

        val restored = db.proactiveBacklogDao().getById("dispatch-1") ?: error("claim missing after reopen")
        assertEquals("old-process-token", restored.claimToken)
        assertEquals(nextAt, db.proactiveBacklogScheduleDao().getNextBacklogDispatchAt())
        assertEquals(notificationId, db.proactiveOutcomeDao().getById(outcomeId)?.notificationId)
        assertEquals(ProactiveNotificationState.PENDING, db.proactiveOutcomeDao().getById(outcomeId)?.notificationState)

        val repository = ProactiveBacklogRepository(
            db.pendingThoughtDao(), db.proactiveBacklogDao(), db.proactiveBacklogScheduleDao(),
        )
        assertEquals(1, repository.releaseExpiredClaims(claimUntil))
        assertEquals(claimUntil + ProactiveBacklogRepository.DISPATCH_GAP_MS, repository.nextWorkAt(claimUntil))

        val outcomes = ProactiveOutcomeRepository(
            db.proactiveOutcomeDao(), ProactiveRelationRepository(db.proactiveRelationDao()),
        )
        val notifier = mockk<MuseNotificationManager>()
        every { notifier.proactiveNotificationBlocker() } returns null
        coEvery {
            notifier.notifyProactiveMessage(
                assistantName = "Muse",
                preview = "hello",
                target = any(),
                outcomeId = outcomeId,
                notificationId = notificationId,
            )
        } returns true
        val runtime = ProactiveRuntime(
            settings = mockk<SettingsRepository>(),
            sessions = mockk<SessionRepository>(),
            assistants = mockk<AssistantRepository>(),
            notifier = notifier,
            generations = mockk<ChatGenerationManager>(),
            proactiveSessionPreferences = mockk(),
        )
        val recovery = ProactiveNotificationRecovery(outcomes, runtime)
        assertEquals(1, recovery.recover(now + 1L))
        assertEquals(ProactiveNotificationState.POSTED, db.proactiveOutcomeDao().getById(outcomeId)?.notificationState)
        assertTrue("recovery retains the same system notification key", db.proactiveOutcomeDao().getById(outcomeId)?.notificationId == notificationId)
    }

    private fun openDb(): MuseDb = Room.databaseBuilder(context, MuseDb::class.java, DB_NAME)
        .allowMainThreadQueries()
        .build()

    private companion object {
        const val DB_NAME = "proactive-restart-recovery-test.db"
    }
}
