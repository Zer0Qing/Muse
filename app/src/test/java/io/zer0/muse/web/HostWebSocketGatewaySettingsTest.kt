package io.zer0.muse.web

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.common.AppJson
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.ChatViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * settings.update 的焦点策略：全局设置**不**受会话焦点约束。
 *
 * 背景：会话写（chat.send / session.select / …）必须串行，避免两个客户端同时往同一会话里
 * 塞消息；全局设置没有这个问题——手机正在聊天的时候，网页改一个开关不该被拒，
 * 冲突按后写覆盖即可。这个测试就是那条策略的可执行版本：
 * 另一个连接正持焦点，本连接照样能改，而且改动真的写进了 SettingsRepository。
 */
class HostWebSocketGatewaySettingsTest {

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

    private class Harness(val gateway: HostWebSocketGateway, val settingsRepo: SettingsRepository)

    private val tempDir = Files.createTempDirectory("host-gateway-settings").toFile()

    private fun harness(): Harness {
        val chat = mockk<ChatViewModel>(relaxed = true)
        every { chat.state } returns MutableStateFlow(ChatUiState())
        every { chat.messages } returns MutableStateFlow(emptyList())
        val generations = mockk<ChatGenerationManager>(relaxed = true)
        every { generations.activeGenerations } returns
            MutableStateFlow<Map<String, ChatGenerationManager.ActiveGeneration>>(emptyMap())
        val settingsRepo = mockk<SettingsRepository>(relaxed = true)
        return Harness(
            gateway = HostWebSocketGateway(
                chatViewModel = chat,
                generationManager = generations,
                sessionRepo = mockk<SessionRepository>(relaxed = true),
                assistantRepo = mockk<AssistantRepository>(relaxed = true),
                settingsRepo = settingsRepo,
                settingsStrings = HostSettingsStrings { name, _ -> "text:$name" },
                mediaLinks = HostMediaLinks(
                    HostMediaStore(
                        imageDir = File(tempDir, "muse_images").apply { mkdirs() },
                        voiceDir = File(tempDir, "voice_messages").apply { mkdirs() },
                    ),
                ),
            ),
            settingsRepo = settingsRepo,
        )
    }

    @Test
    fun `settings update applies for a connection that does not hold the focus`() = runTest {
        val harness = harness()
        val phone = FakeConnection()
        val browser = FakeConnection()

        // 第一个连接（手机）在 serve() 里抢占焦点；第二个连接（网页）因此拿不到焦点。
        val phoneJob = launch { harness.gateway.serve(phone.session) }
        advanceUntilIdle()
        val browserJob = launch { harness.gateway.serve(browser.session) }
        advanceUntilIdle()

        browser.incoming.send(
            Frame.Text(
                """{"protocolVersion":1,"type":"settings.update","requestId":"req-settings","key":"piiGuardEnabled","value":"true"}""",
            ),
        )
        advanceUntilIdle()

        assertTrue(
            "设置写入应被接受，实际事件: ${browser.events}",
            browser.events.any { it.type == "command.accepted" && it.requestId == "req-settings" },
        )
        assertTrue(
            "设置写入不该被焦点拦下: ${browser.events}",
            browser.events.none { it.error == "focus_not_held" },
        )
        coVerify(exactly = 1) { harness.settingsRepo.savePiiGuardEnabled(true) }

        // 前提核对：同一个未持焦点的连接发会话写仍然被拒 —— 焦点隔离对 session.* 依然有效。
        browser.incoming.send(Frame.Text("""{"protocolVersion":1,"type":"session.new","requestId":"req-new"}"""))
        advanceUntilIdle()

        assertTrue(
            "会话写必须继续守着焦点: ${browser.events}",
            browser.events.any { it.type == "error" && it.requestId == "req-new" && it.error == "focus_not_held" },
        )

        browser.incoming.close()
        phone.incoming.close()
        advanceUntilIdle()
        browserJob.join()
        phoneJob.join()
    }
}
