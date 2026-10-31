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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * 消息级写命令：`message.delete` / `message.favorite` / `message.quote`。
 *
 * 闸门与 `chat.send` 同一条焦点规则；删除只动当前会话里真实存在的消息，
 * 重复删/重复收藏都不报错。
 */
class HostMessageCommandsTest {

    private val workDir = Files.createTempDirectory("host-message-commands").toFile()
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
        val repo: SessionRepository,
        val messages: MutableStateFlow<List<UIMessage>>,
        val favorites: MutableMap<String, Boolean>,
    )

    private fun harness(
        initial: List<UIMessage>,
        streaming: Boolean = false,
        entities: Map<String, MessageEntity> = emptyMap(),
    ): Harness {
        val chat = mockk<ChatViewModel>(relaxed = true)
        val messagesFlow = MutableStateFlow(initial)
        val favorites = mutableMapOf<String, Boolean>()
        every { chat.state } returns MutableStateFlow(
            ChatUiState(
                sessionState = ChatSessionState(currentSessionId = SESSION_ID),
                streamState = ChatStreamState(isStreaming = streaming),
            ),
        )
        every { chat.messages } returns messagesFlow
        // 删完要让列表真的变短，否则验不出「同一批再删一次」的幂等。
        every { chat.deleteMessages(any()) } answers {
            val ids = firstArg<List<kotlin.uuid.Uuid>>().map { it.toString() }.toSet()
            messagesFlow.value = messagesFlow.value.filterNot { it.id.toString() in ids }
        }
        every { chat.setMessageFavorite(any(), any()) } answers {
            favorites[firstArg<kotlin.uuid.Uuid>().toString()] = secondArg()
        }
        val repo = mockk<SessionRepository>(relaxed = true)
        coEvery { repo.getMessageById(any()) } answers {
            entities[firstArg<String>()]
        }
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
            repo = repo,
            messages = messagesFlow,
            favorites = favorites,
        )
    }

    private fun message(role: MessageRole, content: String): UIMessage =
        UIMessage(id = kotlin.uuid.Uuid.random(), role = role, content = content)

    private fun entity(message: UIMessage, sessionId: String = SESSION_ID): MessageEntity = MessageEntity(
        id = message.id.toString(),
        sessionId = sessionId,
        role = message.role.name,
        content = message.content,
        createdAt = message.createdAt,
    )

    private fun command(type: String, requestId: String, payload: String): String =
        "{\"protocolVersion\":1,\"type\":\"$type\",\"requestId\":\"$requestId\",\"sessionId\":\"$SESSION_ID\"$payload}"

    private fun deleteCommand(requestId: String, ids: List<String>): String {
        val list = ids.joinToString(",") { "\"$it\"" }
        return command("message.delete", requestId, ",\"messageIds\":[$list]")
    }

    private fun favoriteCommand(requestId: String, id: String, favored: Boolean): String =
        command("message.favorite", requestId, ",\"messageId\":\"$id\",\"favored\":$favored")

    // ── 1. 焦点 ────────────────────────────────────────────────

    @Test
    fun `delete and favorite without the focus are refused`() = runTest {
        val target = message(MessageRole.ASSISTANT, "回复")
        val harness = harness(listOf(target), entities = mapOf(target.id.toString() to entity(target)))

        val holder = FakeConnection()
        val holderJob = launch { harness.gateway.serve(holder.session) }
        advanceUntilIdle()
        val other = FakeConnection()
        val otherJob = launch { harness.gateway.serve(other.session) }
        advanceUntilIdle()

        other.incoming.send(Frame.Text(deleteCommand("req-del", listOf(target.id.toString()))))
        other.incoming.send(Frame.Text(favoriteCommand("req-fav", target.id.toString(), true)))
        advanceUntilIdle()

        assertEquals("focus_not_held", other.events.firstOrNull { it.requestId == "req-del" }?.error)
        assertEquals("focus_not_held", other.events.firstOrNull { it.requestId == "req-fav" }?.error)
        verify(exactly = 0) { harness.chat.deleteMessages(any()) }
        verify(exactly = 0) { harness.chat.setMessageFavorite(any(), any()) }
        assertEquals("仓储零变化：消息仍在", 1, harness.messages.value.size)
        assertTrue(harness.favorites.isEmpty())

        other.incoming.close()
        holder.incoming.close()
        advanceUntilIdle()
        otherJob.join()
        holderJob.join()
    }

    // ── 2. 批量删除 ────────────────────────────────────────────

    @Test
    fun `batch delete reports deleted count and skipped ids`() = runTest {
        val first = message(MessageRole.USER, "一")
        val second = message(MessageRole.ASSISTANT, "二")
        val third = message(MessageRole.USER, "三")
        val harness = harness(listOf(first, second, third))

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        connection.incoming.send(
            Frame.Text(deleteCommand("req-del", listOf(first.id.toString(), second.id.toString(), "no-such-id"))),
        )
        advanceUntilIdle()

        val accepted = connection.events.firstOrNull { it.requestId == "req-del" }
        assertNotNull("删除应被受理: ${connection.events.map { it.type to it.error }}", accepted)
        assertEquals(2, accepted!!.deletedCount)
        assertEquals(listOf("no-such-id"), accepted.skippedMessageIds)
        assertEquals("库里应只剩第三条", listOf(third.id.toString()), harness.messages.value.map { it.id.toString() })

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    // ── 3. 正在生成的那条 ──────────────────────────────────────

    @Test
    fun `deleting the streaming reply is refused as a whole`() = runTest {
        val user = message(MessageRole.USER, "问题")
        val streaming = message(MessageRole.ASSISTANT, "正在写")
        val harness = harness(listOf(user, streaming), streaming = true)

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        connection.incoming.send(
            Frame.Text(deleteCommand("req-del", listOf(user.id.toString(), streaming.id.toString()))),
        )
        advanceUntilIdle()

        val error = connection.events.firstOrNull { it.requestId == "req-del" }
        assertEquals("message_is_streaming", error?.error)
        verify(exactly = 0) { harness.chat.deleteMessages(any()) }
        assertEquals("整批被拒：两条都还在", 2, harness.messages.value.size)

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    // ── 4. 幂等 ────────────────────────────────────────────────

    @Test
    fun `deleting the same ids twice is a no-op the second time`() = runTest {
        val first = message(MessageRole.USER, "一")
        val second = message(MessageRole.ASSISTANT, "二")
        val harness = harness(listOf(first, second))

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        val ids = listOf(first.id.toString(), second.id.toString())
        connection.incoming.send(Frame.Text(deleteCommand("req-1", ids)))
        advanceUntilIdle()
        connection.incoming.send(Frame.Text(deleteCommand("req-2", ids)))
        advanceUntilIdle()

        val firstResult = connection.events.firstOrNull { it.requestId == "req-1" }
        val secondResult = connection.events.firstOrNull { it.requestId == "req-2" }
        assertEquals(2, firstResult?.deletedCount)
        assertEquals("第二次应全部跳过而不是报错", 0, secondResult?.deletedCount)
        assertEquals(ids, secondResult?.skippedMessageIds)
        assertNull("重复删不报错", secondResult?.error)

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    // ── 5. 收藏 ────────────────────────────────────────────────

    @Test
    fun `favorite flips on and off and on without toggling unexpectedly`() = runTest {
        val target = message(MessageRole.ASSISTANT, "好句")
        val harness = harness(listOf(target), entities = mapOf(target.id.toString() to entity(target)))

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        val id = target.id.toString()
        connection.incoming.send(Frame.Text(favoriteCommand("req-1", id, true)))
        advanceUntilIdle()
        assertEquals("开", true, harness.favorites[id])
        connection.incoming.send(Frame.Text(favoriteCommand("req-2", id, false)))
        advanceUntilIdle()
        assertEquals("关", false, harness.favorites[id])
        connection.incoming.send(Frame.Text(favoriteCommand("req-3", id, true)))
        advanceUntilIdle()
        assertEquals("再开", true, harness.favorites[id])
        // 重复设置同一状态是幂等的：再发一次 true 不报错，值不变。
        connection.incoming.send(Frame.Text(favoriteCommand("req-4", id, true)))
        advanceUntilIdle()
        assertEquals(true, harness.favorites[id])
        assertNull(connection.events.firstOrNull { it.requestId == "req-4" }?.error)

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `favorite on an unknown id reports an error instead of crashing`() = runTest {
        val harness = harness(emptyList())

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        connection.incoming.send(Frame.Text(favoriteCommand("req-fav", UUID.randomUUID().toString(), true)))
        advanceUntilIdle()

        assertEquals("message_not_found", connection.events.firstOrNull { it.requestId == "req-fav" }?.error)
        verify(exactly = 0) { harness.chat.setMessageFavorite(any(), any()) }

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    // ── 6. 引用 ────────────────────────────────────────────────

    @Test
    fun `quote returns the content for a message in the current session`() = runTest {
        val target = message(MessageRole.ASSISTANT, "被引用的原话")
        val harness = harness(listOf(target), entities = mapOf(target.id.toString() to entity(target)))

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        connection.incoming.send(
            Frame.Text(command("message.quote", "req-quote", ",\"messageId\":\"${target.id}\"")),
        )
        advanceUntilIdle()

        val quoted = connection.events.firstOrNull { it.requestId == "req-quote" }
        assertEquals("message.quote", quoted?.type)
        assertEquals("被引用的原话", quoted?.content)
        assertEquals(target.id.toString(), quoted?.messageId)

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `quote refuses a message from another session`() = runTest {
        val target = message(MessageRole.ASSISTANT, "别的会话")
        val harness = harness(
            listOf(target),
            entities = mapOf(target.id.toString() to entity(target, sessionId = "another-session")),
        )

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        connection.incoming.send(
            Frame.Text(command("message.quote", "req-quote", ",\"messageId\":\"${target.id}\"")),
        )
        advanceUntilIdle()

        val event = connection.events.firstOrNull { it.requestId == "req-quote" }
        assertEquals("message_not_in_current_session", event?.error)
        assertNull("跨会话不该把内容带出去", event?.content)

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `snapshot advertises the message write capabilities`() = runTest {
        val harness = harness(emptyList())

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        val flags = connection.events.firstOrNull { it.type == "state.snapshot" }?.capabilityFlags.orEmpty()

        assertTrue(flags.containsAll(listOf("message_write", "message_multi_select")))

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    private companion object {
        const val SESSION_ID = "session-1"
    }
}
