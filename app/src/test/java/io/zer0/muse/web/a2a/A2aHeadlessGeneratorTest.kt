package io.zer0.muse.web.a2a

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-20261011-A2A-HEADLESS（REPORT_MASTER batch 3）：A2A 不得驱动 UI ChatViewModel。
 *
 * 这条路径是修复会话劫持/并发串台/cancel 误伤的根因：A2A 用 handler-owned Agent session +
 * ChatService.completeText，tools=null；用户前台 ChatViewModel 完全不参与。
 */
class A2aHeadlessGeneratorTest {

    @Test
    fun `headless completion includes assistant prompt and bounded session history, no tools`() = runBlocking {
        val chatService = mockk<ChatService>()
        val assistantRepository = mockk<AssistantRepository>(relaxed = true)
        val sessionRepository = mockk<SessionRepository>()
        val assistant = AssistantEntity(
            id = "default",
            name = "Muse",
            systemPrompt = "Be helpful.",
            temperature = 0.25f,
            maxTokens = 512,
        )
        val oldHistory = listOf(
            UIMessage(role = MessageRole.USER, content = "previous question"),
            UIMessage(role = MessageRole.ASSISTANT, content = "previous answer"),
        )
        every { sessionRepository.observeMessages("a2a-session") } returns flowOf(oldHistory)
        coEvery { assistantRepository.getById("default") } returns assistant
        val captured = io.mockk.slot<List<UIMessage>>()
        coEvery {
            chatService.completeText(
                messages = capture(captured),
                temperature = 0.25f,
                maxTokens = 512,
                tools = null,
            )
        } returns ChatCompletion(text = "new answer")

        val generator = A2aHeadlessGenerator(chatService, sessionRepository, assistantRepository)
        val reply = generator.complete("a2a-session", "new question")

        assertEquals("new answer", reply)
        assertEquals(MessageRole.SYSTEM, captured.captured.first().role)
        assertEquals("Be helpful.", captured.captured.first().content)
        assertTrue(captured.captured.any { it.role == MessageRole.USER && it.content == "previous question" })
        assertTrue(captured.captured.any { it.role == MessageRole.ASSISTANT && it.content == "previous answer" })
        assertEquals(MessageRole.USER, captured.captured.last().role)
        assertEquals("new question", captured.captured.last().content)
        assertFalse(captured.captured.any { it.toolCalls?.isNotEmpty() == true })
        coVerify(exactly = 1) {
            chatService.completeText(messages = any(), temperature = 0.25f, maxTokens = 512, tools = null)
        }
    }

    @Test
    fun `headless completion clamps history to configured limit`() = runBlocking {
        val chatService = mockk<ChatService>()
        val assistantRepository = mockk<AssistantRepository>(relaxed = true)
        val sessionRepository = mockk<SessionRepository>()
        val history = (1..80).map { UIMessage(role = MessageRole.USER, content = "message-$it") }
        every { sessionRepository.observeMessages("a2a-session") } returns flowOf(history)
        coEvery { assistantRepository.getById("default") } returns null
        val captured = io.mockk.slot<List<UIMessage>>()
        coEvery {
            chatService.completeText(messages = capture(captured), temperature = null, maxTokens = null, tools = null)
        } returns ChatCompletion(text = "ok")

        val reply = A2aHeadlessGenerator(chatService, sessionRepository, assistantRepository).complete("a2a-session", "new")

        assertEquals("ok", reply)
        assertEquals(41, captured.captured.size) // 40 history + current user
        assertEquals("message-41", captured.captured.first().content)
        assertEquals("new", captured.captured.last().content)
    }
}
