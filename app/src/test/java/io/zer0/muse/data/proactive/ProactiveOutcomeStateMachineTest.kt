package io.zer0.muse.data.proactive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProactiveOutcomeStateMachineTest {
    private lateinit var db: MuseDb
    private lateinit var repository: ProactiveOutcomeRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .setQueryExecutor(java.util.concurrent.Executor { it.run() })
            .setTransactionExecutor(java.util.concurrent.Executor { it.run() })
            .allowMainThreadQueries()
            .build()
        repository = ProactiveOutcomeRepository(
            db.proactiveOutcomeDao(),
            ProactiveRelationRepository(db.proactiveRelationDao()),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun transitionsAreOneWayAndOnlyTheCasWinnerAppliesFeedback() = runTest {
        db.proactiveOutcomeDao().upsert(
            ProactiveOutcomeEntity(id = "o1", assistantId = "a1", sessionId = "s1", sentAt = 1L),
        )
        val before = ProactiveRelationRepository(db.proactiveRelationDao()).getOrCreate("a1", 10L).intimacy

        assertEquals(1, repository.resolve("o1", OutcomeState.OPENED, 20L))
        val openedIntimacy = db.proactiveRelationDao().get("a1")?.intimacy ?: error("relation missing")
        assertNotEquals(before, openedIntimacy)
        assertEquals(0, repository.resolve("o1", OutcomeState.DISMISSED, 30L))
        assertEquals(0, repository.resolve("o1", OutcomeState.OPENED, 30L))
        assertEquals(openedIntimacy, db.proactiveRelationDao().get("a1")?.intimacy)

        assertEquals(1, repository.resolve("o1", OutcomeState.REPLIED, 40L))
        val repliedIntimacy = db.proactiveRelationDao().get("a1")?.intimacy
        assertEquals(0, repository.resolve("o1", OutcomeState.DISMISSED, 50L))
        assertEquals(0, repository.resolve("o1", OutcomeState.REPLIED, 50L))
        assertEquals(repliedIntimacy, db.proactiveRelationDao().get("a1")?.intimacy)
        assertEquals(OutcomeState.REPLIED.name, db.proactiveOutcomeDao().getById("o1")?.state)
    }

    @Test
    fun terminalOutcomesCannotBeReopened() = runTest {
        db.proactiveOutcomeDao().upsert(
            ProactiveOutcomeEntity(id = "o2", assistantId = "a1", state = OutcomeState.DISMISSED.name),
        )
        assertEquals(0, repository.resolve("o2", OutcomeState.OPENED, 10L))
        assertEquals(0, repository.resolve("o2", OutcomeState.REPLIED, 10L))
        assertEquals(OutcomeState.DISMISSED.name, db.proactiveOutcomeDao().getById("o2")?.state)
    }

    @Test
    fun concurrentOutcomeTransitionsHaveOneCasWinnerAndOneFeedback() = runTest {
        db.proactiveOutcomeDao().upsert(
            ProactiveOutcomeEntity(id = "o3", assistantId = "a1", state = OutcomeState.PENDING.name),
        )
        val opened = async(Dispatchers.IO) { repository.resolve("o3", OutcomeState.OPENED, 10L) }
        val dismissed = async(Dispatchers.IO) { repository.resolve("o3", OutcomeState.DISMISSED, 10L) }
        val affected = opened.await() + dismissed.await()

        assertEquals(1, affected)
        val finalState = db.proactiveOutcomeDao().getById("o3")?.state ?: error("outcome missing")
        assertTrue(finalState == OutcomeState.OPENED.name || finalState == OutcomeState.DISMISSED.name)
        val intimacy = db.proactiveRelationDao().get("a1")?.intimacy ?: error("feedback relation missing")
        val openedDelta = ProactiveLearning.applyOutcome(0.15f, OutcomeState.OPENED)
        val dismissedDelta = ProactiveLearning.applyOutcome(0.15f, OutcomeState.DISMISSED)
        assertTrue(intimacy == openedDelta || intimacy == dismissedDelta)
    }

    @Test
    fun normalLaneOutcomeInsertIsIdempotentForAStableMessageKey() = runTest {
        val first = repository.recordSentDetailed(
            thought = null,
            sessionId = "s1",
            now = 10L,
            assistantId = "a1",
            messageId = "message-1",
            outcomeId = "outcome-for-message-1",
            notificationTitle = "Muse",
            notificationPreview = "hello",
        )
        val retry = repository.recordSentDetailed(
            thought = null,
            sessionId = "s1",
            now = 11L,
            assistantId = "a1",
            messageId = "message-1",
            outcomeId = "outcome-for-message-1",
            notificationTitle = "Muse",
            notificationPreview = "hello",
        )

        assertTrue(first.inserted)
        assertEquals(first.outcomeId, retry.outcomeId)
        assertEquals(false, retry.inserted)
        assertEquals(1, db.proactiveOutcomeDao().getAll().size)
        assertEquals(10L, db.proactiveOutcomeDao().getById(first.outcomeId)?.sentAt)
        assertEquals(ProactiveNotificationState.PENDING, db.proactiveOutcomeDao().getById(first.outcomeId)?.notificationState)
    }
}
