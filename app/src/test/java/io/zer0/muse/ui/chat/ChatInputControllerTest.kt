package io.zer0.muse.ui.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.UIMessage
import io.zer0.muse.ui.ChatInputState
import io.zer0.muse.ui.ChatSessionState
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.PendingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 内存实现的 [ChatStateAccessor],供 Controller 纯逻辑测试。 */
private class FakeAccessor(initial: ChatUiState) : ChatStateAccessor {
    private val state = MutableStateFlow(initial)
    override val snapshot: ChatUiState get() = state.value
    override fun update(transform: (ChatUiState) -> ChatUiState) = state.update(transform)
    override val messagesSnapshot: List<UIMessage> get() = emptyList()
    override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) = Unit
    override val coroutineScope: CoroutineScope get() = CoroutineScope(Dispatchers.Unconfined)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatInputControllerTest {

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    private fun controller(
        state: ChatUiState,
        onSend: (String, List<String>, String) -> Unit = { _, _, _ -> },
    ): Pair<ChatInputController, FakeAccessor> {
        val accessor = FakeAccessor(state)
        return ChatInputController(accessor, appContext, onSend) to accessor
    }

    @Test
    fun `updateInput does not mark typing as draft`() {
        // v2.6.7: 这是用户反馈的直接回归——“打什么字进去它都会显示是草稿”。
        // 输入就是输入，不是草稿状态；只有从持久化恢复的内容才算草稿。
        // 草稿标记不再点亮，hasDraft 保持进入时的值（false）。
        val (controller, accessor) = controller(
            ChatUiState(inputState = ChatInputState(input = "x", hasDraft = false, inputHistoryIndex = 0)),
        )
        controller.updateInput("y")
        assertEquals("y", accessor.snapshot.input)
        assertFalse("用户键入不应点亮草稿标记", accessor.snapshot.hasDraft)
        assertEquals(null, accessor.snapshot.inputHistoryIndex)
    }

    @Test
    fun `updateInput preserves restored draft flag`() {
        // 从持久化恢复的草稿（hasDraft=true）在用户继续编辑时不应被当成“新输入”而洗掉，
        // 也不应反过来把普通输入升格为草稿——两边都保持各管各的。
        val (controller, accessor) = controller(
            ChatUiState(inputState = ChatInputState(input = "恢复的草稿", hasDraft = true)),
        )
        controller.updateInput("恢复的草稿改")
        assertEquals("恢复的草稿改", accessor.snapshot.input)
        assertTrue("仍是草稿内容", accessor.snapshot.hasDraft)
    }

    @Test
    fun `updateInput with blank text clears input`() {
        val (controller, accessor) = controller(
            ChatUiState(inputState = ChatInputState(input = "x", hasDraft = true)),
        )
        controller.updateInput("")
        assertEquals("", accessor.snapshot.input)
        // v2.6.7: 清空输入不再改动草稿标记（草稿的生死由持久化层管，不由键入管）。
        assertTrue("清空文本不重置草稿来源标记", accessor.snapshot.hasDraft)
    }

    @Test
    fun `enqueuePendingSend moves input and images into queue`() {
        val (controller, accessor) = controller(
            ChatUiState(inputState = ChatInputState(input = "hello", pendingImages = listOf("img1"))),
        )
        controller.enqueuePendingSend()
        val queue = accessor.snapshot.sendQueue
        assertEquals(1, queue.size)
        assertEquals("hello", queue[0].text)
        assertEquals(listOf("img1"), queue[0].images)
        assertEquals("", accessor.snapshot.input)
        assertTrue(accessor.snapshot.pendingImages.isEmpty())
    }

    @Test
    fun `enqueuePendingSend ignores blank input without images`() {
        val (controller, accessor) = controller(
            ChatUiState(inputState = ChatInputState(input = "   ", pendingImages = emptyList())),
        )
        controller.enqueuePendingSend()
        assertTrue(accessor.snapshot.sendQueue.isEmpty())
    }

    @Test
    fun `navigateInputHistory walks older then newer`() {
        val (controller, accessor) = controller(
            ChatUiState(inputState = ChatInputState(input = "", inputHistory = listOf("recent", "older"))),
        )
        controller.navigateInputHistory(-1)
        assertEquals("recent", accessor.snapshot.input)
        assertEquals(0, accessor.snapshot.inputHistoryIndex)
        controller.navigateInputHistory(-1)
        assertEquals("older", accessor.snapshot.input)
        assertEquals(1, accessor.snapshot.inputHistoryIndex)
    }

    @Test
    fun `clearPendingQueue empties queue`() {
        val (controller, accessor) = controller(
            ChatUiState(inputState = ChatInputState(sendQueue = listOf(PendingMessage("a"), PendingMessage("b")))),
        )
        controller.clearPendingQueue()
        assertTrue(accessor.snapshot.sendQueue.isEmpty())
    }

    @Test
    fun `sendPendingSend dequeues and invokes send callback`() {
        var sent: Triple<String, List<String>, String>? = null
        val (controller, accessor) = controller(
            ChatUiState(
                inputState = ChatInputState(sendQueue = listOf(PendingMessage("hi", listOf("p")))),
                sessionState = ChatSessionState(currentSessionId = "s1"),
            ),
            onSend = { t, imgs, sid -> sent = Triple(t, imgs, sid) },
        )
        controller.sendPendingSend(0)
        assertTrue(accessor.snapshot.sendQueue.isEmpty())
        assertEquals(Triple("hi", listOf("p"), "s1"), sent)
    }

    // ── P0-7: 待发队列"单独发送"护栏 ──

    @Test
    fun `sendPendingSend in agent mode uses agent session id`() {
        var sent: Triple<String, List<String>, String>? = null
        val (controller, accessor) = controller(
            ChatUiState(
                inputState = ChatInputState(sendQueue = listOf(PendingMessage("agent msg"))),
                sessionState = ChatSessionState(currentSessionId = "task-session"),
                agentState = io.zer0.muse.ui.ChatAgentState(isAgentMode = true, agentSessionId = "agent-session"),
            ),
            onSend = { t, imgs, sid -> sent = Triple(t, imgs, sid) },
        )
        controller.sendPendingSend(0)
        assertTrue(accessor.snapshot.sendQueue.isEmpty())
        // P0-7: Agent 模式必须发到 agentSessionId,不得串到任务会话
        assertEquals(Triple("agent msg", emptyList<String>(), "agent-session"), sent)
    }

    @Test
    fun `sendPendingSend while streaming is blocked and message stays queued`() {
        var sent = false
        val (controller, accessor) = controller(
            ChatUiState(
                inputState = ChatInputState(sendQueue = listOf(PendingMessage("queued"))),
                sessionState = ChatSessionState(currentSessionId = "s1"),
                streamState = io.zer0.muse.ui.ChatStreamState(isStreaming = true),
            ),
            onSend = { _, _, _ -> sent = true },
        )
        controller.sendPendingSend(0)
        // P0-7: 流式中禁止"单独发送"(会 cancel 当前生成),消息保留在队列
        assertFalse("流式中单独发送不得触发发送", sent)
        assertEquals("流式中单独发送不得出队", 1, accessor.snapshot.sendQueue.size)
    }
}
