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

    /**
     * 本轮累积识别文本(本轮 = 从用户开始录音到点「结束」之间)。
     *
     * ASR 会话可能因服务端静音收尾/断线而中途结束,结束前已识别的文字不能丢:每次回调都并入
     * 这里,重开录音后继续累积,用户点「结束」时整段提交。UI 也直接展示这段文本。
     */
    val transcript = MutableStateFlow("")

    val aiReply = MutableStateFlow("")

    /** 轻提示(如「没听清,再说一次」)：只在 LISTENING 且未在录音时展示，录音起来后清除。 */
    val hint = MutableStateFlow<String?>(null)

    @Volatile
    var job: Job? = null

    /** 并入一次 ASR 回调文本(整段或增量,按 [mergeTranscript] 去重)。 */
    fun accumulateTranscript(incoming: String) {
        transcript.value = mergeTranscript(transcript.value, incoming)
    }

    /**
     * 点「结束」时的提交文本:本轮累积 + ASR 收尾状态里携带的末段文本(重叠部分去重)。
     *
     * 收尾文本可能与已并入的内容重复(回调与状态更新是同一次识别的两个通道),合并规则保证幂等。
     */
    fun commitText(asrSessionFinal: String): String = mergeTranscript(transcript.value, asrSessionFinal)

    /** 清空本轮累积:提交成功、用户取消、退出语音对话时调用。 */
    fun clearTranscript() {
        transcript.value = ""
    }
}
