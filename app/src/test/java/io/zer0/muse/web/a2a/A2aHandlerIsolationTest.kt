package io.zer0.muse.web.a2a

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A2A task/session isolation integration tests. */
class A2aHandlerIsolationTest {

    @Test
    fun `handler has no ChatViewModel dependency or UI input mutation path`() {
        val source = File("src/main/java/io/zer0/muse/web/a2a/A2aHandler.kt").readText()
        assertFalse("A2A must not drive the application-wide ViewModel", source.contains("ChatViewModel"))
        assertFalse("A2A must not mutate the focused chat input", source.contains("updateInput("))
        assertFalse("A2A must not invoke the UI send() path", source.contains("chatViewModel.send()"))
        assertTrue("A2A must use the isolated headless generator", source.contains("headlessGenerator.complete"))
    }

    private fun handler(
        chatService: ChatService,
        sessionRepository: SessionRepository,
        assistantRepository: AssistantRepository = mockk(relaxed = true),
    ) = A2aHandler(chatService, sessionRepository, assistantRepository, "test")

    @Test
    fun `a2a uses an agent-only session and never accepts arbitrary muse session ids`() = runBlocking {
        val chatService = mockk<ChatService>()
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        coEvery { sessionRepository.createAgentSession("default") } returns "a2a-owned"
        coEvery { sessionRepository.getSessionById("a2a-owned") } returns
            SessionEntity(id = "a2a-owned", title = "Agent", createdAt = 1L, updatedAt = 1L, isAgentSession = true)
        every { sessionRepository.observeMessages("a2a-owned") } returns flowOf(emptyList())
        coEvery { chatService.completeText(messages = any(), temperature = null, maxTokens = null, tools = null) } returns
            ChatCompletion(text = "isolated answer")

        val assistantRepository = mockk<AssistantRepository>(relaxed = true)
        coEvery { assistantRepository.getById("default") } returns null
        val a2a = handler(chatService, sessionRepository, assistantRepository)
        val task = a2a.sendMessage(
            MessageSendParams(
                message = A2aMessage(contextId = "ctx-1", parts = listOf(A2aPart(text = "hello"))),
                taskId = "task-1",
            ),
        )
        val rejected = a2a.sendMessage(
            MessageSendParams(
                message = A2aMessage(sessionId = "users-real-session", parts = listOf(A2aPart(text = "do not touch"))),
                taskId = "task-2",
            ),
        )

        assertEquals("A2A failure: ${task.error}", A2aTaskState.COMPLETED, task.state)
        assertEquals("ctx-1", task.contextId)
        assertEquals(A2aTaskState.FAILED, rejected.state)
        assertTrue(rejected.error.orEmpty().contains("unknown A2A session"))
        coVerify(exactly = 1) { sessionRepository.createAgentSession("default") }
        coVerify(exactly = 1) { chatService.completeText(messages = any(), temperature = null, maxTokens = null, tools = null) }
    }

    @Test
    fun `oversized A2A prompt is rejected before session creation or model call`() = runBlocking {
        val chatService = mockk<ChatService>(relaxed = true)
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        val assistantRepository = mockk<AssistantRepository>(relaxed = true)
        val task = handler(chatService, sessionRepository, assistantRepository).sendMessage(
            MessageSendParams(
                message = A2aMessage(parts = listOf(A2aPart(text = "x".repeat(A2aHandler.MAX_MESSAGE_CHARS + 1)))),
                taskId = "oversized",
            ),
        )

        assertEquals(A2aTaskState.FAILED, task.state)
        assertTrue(task.error.orEmpty().contains("message too long"))
        coVerify(exactly = 0) { sessionRepository.createAgentSession(any()) }
        coVerify(exactly = 0) { chatService.completeText(messages = any(), tools = any()) }
    }

    @Test
    fun `same context id reuses the same isolated session across messages`() = runBlocking {
        val chatService = mockk<ChatService>()
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        coEvery { sessionRepository.createAgentSession("default") } returns "a2a-owned"
        coEvery { sessionRepository.getSessionById("a2a-owned") } returns
            SessionEntity(id = "a2a-owned", title = "Agent", createdAt = 1L, updatedAt = 1L, isAgentSession = true)
        every { sessionRepository.observeMessages("a2a-owned") } returns flowOf(emptyList())
        coEvery { chatService.completeText(messages = any(), temperature = null, maxTokens = null, tools = null) } returns
            ChatCompletion(text = "reply")
        val assistantRepository = mockk<AssistantRepository>(relaxed = true)
        coEvery { assistantRepository.getById("default") } returns null

        val a2a = handler(chatService, sessionRepository, assistantRepository)
        val first = a2a.sendMessage(
            MessageSendParams(message = A2aMessage(parts = listOf(A2aPart(text = "one"))), taskId = "t1"),
        )
        val contextId = requireNotNull(first.contextId)
        val second = a2a.sendMessage(
            MessageSendParams(message = A2aMessage(contextId = contextId, parts = listOf(A2aPart(text = "two"))), taskId = "t2"),
        )

        assertTrue(contextId.startsWith("ctx-"))
        assertEquals(contextId, second.contextId)
        coVerify(exactly = 1) { sessionRepository.createAgentSession("default") }
        coVerify(exactly = 2) { chatService.completeText(messages = any(), temperature = null, maxTokens = null, tools = null) }
    }

    @Test
    fun `cancel only cancels a registered working a2a task`() = runBlocking {
        val chatService = mockk<ChatService>()
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        val providerStarted = CompletableDeferred<Unit>()
        coEvery { sessionRepository.createAgentSession("default") } returns "a2a-owned"
        coEvery { sessionRepository.getSessionById("a2a-owned") } returns
            SessionEntity(id = "a2a-owned", title = "Agent", createdAt = 1L, updatedAt = 1L, isAgentSession = true)
        every { sessionRepository.observeMessages("a2a-owned") } returns flowOf(emptyList())
        coEvery { chatService.completeText(messages = any(), temperature = null, maxTokens = null, tools = null) } coAnswers {
            providerStarted.complete(Unit)
            awaitCancellation()
        }
        val assistantRepository = mockk<AssistantRepository>(relaxed = true)
        coEvery { assistantRepository.getById("default") } returns null

        val a2a = handler(chatService, sessionRepository, assistantRepository)
        val pending = async {
            runCatching {
                a2a.sendMessage(
                    MessageSendParams(
                        message = A2aMessage(
                            contextId = "ctx-cancel",
                            parts = listOf(A2aPart(text = "wait")),
                        ),
                        taskId = "t-cancel",
                    ),
                )
            }.getOrNull()
        }
        withTimeout(2_000L) {
            providerStarted.await()
            while (a2a.getTask("t-cancel")?.state != A2aTaskState.WORKING) delay(10L)
        }

        val canceled = a2a.cancelTask("t-cancel")
        val unrelated = a2a.cancelTask("not-owned")
        pending.join()

        assertEquals(A2aTaskState.CANCELED, canceled?.state)
        assertEquals("未知 taskId 不应被记录或可查询", null, unrelated)
        assertEquals(null, a2a.getTask("not-owned"))
        assertTrue("CancelTask 必须取消自己的请求协程", pending.isCancelled)
        assertFalse(a2a.getTask("t-cancel")?.state == A2aTaskState.WORKING)
    }
}
