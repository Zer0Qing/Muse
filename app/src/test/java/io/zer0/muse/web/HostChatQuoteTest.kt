package io.zer0.muse.web

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.ui.ChatSessionState
import io.zer0.muse.ui.ChatStreamState
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.chat.buildQuotedContent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `chat.send.quotedMessageId`：引用随消息一起发出去。
 *
 * 引用真正落地走的是 App 自己的机制（`setReplyingTo` → 发送时正文按 `> …` 格式拼好），
 * 这里的假 ViewModel 照这个行为模拟，所以断言看到的是网页端将来渲染的那份数据。
 */
class HostChatQuoteTest {

    private val workDir = Files.createTempDirectory("host-chat-quote").toFile()
    private val imageDir = File(workDir, "muse_images").apply { mkdirs() }
    private val voiceDir = File(workDir, "voice_messages").apply { mkdirs() }

    private class FakeConnection {
        val incoming = Channel<Frame>(Channel.UNLIMITED)
        val events = mutableListOf<HostEvent>()
        private val mock = mockk<DefaultWebSocketServerSession>(relaxed = true)

        val session: DefaultWebSocketServerSession = mock

        init {
            every { mock.incoming } returns incoming
            coEvery { mock.send(any<Frame>()) } coAnswers {
                val frame = firstArg<Frame>()
                if (frame is Frame.Text) {
                    events += AppJson.decodeFromString<HostEvent>(frame.data.decodeToString())
                }
            }
        }
    }

    private class Harness(
        val gateway: HostWebSocketGateway,
        val chat: ChatViewModel,
        val messages: MutableStateFlow<List<UIMessage>>,
    )

    /** 假 ViewModel：send() 按 App 的方式把 replyingTo 拼成 `> …` 前缀后追加一条用户消息。 */
    private fun harness(
        initial: List<UIMessage>,
        entities: Map<String, MessageEntity>,
    ): Harness {
        val chat = mockk<ChatViewModel>(relaxed = true)
        val messagesFlow = MutableStateFlow(initial)
        var replying: UIMessage? = null
        var input = ""
        every { chat.state } returns MutableStateFlow(
            ChatUiState(
                sessionState = ChatSessionState(currentSessionId = SESSION_ID),
                streamState = ChatStreamState(),
            ),
        )
        every { chat.messages } returns messagesFlow
        every { chat.setReplyingTo(any()) } answers { replying = firstArg() }
        every { chat.updateInput(any()) } answers { input = firstArg() }
        every { chat.send() } answers {
            val content = replying?.let { buildQuotedContent(it.content, input) } ?: input
            messagesFlow.value = messagesFlow.value + UIMessage(role = MessageRole.USER, content = content)
            replying = null
        }
        val repo = mockk<SessionRepository>(relaxed = true)
        coEvery { repo.getMessageById(any()) } answers { entities[firstArg<String>()] }
        coEvery { repo.getMessageAsUiMessage(any()) } answers {
            initial.firstOrNull { it.id.toString() == firstArg<String>() }
        }
        val generations = mockk<ChatGenerationManager>(relaxed = true)
        every { generations.activeGenerations } returns
            MutableStateFlow<Map<String, ChatGenerationManager.ActiveGeneration>>(emptyMap())
        return Harness(
            gateway = HostWebSocketGateway(
                chatViewModel = chat,
                generationManager = generations,
                sessionRepo = repo,
                assistantRepo = mockk<AssistantRepository>(relaxed = true),
                settingsRepo = mockk<SettingsRepository>(relaxed = true),
                settingsStrings = HostSettingsStrings { name, _ -> "text:$name" },
                mediaLinks = HostMediaLinks(HostMediaStore(imageDir, voiceDir)),
            ),
            chat = chat,
            messages = messagesFlow,
        )
    }

    private fun entity(message: UIMessage, sessionId: String = SESSION_ID): MessageEntity = MessageEntity(
        id = message.id.toString(),
        sessionId = sessionId,
        role = message.role.name,
        content = message.content,
        createdAt = message.createdAt,
    )

    private fun sendCommand(requestId: String, text: String, quotedMessageId: String? = null): String {
        val quote = quotedMessageId?.let { ",\"quotedMessageId\":\"$it\"" }.orEmpty()
        return "{\"protocolVersion\":1,\"type\":\"chat.send\",\"requestId\":\"$requestId\"," +
            "\"sessionId\":\"$SESSION_ID\",\"text\":\"$text\"$quote}"
    }

    @Test
    fun `send with a valid quoted id carries the quoted body into the snapshot`() = runTest {
        val quoted = UIMessage(id = kotlin.uuid.Uuid.random(), role = MessageRole.ASSISTANT, content = "被引用的原话")
        val harness = harness(listOf(quoted), mapOf(quoted.id.toString() to entity(quoted)))

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        connection.incoming.send(Frame.Text(sendCommand("req-send", "我的回应", quoted.id.toString())))
        advanceUntilIdle()

        val sent = harness.messages.value.last()
        assertEquals(MessageRole.USER, sent.role)
        assertTrue("正文应带上 `> ` 引用块: ${sent.content}", sent.content.startsWith("> 被引用的原话"))
        val snapshot = connection.events.lastOrNull { it.type == "state.snapshot" }
        val sentMessage = snapshot?.messages?.lastOrNull { it.id == sent.id.toString() }
        assertEquals("被引用的原话", sentMessage?.quotedContent)
        assertEquals("被引用的原话", snapshot?.messages?.last()?.quotedContent)

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `send with a quoted id from another session is refused and nothing is sent`() = runTest {
        val foreign = UIMessage(id = kotlin.uuid.Uuid.random(), role = MessageRole.ASSISTANT, content = "别的会话")
        val harness = harness(
            listOf(foreign),
            mapOf(foreign.id.toString() to entity(foreign, sessionId = "another-session")),
        )

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        val before = harness.messages.value.size
        connection.incoming.send(Frame.Text(sendCommand("req-send", "想引用", foreign.id.toString())))
        advanceUntilIdle()

        val event = connection.events.firstOrNull { it.requestId == "req-send" }
        assertEquals("message_not_in_current_session", event?.error)
        verify(exactly = 0) { harness.chat.send() }
        assertEquals("不该有新消息落库", before, harness.messages.value.size)

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `send without the field behaves like before`() = runTest {
        val previous = UIMessage(id = kotlin.uuid.Uuid.random(), role = MessageRole.ASSISTANT, content = "上一轮")
        val harness = harness(listOf(previous), mapOf(previous.id.toString() to entity(previous)))

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        connection.incoming.send(Frame.Text(sendCommand("req-send", "普通消息")))
        advanceUntilIdle()

        val sent = harness.messages.value.last()
        assertEquals("普通消息", sent.content)
        val snapshot = connection.events.lastOrNull { it.type == "state.snapshot" }
        assertNull("没有引用就不该有引用块", snapshot?.messages?.last()?.quotedContent)
        assertTrue(connection.events.any { it.type == "command.accepted" && it.requestId == "req-send" })

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    /** 回归：`message.quote` 仍然回被引用消息的正文（短文本不被截断）。 */
    @Test
    fun `message quote still returns the original content`() = runTest {
        val quoted = UIMessage(id = kotlin.uuid.Uuid.random(), role = MessageRole.ASSISTANT, content = "被引用的原话")
        val harness = harness(listOf(quoted), mapOf(quoted.id.toString() to entity(quoted)))

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        connection.incoming.send(
            Frame.Text(
                "{\"protocolVersion\":1,\"type\":\"message.quote\",\"requestId\":\"req-quote\"," +
                    "\"sessionId\":\"$SESSION_ID\",\"messageId\":\"${quoted.id}\"}",
            ),
        )
        advanceUntilIdle()

        val event = connection.events.firstOrNull { it.requestId == "req-quote" }
        assertEquals("message.quote", event?.type)
        assertEquals("被引用的原话", event?.content)

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    private companion object {
        const val SESSION_ID = "session-1"
    }
}
