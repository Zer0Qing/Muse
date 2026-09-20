package io.zer0.muse.ui.chat

import io.zer0.ai.core.UIMessage
import io.zer0.muse.ui.ChatUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Phase 3 测试夹具:内存实现的 [ChatStateAccessor]。
 *
 * 与各 Controller 测试内的私有 FakeAccessor 等价,但:
 *  - `update` 加锁,允许重试协程与测试线程并发读写;
 *  - 可注入自定义 [CoroutineScope],测试可主动取消以验证取消传播。
 */
class InMemoryChatStateAccessor(
    initial: ChatUiState = ChatUiState(),
    scope: CoroutineScope? = null,
) : ChatStateAccessor {

    private val ownScope: CoroutineScope = scope ?: CoroutineScope(Dispatchers.Unconfined)
    private val lock = Any()

    @Volatile
    private var state: ChatUiState = initial

    override val snapshot: ChatUiState get() = state

    override fun update(transform: (ChatUiState) -> ChatUiState) {
        synchronized(lock) {
            state = transform(state)
        }
    }

    override val messagesSnapshot: List<UIMessage> get() = emptyList()

    override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) = Unit

    override val coroutineScope: CoroutineScope get() = ownScope
}
