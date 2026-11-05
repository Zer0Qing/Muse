package io.zer0.muse.web

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 命令幂等的**作用域**（阶段 2 的 G4）。
 *
 * 缺陷形状：去重集合原来挂在 `ConnectionContext` 上，连接一断就没了。
 * 于是"同一个 requestId 再发一次"在重连之后会被当成**新命令再执行一遍**——
 * 对写操作来说，再执行一遍可能就是多删一条、多插一条。
 * 今天网页客户端每次都新生成 UUID，所以暴露面是理论的；
 * 但这条正是"结果未知后重试"该有安全网的地方（阶段 2 验收：写操作结果未知时不盲目重发），
 * 等离线队列/标签页恢复一进来就会撞上。
 *
 * 参照同一层的 `focus`：焦点令牌本来就是跨连接状态，去重也应该是。
 */
class HostWebSocketIdempotencyScopeTest {

    private companion object {
        const val SESSION_ID = "session-idem"
        const val SHARED_REQUEST_ID = "req-shared-across-connections"
    }

    private val workDir = Files.createTempDirectory("host-idem").toFile()
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

    private fun harness(): Pair<HostWebSocketGateway, ChatViewModel> {
        val chat = mockk<ChatViewModel>(relaxed = true)
        every { chat.state } returns MutableStateFlow(
            ChatUiState(
                sessionState = ChatSessionState(currentSessionId = SESSION_ID),
                streamState = ChatStreamState(),
            ),
        )
        every { chat.messages } returns MutableStateFlow(emptyList<UIMessage>())
        val generations = mockk<ChatGenerationManager>(relaxed = true)
        every { generations.activeGenerations } returns MutableStateFlow(emptyMap())
        val gateway = HostWebSocketGateway(
            chatViewModel = chat,
            generationManager = generations,
            sessionRepo = mockk<SessionRepository>(relaxed = true),
            assistantRepo = mockk<AssistantRepository>(relaxed = true),
            settingsRepo = mockk<SettingsRepository>(relaxed = true),
            settingsStrings = HostSettingsStrings { name, _ -> "text:$name" },
            mediaLinks = HostMediaLinks(HostMediaStore(imageDir, voiceDir)),
        )
        return gateway to chat
    }

    private fun sendCommand(requestId: String): String =
        "{\"protocolVersion\":1,\"type\":\"chat.send\",\"requestId\":\"$requestId\"," +
            "\"sessionId\":\"$SESSION_ID\",\"text\":\"你好\"}"

    @Test
    fun `重连后同一 requestId 不会被第二次执行`() {
        val (gateway, chat) = harness()
        val first = FakeConnection()
        val second = FakeConnection()

        runTest {
            val jobA = launch { gateway.serve(first.session) }
            advanceUntilIdle()
            first.incoming.send(Frame.Text(sendCommand(SHARED_REQUEST_ID)))
            advanceUntilIdle()

            // 第一个连接断开（焦点随之释放），第二个连接上来
            first.incoming.close()
            advanceUntilIdle()
            jobA.join()

            val jobB = launch { gateway.serve(second.session) }
            advanceUntilIdle()
            second.incoming.send(Frame.Text(sendCommand(SHARED_REQUEST_ID)))
            advanceUntilIdle()

            coVerify(exactly = 1) { chat.send() }

            val duplicate = second.events.firstOrNull { it.type == "command.duplicate" }
            assertNotNull(
                "跨连接的同 requestId 必须被判重（否则写操作会执行两遍）。实际收到：" +
                    second.events.map { it.type to it.requestId },
                duplicate,
            )
            assertTrue(
                "判重事件要带上被拒的 requestId，客户端才能收口对应的待办",
                duplicate!!.requestId == SHARED_REQUEST_ID,
            )

            second.incoming.close()
            advanceUntilIdle()
            jobB.join()
        }
    }

    @Test
    fun `不同 requestId 不受共享集合影响`() {
        val (gateway, chat) = harness()
        val a = FakeConnection()
        val b = FakeConnection()

        runTest {
            val jobA = launch { gateway.serve(a.session) }
            advanceUntilIdle()
            a.incoming.send(Frame.Text(sendCommand("req-1")))
            advanceUntilIdle()
            a.incoming.close()
            advanceUntilIdle()
            jobA.join()

            val jobB = launch { gateway.serve(b.session) }
            advanceUntilIdle()
            b.incoming.send(Frame.Text(sendCommand("req-2")))
            advanceUntilIdle()

            coVerify(exactly = 2) { chat.send() }
            assertTrue(
                "新 requestId 不该被共享集合误挡：${b.events.map { it.type to it.error }}",
                b.events.none { it.type == "command.duplicate" },
            )

            b.incoming.close()
            advanceUntilIdle()
            jobB.join()
        }
    }
}
