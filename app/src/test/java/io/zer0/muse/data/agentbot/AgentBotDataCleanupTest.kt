package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.SessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentBotDataCleanupTest {
    private lateinit var graph: AgentBotFlowTestGraph

    @Before
    fun setUp() {
        graph = AgentBotFlowTestGraph(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() {
        graph.close()
    }

    @Test
    fun deletingSessionRemovesItsAgentBotRowsInTheSessionTransaction() = runTest {
        seedAgentBotRows()

        graph.sessions.deleteSession(SESSION)

        assertEquals(emptyList<BotTaskEntity>(), graph.db.botTaskDao().getAll())
        assertEquals(emptyList<TaskRunEntity>(), graph.db.taskRunDao().getAll())
        assertEquals(emptyList<TaskStepEntity>(), graph.db.taskStepDao().getAll())
        assertEquals(emptyList<BotEventEntity>(), graph.db.botEventDao().getAll())
        assertEquals(emptyList<ApprovalRequestEntity>(), graph.db.approvalRequestDao().getAll())
        assertEquals(emptyList<PublicationOutboxEntity>(), graph.db.publicationOutboxDao().getAll())
        assertEquals(emptyList<BotAnnotationEntity>(), graph.db.botAnnotationDao().getAll())
        assertEquals(emptyList<BotBindingEntity>(), graph.db.botBindingDao().getAll())
    }

    @Test
    fun deletingAssistantRemovesItsAgentBotRowsAndKeepsTheSession() = runTest {
        seedAgentBotRows()

        AgentBotDataCleanup(graph.db).deleteForAssistant(ASSISTANT)

        assertEquals(emptyList<BotTaskEntity>(), graph.db.botTaskDao().getAll())
        assertEquals(emptyList<TaskRunEntity>(), graph.db.taskRunDao().getAll())
        assertEquals(emptyList<TaskStepEntity>(), graph.db.taskStepDao().getAll())
        assertEquals(emptyList<BotEventEntity>(), graph.db.botEventDao().getAll())
        assertEquals(emptyList<ApprovalRequestEntity>(), graph.db.approvalRequestDao().getAll())
        assertEquals(emptyList<PublicationOutboxEntity>(), graph.db.publicationOutboxDao().getAll())
        assertEquals(emptyList<BotAnnotationEntity>(), graph.db.botAnnotationDao().getAll())
        assertEquals(emptyList<BotBindingEntity>(), graph.db.botBindingDao().getAll())
        assertEquals(SESSION, graph.db.sessionDao().getById(SESSION)?.id)
    }

    private suspend fun seedAgentBotRows() {
        graph.db.sessionDao().insert(
            SessionEntity(
                id = SESSION,
                title = "Agent test",
                createdAt = 1L,
                updatedAt = 1L,
                assistantId = ASSISTANT,
            ),
        )
        graph.db.botBindingDao().upsert(
            BotBindingEntity(botId = BOT, assistantId = ASSISTANT, canonicalAgentSessionId = SESSION),
        )
        graph.db.botTaskDao().upsert(
            BotTaskEntity(taskId = TASK, botId = BOT, originSessionId = SESSION),
        )
        graph.db.taskRunDao().upsert(TaskRunEntity(runId = RUN, taskId = TASK))
        graph.db.taskStepDao().upsert(TaskStepEntity(stepId = STEP, runId = RUN))
        graph.db.botEventDao().upsert(
            BotEventEntity(
                eventId = EVENT,
                botId = BOT,
                originSessionId = SESSION,
                taskId = TASK,
                runId = RUN,
                dedupeKey = "dedupe-$EVENT",
            ),
        )
        graph.db.approvalRequestDao().upsert(
            ApprovalRequestEntity(
                approvalId = APPROVAL,
                botId = BOT,
                originSessionId = SESSION,
                taskId = TASK,
                runId = RUN,
            ),
        )
        graph.db.publicationOutboxDao().upsert(
            PublicationOutboxEntity(
                id = PUBLICATION,
                publicationId = PUBLICATION,
                botId = BOT,
                originSessionId = SESSION,
            ),
        )
        graph.db.botAnnotationDao().upsert(
            BotAnnotationEntity(id = ANNOTATION, botId = BOT, originSessionId = SESSION),
        )
    }

    private companion object {
        const val ASSISTANT = "assistant-cleanup"
        const val SESSION = "session-cleanup"
        const val BOT = "bot-cleanup"
        const val TASK = "task-cleanup"
        const val RUN = "run-cleanup"
        const val STEP = "step-cleanup"
        const val EVENT = "event-cleanup"
        const val APPROVAL = "approval-cleanup"
        const val PUBLICATION = "publication-cleanup"
        const val ANNOTATION = "annotation-cleanup"
    }
}
