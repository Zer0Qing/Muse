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
    fun `updateInput clears draft and history index`() {
        val (controller, accessor) = controller(
            ChatUiState(inputState = ChatInputState(input = "x", hasDraft = true, inputHistoryIndex = 0)),
        )
        controller.updateInput("y")
        assertEquals("y", accessor.snapshot.input)
        assertFalse(accessor.snapshot.hasDraft)
        assertEquals(null, accessor.snapshot.inputHistoryIndex)
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
}
