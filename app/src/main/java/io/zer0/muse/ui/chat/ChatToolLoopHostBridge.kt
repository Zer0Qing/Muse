package io.zer0.muse.ui.chat

import io.zer0.ai.ChatService
import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.chat.rewrite.ConversationEventDraft
import io.zer0.muse.data.chat.rewrite.ConversationService
import io.zer0.muse.data.chat.rewrite.MessageCommit
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.session.ConversationSessionManager
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.ui.ChatErrorType
import kotlin.uuid.Uuid

/**
 * v2.x 重构 S1: [ChatToolLoopRunner] 访问 ChatViewModel 的窄桥接口。
 *
 * 存在的理由:工具循环体(1392 行)整体搬出后,仍需要访问原 ViewModel 的少量
 * 成员与方法。用显式接口把这些访问集中声明,而不是把 ViewModel 整体传进去 ——
 * 依赖面可见、可测、可替身。
 *
 * 迁移性质:纯抽取。本接口所有方法均为对原 VM 成员/方法的**直接转发**,
 * 不改变任何语义。ChatViewModel 以 override 形式实现本接口,不写转发代码。
 */
@Suppress("TooManyFunctions", "LongParameterList", "ComplexInterface")
internal interface ChatToolLoopHostBridge {

    // ── 状态 ───────────────────────────────────────────────────────────

    /** 原 `_state` / `_messages` 的持有者。 */
    val stateStore: ChatStateStore

    /** 原 `generationState`(toolAssistantId / activeToolSessionId / toolMediaMessages)。 */
    val generationState: io.zer0.muse.ui.chat.ChatGenerationState

    // ── 依赖(原构造器字段,VM 侧 override 暴露)────────────────────────

    val appContext: android.content.Context
    val settings: SettingsRepository
    val sessionRepository: SessionRepository
    val chatService: ChatService
    val sessionManager: ConversationSessionManager
    val notificationManager: MuseNotificationManager
    val conversationService: ConversationService
    val messageCommit: MessageCommit
    val artifactRepository: io.zer0.muse.data.artifact.ArtifactRepository
    val chatGenerationManager: io.zer0.muse.schedule.ChatGenerationManager
    val sessionPermissionStore: io.zer0.muse.tools.SessionPermissionStore
    val toolOrchestrator: io.zer0.muse.tools.ToolOrchestrator

    // ── 原 VM 方法转发(签名与 VM 侧一一对应)──────────────────────────

    fun addError(type: ChatErrorType, message: String, isRecoverable: Boolean = true)
    fun classifyErrorType(message: String, throwable: Throwable? = null): ChatErrorType
    fun clearStreamingStateIfLatest(state: StreamRunState, finalPhase: io.zer0.muse.ui.ChatStreamPhase? = null): Boolean
    fun collectStickyToolNames(history: List<UIMessage>): Set<String>
    fun currentSessionIdForApproval(): String?
    fun nextToolGenerationToken(): Long
    fun streamFlushIntervalMs(builderLength: Int): Long
    fun canUseToolModelForRound(history: List<UIMessage>, toolModel: Model): Boolean
    fun resumeOverlapToDrop(duplicateRemaining: String, delta: String): Int
    fun shouldReplaceOnResumeRewrite(duplicateTotal: Int, consumedChars: Int, minOverlap: Int = 6): Boolean
    fun shouldReplaceOnResumeSupersede(original: String?, attemptText: String?, currentContent: String): Boolean
    suspend fun persistCheckpointAndReleaseOutbox(
        state: StreamRunState,
        sessionId: String,
        userMessageId: String,
        assistantMessageId: String,
        content: String,
        createdAt: Long,
    )
    suspend fun persistCurrentAssistant(sessionId: String, assistantId: Uuid, msg: UIMessage? = null)
    suspend fun persistToolMessageMedia(sessionId: String?, assistantId: Uuid)
    suspend fun recordConversationShadow(event: ConversationEventDraft)
    suspend fun requestToolApprovalForSession(
        sessionId: String,
        toolName: String,
        toolCallId: String,
        argsPreview: String,
        args: Map<String, Any?>,
    ): ToolApprovalState
    fun updateAssistant(
        id: Uuid,
        content: String,
        reasoning: String? = null,
        imageBase64List: List<String>? = null,
        imageUrls: List<String>? = null,
        isStreaming: Boolean = false,
    )
    suspend fun updateAssistantWithVisualTransform(
        assistantId: Uuid,
        content: String,
        reasoning: String? = null,
        isStreaming: Boolean = false,
    )
    suspend fun updateContextTokenCount()
    suspend fun waitForNetworkRecovery(timeoutMs: Long): Boolean

    /** 原 `taskCardCoordinator`。 */
    val generationController: ChatGenerationController
    val coroutineScope: kotlinx.coroutines.CoroutineScope
    val accessor: ChatStateAccessor
    val taskCardCoordinator: ChatTaskCardCoordinator

    /** 原 `messageController`。 */
    val messageController: ChatMessageController
}
