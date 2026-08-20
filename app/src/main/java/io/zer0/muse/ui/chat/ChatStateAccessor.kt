package io.zer0.muse.ui.chat

import io.zer0.ai.core.UIMessage
import io.zer0.muse.ui.ChatUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * v1.105 ChatViewModel 拆分: 让 Coordinator 能读写 state 的窄接口。
 *
 * 不直接传整个 ChatViewModel,避免 Coordinator 反向依赖宿主。
 * Coordinator 只需要:
 *  - 读当前 state 快照 [snapshot]
 *  - 原子更新 state [update]
 *  - 协程作用域 [coroutineScope](由实现方提供 viewModelScope)
 *
 * 实现方(ChatViewModel)持有 [MutableStateFlow] 并实现本接口;
 * 各 Coordinator 在构造函数接收本接口,不直接持有 ViewModel。
 */
interface ChatStateAccessor {
    /** 当前 state 快照(只读)。 */
    val snapshot: ChatUiState

    /** 原子更新 state(传入 transform,返回新 state)。 */
    fun update(transform: (ChatUiState) -> ChatUiState)

    /** B2-01: 当前消息列表快照(只读)。 */
    val messagesSnapshot: List<UIMessage>

    /** B2-01: 原子更新消息列表。 */
    fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>)

    /** 协程作用域(launch 用),由实现方提供 viewModelScope。 */
    val coroutineScope: kotlinx.coroutines.CoroutineScope
}


/**
 * 原子追加输入文本，避免异步弹窗/OCR/ASR 回调使用旧快照覆盖用户刚输入的内容。
 */
fun ChatStateAccessor.appendInputAtomically(insertion: String, separator: String = "\n\n") {
    if (insertion.isEmpty()) return
    update { state ->
        val current = state.input
        val merged = if (current.isBlank()) insertion else current + separator + insertion
        state.copy(input = merged)
    }
}
