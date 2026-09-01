package io.zer0.muse.ui.chat

import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.chat.ConversationTree
import io.zer0.muse.ui.ChatUiState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * v1.x: ChatViewModel 的共享状态容器。
 *
 * 承载 _state / _messages / _conversationTree 三份 StateFlow,使各 controller
 * 不必持有 ChatViewModel 也能读写同一份事实源。ChatViewModel 仍通过只读
 * StateFlow 对外暴露,并通过 getter 委托到本容器的 MutableStateFlow,保持
 * 既有 `_state.xxx` / `_messages.xxx` / `_conversationTree.xxx` 引用不变。
 */
class ChatStateStore {
    val state = MutableStateFlow(ChatUiState())
    val messages = MutableStateFlow<List<UIMessage>>(emptyList())
    val conversationTree = MutableStateFlow(ConversationTree())
}
