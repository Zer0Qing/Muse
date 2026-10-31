package io.zer0.muse.web

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.ui.ChatError
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.ChatSessionState
import io.zer0.muse.ui.ChatStreamPhase
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
import java.util.Base64

/**
 * 快照里补齐的三类数据：媒体可访问地址、工具调用明细、消息级状态。
 *
 * 断言都落在 `state.snapshot` 事件上 —— 这条事件是网页端渲染的唯一数据源，
 * 它拿不到的东西网页端就一定拿不到。
 */
class HostWebSocketGatewayMediaTest {

    private val workDir = Files.createTempDirectory("host-gateway-media").toFile()
    private val imageDir = File(workDir, "muse_images").apply { mkdirs() }
    private val voiceDir = File(workDir, "voice_messages").apply { mkdirs() }
    private val mediaStore = HostMediaStore(imageDir, voiceDir)

    /** 一条被打通到网关的假连接：命令从 [incoming] 送入，事件从 [events] 读出。 */
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

    private fun gateway(messages: List<UIMessage>, state: ChatUiState): HostWebSocketGateway {
        val chat = mockk<ChatViewModel>(relaxed = true)
        every { chat.state } returns MutableStateFlow(state)
        every { chat.messages } returns MutableStateFlow(messages)
        val generations = mockk<ChatGenerationManager>(relaxed = true)
        every { generations.activeGenerations } returns
            MutableStateFlow<Map<String, ChatGenerationManager.ActiveGeneration>>(emptyMap())
        return HostWebSocketGateway(
            chatViewModel = chat,
            generationManager = generations,
            sessionRepo = mockk<SessionRepository>(relaxed = true),
            assistantRepo = mockk<AssistantRepository>(relaxed = true),
            settingsRepo = mockk<SettingsRepository>(relaxed = true),
            settingsStrings = HostSettingsStrings { name, _ -> "text:$name" },
            mediaLinks = HostMediaLinks(mediaStore),
        )
    }

    /** 起一条连接,取它收到的第一份快照(与 serve 建立时下发的那份同源)。 */
    private fun snapshotOf(
        messages: List<UIMessage>,
        state: ChatUiState = defaultState(),
    ): HostEvent {
        var captured: HostEvent? = null
        runTest {
            val connection = FakeConnection()
            val job = launch { gateway(messages, state).serve(connection.session) }
            advanceUntilIdle()
            captured = connection.events.firstOrNull { it.type == "state.snapshot" }
            connection.incoming.close()
            advanceUntilIdle()
            job.join()
        }
        val event = captured
        assertNotNull("应有一份快照事件", event)
        return event!!
    }

    private fun message(event: HostEvent, index: Int): HostMessage = event.messages[index]

    @Test
    fun `file and base64 image refs become media routes while unusable ones are dropped`() {
        val file = File(imageDir, "gen_0_dead.bin").apply { writeBytes(PNG_BYTES) }
        val dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_BYTES)
        val urls = message(
            snapshotOf(
                listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        content = "看图",
                        imageUrls = listOf(
                            "file://${file.absolutePath}",
                            dataUri,
                            "https://cdn.example.com/a.png",
                            "plain text, definitely not media",
                        ),
                    ),
                ),
            ),
            0,
        ).imageUrls

        assertEquals(3, urls.size)
        urls.forEach { assertTrue("应下发浏览器能用的地址: $it", it.startsWith(MEDIA_PREFIX) || it.startsWith("https://")) }
        assertEquals(file.canonicalFile, mediaStore.resolve(urls[0].removePrefix(MEDIA_PREFIX)))
        assertNotNull(mediaStore.resolve(urls[1].removePrefix(MEDIA_PREFIX)))
        assertEquals("https://cdn.example.com/a.png", urls[2])
    }

    @Test
    fun `voice url and duration ride on the same rule`() {
        val clip = File(voiceDir, "say.m4a").apply { writeBytes(M4A_BYTES) }
        val cleaned = File(voiceDir, "gone.m4a").apply { writeBytes(M4A_BYTES) }
        assertTrue(cleaned.delete())

        val host = message(snapshotOf(listOf(voiceMessage("file://${clip.absolutePath}", 3_400L))), 0)
        val removed = message(snapshotOf(listOf(voiceMessage("file://${cleaned.absolutePath}", 0L))), 0)

        assertTrue("语音地址同样走媒体路由", host.voiceUrl!!.startsWith(MEDIA_PREFIX))
        assertEquals(clip.canonicalFile, mediaStore.resolve(host.voiceUrl!!.removePrefix(MEDIA_PREFIX)))
        assertEquals(3_400L, host.voiceDurationMs!!)
        assertNull("语音已被清理时不下发地址", removed.voiceUrl)
        assertNull(removed.voiceDurationMs)
    }

    @Test
    fun `a message with two tool calls carries both, with truncated previews`() {
        val longArguments = """{"path":"/sdcard/x.txt","note":"${"N".repeat(2_000)}"}"""
        val calls = message(
            snapshotOf(
                listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        content = "",
                        toolCalls = listOf(
                            ToolCall(id = "call_read", name = "read_file", arguments = longArguments),
                            ToolCall(id = "call_search", name = "web_search", arguments = """{"q":"muse"}"""),
                        ),
                    ),
                ),
            ),
            0,
        ).toolCalls

        assertEquals(2, calls.size)
        assertEquals("call_read", calls[0].callId)
        assertEquals("read_file", calls[0].name)
        assertEquals("call_search", calls[1].callId)
        assertEquals("web_search", calls[1].name)
        assertTrue("参数预览应被截断", calls[0].argumentsPreview.length < longArguments.length)
        assertTrue(calls[0].argumentsPreview.contains("path"))
        assertTrue(calls[0].argumentsPreview.contains("note"))
        assertNull("请求列表里的调用还没有结果", calls[0].resultPreview)
        assertNull(calls[0].success)
        assertNull(calls[0].durationMs)
        assertEquals("""{"q":"muse"}""", calls[1].argumentsPreview)
    }

    @Test
    fun `tool card carries result success and duration while blank result stays null`() {
        val longResult = "R".repeat(4_000)
        val info = ToolCallInfo(
            toolName = "shell",
            arguments = """{"cmd":"ls"}""",
            result = longResult,
            isSuccess = false,
            durationMs = 51L,
        )
        val withResult = UIMessage(role = MessageRole.ASSISTANT, content = "", toolCallId = "call_77", toolCallInfo = info)
        val emptyResult = withResult.copy(toolCallInfo = info.copy(result = "   "))

        val first = message(snapshotOf(listOf(withResult)), 0)
        val second = message(snapshotOf(listOf(emptyResult)), 0)

        assertEquals("shell", first.toolName)
        assertEquals(1, first.toolCalls.size)
        assertEquals("call_77", first.toolCalls[0].callId)
        assertEquals("""{"cmd":"ls"}""", first.toolCalls[0].argumentsPreview)
        assertEquals(false, first.toolCalls[0].success)
        assertEquals(51L, first.toolCalls[0].durationMs!!)
        assertTrue("结果预览应被截断", first.toolCalls[0].resultPreview!!.length < longResult.length)
        assertTrue(first.toolCalls[0].resultPreview!!.startsWith("RRR"))
        assertNull("空结果给 null 而不是空串", second.toolCalls[0].resultPreview)
    }

    @Test
    fun `request only call is merged into the tool card instead of listed twice`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCalls = listOf(ToolCall(id = "call_one", name = "read_file", arguments = "{}")),
            toolCallInfo = ToolCallInfo(toolName = "read_file", arguments = "{}", result = "abc", isSuccess = true),
        )

        val calls = message(snapshotOf(listOf(assistant)), 0).toolCalls

        assertEquals(1, calls.size)
        assertEquals("call_one", calls[0].callId)
        assertEquals(true, calls[0].success)
        assertEquals("abc", calls[0].resultPreview)
    }

    @Test
    fun `status marks the streaming reply while the turn runs`() {
        val user = UIMessage(role = MessageRole.USER, content = "你好")
        val assistant = UIMessage(role = MessageRole.ASSISTANT, content = "开场")
        val state = defaultState(
            ChatStreamState(phase = ChatStreamPhase.STREAMING, isStreaming = true, isWaitingFirstToken = false),
        )

        val snapshot = snapshotOf(listOf(user, assistant), state)

        assertEquals(HostMessageStatus.SENT, message(snapshot, 0).status)
        assertEquals(HostMessageStatus.STREAMING, message(snapshot, 1).status)
        assertNull(message(snapshot, 1).errorText)
    }

    @Test
    fun `user message is sending until the first token arrives`() {
        val assistant = UIMessage(role = MessageRole.ASSISTANT, content = "上一轮")
        val user = UIMessage(role = MessageRole.USER, content = "新一轮")
        val state = defaultState(
            ChatStreamState(phase = ChatStreamPhase.CONNECTING, isStreaming = true, isWaitingFirstToken = true),
        )

        val snapshot = snapshotOf(listOf(assistant, user), state)

        assertEquals(HostMessageStatus.STREAMING, message(snapshot, 0).status)
        assertEquals(HostMessageStatus.SENDING, message(snapshot, 1).status)
    }

    @Test
    fun `failed turn marks the last assistant message with an error text`() {
        val user = UIMessage(role = MessageRole.USER, content = "你好")
        val assistant = UIMessage(role = MessageRole.ASSISTANT, content = "")
        val state = ChatUiState(
            sessionState = ChatSessionState(currentSessionId = SESSION_ID),
            streamState = ChatStreamState(phase = ChatStreamPhase.FAILED),
            errors = listOf(ChatError(type = ChatErrorType.NETWORK, message = "上游连接被重置")),
        )

        val snapshot = snapshotOf(listOf(user, assistant), state)

        assertEquals(HostMessageStatus.SENT, message(snapshot, 0).status)
        assertEquals(HostMessageStatus.FAILED, message(snapshot, 1).status)
        assertEquals("上游连接被重置", message(snapshot, 1).errorText)
    }

    @Test
    fun `dismissed error leaves the finished reply as sent`() {
        val assistant = UIMessage(role = MessageRole.ASSISTANT, content = "半截回复")
        val state = ChatUiState(
            sessionState = ChatSessionState(currentSessionId = SESSION_ID),
            streamState = ChatStreamState(phase = ChatStreamPhase.FAILED),
            errors = emptyList(),
        )

        assertEquals(HostMessageStatus.SENT, message(snapshotOf(listOf(assistant), state), 0).status)
    }

    @Test
    fun `snapshot advertises the three new capabilities`() {
        val flags = snapshotOf(emptyList()).capabilityFlags

        assertTrue(flags.containsAll(listOf("media", "tool_call_details", "message_status")))
    }

    @Test
    fun `existing fields keep their meaning`() {
        val file = File(imageDir, "keep.bin").apply { writeBytes(PNG_BYTES) }
        val info = ToolCallInfo(toolName = "camera_snap", arguments = "{}", result = "ok", isSuccess = true)
        val snapshot = snapshotOf(
            listOf(
                UIMessage(role = MessageRole.USER, content = "拍一张", imageUrls = listOf("file://${file.absolutePath}")),
                UIMessage(role = MessageRole.ASSISTANT, content = "", toolCallInfo = info),
            ),
        )

        assertEquals("user", message(snapshot, 0).role)
        assertEquals("拍一张", message(snapshot, 0).content)
        assertEquals(1, message(snapshot, 0).imageUrls.size)
        assertEquals(HostMessageStatus.SENT, message(snapshot, 0).status)
        assertEquals("camera_snap", message(snapshot, 1).toolName)
        assertEquals(emptyList<String>(), message(snapshot, 1).citationUrls)
        assertNull(message(snapshot, 1).voiceUrl)
    }

    private fun defaultState(streamState: ChatStreamState = ChatStreamState()) = ChatUiState(
        sessionState = ChatSessionState(currentSessionId = SESSION_ID),
        streamState = streamState,
    )

    private fun voiceMessage(uri: String, durationMs: Long) = UIMessage(
        role = MessageRole.ASSISTANT,
        content = "",
        voiceUri = uri,
        voiceDurationMs = durationMs,
    )

    private companion object {
        const val SESSION_ID = "session-1"
        const val MEDIA_PREFIX = "/api/media/"

        val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(24)
        val M4A_BYTES = byteArrayOf(0, 0, 0, 0x20.toByte()) + "ftyp".toByteArray() + "M4A ".toByteArray() + ByteArray(16)
    }
}
