package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentInputPersistenceTest {

    @Test
    fun `an event write failure rolls back the edit and the child message`() = runTest {
        val graph = AgentBotFlowTestGraph(ApplicationProvider.getApplicationContext<Context>())
        try {
            val session = graph.sessions.createSession()
            val facade = AgentConversationFacade(graph.repository, mergeWindowMs = 0)
            val original = requireNotNull(facade.acceptUserInput("bot", session, "Original", sessions = graph.sessions))
            val reply = UIMessage(role = MessageRole.ASSISTANT, content = "Original reply", parentMessageId = original.messageId)
            graph.sessions.appendMessage(session, reply)
            val updatedId = Uuid.random().toString()
            graph.db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_agent_input BEFORE INSERT ON bot_events BEGIN SELECT RAISE(ABORT, 'test event failure'); END",
            )

            val failure = runCatching {
                facade.acceptUserInput("bot", session, "Edited", updatedId, graph.sessions, replacedMessageId = original.messageId)
            }

            assertTrue(failure.isFailure || failure.getOrNull() == null)
            assertEquals("Original", graph.db.messageDao().getById(session, original.messageId)?.content)
            assertEquals("Original reply", graph.db.messageDao().getById(session, reply.id.toString())?.content)
            assertNull(graph.db.messageDao().getById(session, updatedId))
            assertEquals(original.eventId, graph.repository.pendingEvents("bot").single().eventId)

            graph.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_agent_input")
            val updated = facade.acceptUserInput(
                "bot",
                session,
                "Edited",
                updatedId,
                graph.sessions,
                replacedMessageId = original.messageId,
            )
            assertNotNull(updated)
            assertNull(graph.db.messageDao().getById(session, original.messageId))
            assertNull(graph.db.messageDao().getById(session, reply.id.toString()))
            assertEquals("Edited", graph.db.messageDao().getById(session, updatedId)?.content)
            assertEquals(updated?.eventId, graph.repository.pendingEvents("bot").last().eventId)
        } finally {
            graph.close()
        }
    }

    @Test
    fun `a message write failure leaves the original and never records the input event`() = runTest {
        val graph = AgentBotFlowTestGraph(ApplicationProvider.getApplicationContext<Context>())
        try {
            val session = graph.sessions.createSession()
            val facade = AgentConversationFacade(graph.repository, mergeWindowMs = 0)
            val original = requireNotNull(facade.acceptUserInput("bot", session, "Original", sessions = graph.sessions))
            graph.db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_replacement BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT, 'test message failure'); END",
            )

            val failure = runCatching {
                facade.acceptUserInput("bot", session, "Edited", sessions = graph.sessions, replacedMessageId = original.messageId)
            }

            assertTrue(failure.isFailure || failure.getOrNull() == null)
            assertEquals("Original", graph.db.messageDao().getById(session, original.messageId)?.content)
            assertEquals(original.eventId, graph.repository.pendingEvents("bot").single().eventId)
        } finally {
            graph.close()
        }
    }
}
