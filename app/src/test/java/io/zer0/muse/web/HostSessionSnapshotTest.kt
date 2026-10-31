package io.zer0.muse.web

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.zer0.common.AppJson
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionEntity
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
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 会话侧的 Host 缺口：归档会话要能看见、能点开；新建会话要认 `assistantId`。
 */
class HostSessionSnapshotTest {

    private val workDir = Files.createTempDirectory("host-session-snapshot").toFile()
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
        val assistants: AssistantRepository,
        val state: MutableStateFlow<ChatUiState>,
    )

    private fun harness(
        sessions: List<SessionEntity> = emptyList(),
        assistantsById: Map<String, AssistantEntity> = emptyMap(),
        currentSessionId: String? = SESSION_ID,
    ): Harness {
        val stateFlow = MutableStateFlow(
            ChatUiState(
                sessionState = ChatSessionState(currentSessionId = currentSessionId, sessions = sessions),
                streamState = ChatStreamState(),
            ),
        )
        val chat = mockk<ChatViewModel>(relaxed = true)
        every { chat.state } returns stateFlow
        every { chat.messages } returns MutableStateFlow(emptyList())
        every { chat.switchSession(any()) } answers {
            val target = firstArg<String>()
            val current = stateFlow.value
            stateFlow.value = ChatUiState(
                sessionState = ChatSessionState(
                    currentSessionId = target,
                    sessions = current.sessionState.sessions,
                ),
                streamState = ChatStreamState(),
            )
        }
        val assistantRepo = mockk<AssistantRepository>(relaxed = true)
        coEvery { assistantRepo.getById(any()) } answers { assistantsById[firstArg<String>()] }
        val generations = mockk<ChatGenerationManager>(relaxed = true)
        every { generations.activeGenerations } returns
            MutableStateFlow<Map<String, ChatGenerationManager.ActiveGeneration>>(emptyMap())
        return Harness(
            gateway = HostWebSocketGateway(
                chatViewModel = chat,
                generationManager = generations,
                sessionRepo = mockk<SessionRepository>(relaxed = true),
                assistantRepo = assistantRepo,
                settingsRepo = mockk<SettingsRepository>(relaxed = true),
                settingsStrings = HostSettingsStrings { name, _ -> "text:$name" },
                mediaLinks = HostMediaLinks(HostMediaStore(imageDir, voiceDir)),
            ),
            chat = chat,
            assistants = assistantRepo,
            state = stateFlow,
        )
    }

    private fun session(
        id: String,
        archived: Boolean = false,
        deletedAt: Long? = null,
        assistantId: String = "default",
    ) = SessionEntity(
        id = id,
        title = id,
        createdAt = 1L,
        updatedAt = 2L,
        assistantId = assistantId,
        archived = archived,
        deletedAt = deletedAt,
    )

    @Test
    fun `snapshot carries archived sessions and drops deleted ones`() = runTest {
        val harness = harness(
            sessions = listOf(
                session("live"),
                session("archived", archived = true),
                session("deleted", deletedAt = 3L),
            ),
        )

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        val snapshot = connection.events.firstOrNull { it.type == "state.snapshot" }

        val ids = snapshot?.sessions?.map { it.id }
        assertEquals(listOf("live", "archived"), ids)
        assertEquals("归档会话要带 archived 标记", true, snapshot?.sessions?.first { it.id == "archived" }?.archived)
        assertEquals(false, snapshot?.sessions?.first { it.id == "live" }?.archived)

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `session select works for an archived session`() = runTest {
        val harness = harness(
            sessions = listOf(session("live"), session("archived", archived = true)),
        )

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        connection.incoming.send(
            Frame.Text("{\"protocolVersion\":1,\"type\":\"session.select\",\"requestId\":\"req-select\",\"sessionId\":\"archived\"}"),
        )
        advanceUntilIdle()

        val accepted = connection.events.firstOrNull { it.requestId == "req-select" }
        assertEquals(
            "事件序列: ${connection.events.map { it.type to it.error }}",
            "command.accepted",
            accepted?.type,
        )
        assertEquals("archived", accepted?.sessionId)
        assertEquals("archived", harness.state.value.currentSessionId)
        verify(exactly = 1) { harness.chat.switchSession("archived") }

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `session new binds the requested assistant`() = runTest {
        val harness = harness(assistantsById = mapOf("narrator" to AssistantEntity(id = "narrator", name = "Narrator")))

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        connection.incoming.send(
            Frame.Text("{\"protocolVersion\":1,\"type\":\"session.new\",\"requestId\":\"req-new\",\"assistantId\":\"narrator\"}"),
        )
        advanceUntilIdle()

        assertEquals("command.accepted", connection.events.firstOrNull { it.requestId == "req-new" }?.type)
        verify(exactly = 1) { harness.chat.createNewSession(any(), "narrator") }

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `session new without assistant id keeps the default behaviour`() = runTest {
        val harness = harness()

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        connection.incoming.send(Frame.Text("{\"protocolVersion\":1,\"type\":\"session.new\",\"requestId\":\"req-new\"}"))
        advanceUntilIdle()

        assertEquals("command.accepted", connection.events.firstOrNull { it.requestId == "req-new" }?.type)
        verify(exactly = 1) { harness.chat.createNewSession(any(), null) }

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `session new with an unknown assistant reports assistant_not_found`() = runTest {
        val harness = harness()

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        connection.incoming.send(
            Frame.Text("{\"protocolVersion\":1,\"type\":\"session.new\",\"requestId\":\"req-new\",\"assistantId\":\"ghost\"}"),
        )
        advanceUntilIdle()

        val event = connection.events.firstOrNull { it.requestId == "req-new" }
        assertEquals("error", event?.type)
        assertEquals("assistant_not_found", event?.error)
        verify(exactly = 0) { harness.chat.createNewSession(any(), any()) }

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    private companion object {
        const val SESSION_ID = "session-1"
    }
}
