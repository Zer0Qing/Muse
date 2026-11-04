package io.zer0.muse.web

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.mockk.coEvery
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 事件序号 / 游标 / 补拉的契约（阶段 2 的 G1）。
 *
 * 抓到的缺陷：`sendEvent` 里写成 `eventSeq = ctx.eventSeq++` 之后再 `cursor = ctx.eventSeq`，
 * 于是 **cursor 恒等于 seq+1**。客户端把 cursor 当"我已拥有到哪一条"原样回传，
 * 服务端用 `eventSeq > cursor` 过滤 ⇒ 稳定漏掉紧随其后的那一条。
 * 今天它多半被"跨连接即退化为全量快照"掩盖，但那不是修复，是运气。
 *
 * 所以这里既锁语义（cursor == 本条 seq），也锁行为：**用服务端实际下发的 cursor 回传**，
 * 必须拿回缺的那一条，而不是被一张新快照糊过去。
 */
class HostWebSocketCursorReplayTest {

    private companion object {
        const val SESSION_ID = "session-cursor"
    }

    private val workDir = Files.createTempDirectory("host-cursor").toFile()
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

    private fun gateway(): HostWebSocketGateway {
        val chat = mockk<ChatViewModel>(relaxed = true)
        every { chat.state } returns MutableStateFlow(
            ChatUiState(
                sessionState = ChatSessionState(currentSessionId = SESSION_ID),
                streamState = ChatStreamState(),
            ),
        )
        every { chat.messages } returns MutableStateFlow(emptyList<UIMessage>())
        every { chat.send() } returns Unit
        val generations = mockk<ChatGenerationManager>(relaxed = true)
        every { generations.activeGenerations } returns MutableStateFlow(emptyMap())
        return HostWebSocketGateway(
            chatViewModel = chat,
            generationManager = generations,
            sessionRepo = mockk<SessionRepository>(relaxed = true),
            assistantRepo = mockk<AssistantRepository>(relaxed = true),
            settingsRepo = mockk<SettingsRepository>(relaxed = true),
            settingsStrings = HostSettingsStrings { name, _ -> "text:$name" },
            mediaLinks = HostMediaLinks(HostMediaStore(imageDir, voiceDir)),
        )
    }

    private fun command(type: String, requestId: String, extra: String = ""): String =
        "{\"protocolVersion\":1,\"type\":\"$type\",\"requestId\":\"$requestId\",\"sessionId\":\"$SESSION_ID\"$extra}"

    @Test
    fun `每条下发的 cursor 必须等于它自己的 eventSeq`() {
        val connection = FakeConnection()
        runTest {
            val job = launch { gateway().serve(connection.session) }
            advanceUntilIdle()
            connection.incoming.send(Frame.Text(command("state.sync", "req-a")))
            advanceUntilIdle()
            connection.incoming.send(
                Frame.Text(command("chat.send", "req-b", ",\"text\":\"你好\"")),
            )
            advanceUntilIdle()

            val sequenced = connection.events.filter { it.eventSeq != null }
            assertTrue("应当至少发出两条带序号的事件：${connection.events.map { it.type }}", sequenced.size >= 2)
            sequenced.forEach { event ->
                assertEquals(
                    "cursor 必须等于本条 eventSeq（否则补拉恒差一条），事件类型 ${event.type}",
                    event.eventSeq,
                    event.cursor,
                )
            }
            connection.incoming.close()
            advanceUntilIdle()
            job.join()
        }
    }

    /**
     * 补拉的**无缺口不变量**：客户端只拥有第一条时，返回集的第一条必须正好是第二条。
     *
     * 关键是"缺口必须真的存在"——所以 `owned` 取的是**第一条的回复**（按 requestId 精确取，
     * 不靠顺序运气），而不是最新一条。上一版我取了最新一条，旧写法于是退化成发一张新快照，
     * 而那张快照的序号恰好等于 seq+1，把不变量糊过去了（变异只红一条就是这件事的证据）。
     *
     * 旧写法 cursor = seq+1：`eventSeq > cursor` 跳过紧接的那条 → 返回集从 seq+2 开始，
     * 或者整个空掉、退化成一张新快照；新写法必须从 seq+1 起。
     */
    @Test
    fun `只拥有第一条时补拉必须从第二条开始`() {
        val connection = FakeConnection()
        runTest {
            val job = launch { gateway().serve(connection.session) }
            advanceUntilIdle()
            connection.incoming.send(Frame.Text(command("state.sync", "req-first")))
            advanceUntilIdle()
            // 客户端停在第一条；后面又发生了一条真实事件 —— 缺口从这里开始
            val owned = connection.events.first { it.requestId == "req-first" }
            assertTrue(
                "第一条必须带序号，否则测不出游标语义：${owned.type}",
                owned.eventSeq != null,
            )

            connection.incoming.send(Frame.Text(command("chat.send", "req-second", ",\"text\":\"你好\"")))
            advanceUntilIdle()

            val beforeReplay = connection.events.size
            connection.incoming.send(
                Frame.Text(command("state.sync", "req-replay", ",\"cursor\":\"${owned.cursor}\"")),
            )
            advanceUntilIdle()

            val delivered = connection.events.drop(beforeReplay)
            val replayed = delivered.mapNotNull { it.eventSeq }
            assertTrue(
                "补拉应当给回事件而不是空掉，实际：${delivered.map { it.type to it.eventSeq }}",
                replayed.isNotEmpty(),
            )
            assertEquals(
                "第一回的序号必须正好接上（跳过一条就是恒漏一条）：" +
                    "已拥有 seq=${owned.eventSeq} cursor=${owned.cursor}，返回 $replayed",
                owned.eventSeq!! + 1,
                replayed.min(),
            )
            connection.incoming.close()
            advanceUntilIdle()
            job.join()
        }
    }
}
