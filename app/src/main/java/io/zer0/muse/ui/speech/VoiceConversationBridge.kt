package io.zer0.muse.ui.speech

import kotlinx.coroutines.flow.StateFlow

/**
 * 语音对话与宿主会话之间的最小桥接契约。
 *
 * 语音界面只依赖本契约，不直接持有具体聊天 ViewModel；实现方负责把识别文本送入会话，
 * 并把生成状态与最新回复暴露为可观察状态。
 */
interface VoiceConversationBridge {
    /** 当前助手名称，用于语音全屏页的标题。 */
    val assistantName: StateFlow<String>

    /** 当前是否仍在生成回复。 */
    val isGenerating: StateFlow<Boolean>

    /** 最新一条助手回复正文，用于生成完成后朗读与界面展示。 */
    val latestAssistantReply: StateFlow<String>

    /** 把一段已识别的语音文本送入会话。 */
    fun sendVoiceUtterance(text: String)

    /** 停止当前前台生成，不影响后台任务。 */
    fun stopGeneration()
}
