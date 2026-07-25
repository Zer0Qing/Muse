package io.zer0.muse.ui.chat

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

    /** 协程作用域(launch 用),由实现方提供 viewModelScope。 */
    val coroutineScope: kotlinx.coroutines.CoroutineScope
}
