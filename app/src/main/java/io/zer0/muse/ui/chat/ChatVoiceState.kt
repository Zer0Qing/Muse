package io.zer0.muse.ui.chat

import io.zer0.muse.ui.speech.VoiceConversationState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * v1.x: 语音对话模式(录音 → 识别 → 思考 → 播报循环)的共享状态容器。
 *
 * 承载状态/转写/AI 回复三个 StateFlow 与循环观察协程引用。ChatViewModel 通过
 * getter/setter 委托到本容器,保持既有 `_voiceConversationState.xxx` 等引用不变,
 * 为把 startVoiceConversation/stopVoiceConversation 等整簇迁入 ChatMediaController 铺路。
 */
internal class ChatVoiceState {
    val state = MutableStateFlow(VoiceConversationState.IDLE)
    val transcript = MutableStateFlow("")
    val aiReply = MutableStateFlow("")

    @Volatile
    var job: Job? = null
}
