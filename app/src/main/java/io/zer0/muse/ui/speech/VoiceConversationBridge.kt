package io.zer0.muse.ui.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 语音对话与宿主会话之间的最小桥接契约。
 *
 * 语音界面只依赖本契约，不直接持有具体聊天 ViewModel，因此同一套语音交互可以同时
 * 服务普通聊天与 Agent 主舞台。实现方负责把识别文本送入会话，并把生成状态与最新回复
 * 暴露为可观察状态。
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

/**
 * 以数据流和回调装配的通用桥接实现。
 *
 * 不持有 Android Context，也不启动协程职责之外的资源，便于单测直接验证语音文本路由、
 * 生成状态与回复状态。调用方负责提供生命周期正确的 [scope]。
 */
@Suppress("LongParameterList")
class AgentVoiceBridge(
    scope: CoroutineScope,
    assistantNameFlow: Flow<String>,
    isGeneratingFlow: Flow<Boolean>,
    latestAssistantReplyFlow: Flow<String>,
    initialAssistantName: String = "",
    initialGenerating: Boolean = false,
    initialLatestAssistantReply: String = "",
    private val onSendUtterance: (String) -> Unit,
    private val onStopGeneration: () -> Unit,
) : VoiceConversationBridge {

    override val assistantName: StateFlow<String> =
        assistantNameFlow.stateIn(scope, SharingStarted.Eagerly, initialAssistantName)

    override val isGenerating: StateFlow<Boolean> =
        isGeneratingFlow.stateIn(scope, SharingStarted.Eagerly, initialGenerating)

    override val latestAssistantReply: StateFlow<String> =
        latestAssistantReplyFlow.stateIn(scope, SharingStarted.Eagerly, initialLatestAssistantReply)

    override fun sendVoiceUtterance(text: String) {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return
        onSendUtterance(cleaned)
    }

    override fun stopGeneration() {
        onStopGeneration()
    }
}
