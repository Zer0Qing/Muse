package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.ai.core.UIMessage
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.data.chat.rewrite.ConversationService
import io.zer0.muse.data.milestone.MilestoneChecker
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.mcp.McpRegistry
import io.zer0.muse.rag.RagService
import io.zer0.muse.schedule.UserActivityProfile
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.transformer.SystemPromptAssembler
import io.zer0.muse.transformer.TransformerPipeline
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.SessionMemoryCache
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * v1.x: 生成/发送管线的依赖 bundle。
 *
 * 把 send/enqueueSend/launchStream 及其 helper 共用的服务与共享状态收口成单一构造参数,
 * 避免 ChatGenerationController 构造参数爆炸。ChatViewModel 侧仍需提供少数方法回调
 * (addError/generateImage/launchStream),因为它们是 facada 内私有实现,用回调而非反向依赖。
 */
@Suppress("LongParameterList")
internal class GenerationDeps(
    val accessor: ChatStateAccessor,
    val stateStore: ChatStateStore,
    val generationState: ChatGenerationState,
    val settings: SettingsRepository,
    val sessionRepository: SessionRepository,
    val appContext: Context,
    val activityProfile: UserActivityProfile,
    val auditLogger: AuditLogger,
    val idListJson: Json,
    val messageController: ChatMessageController,
    val addError: (ChatErrorType, String, Boolean) -> Unit,
    val generateImage: (prompt: String, sessionId: String) -> Unit,
    val sessionMemoryCache: SessionMemoryCache,
    val systemPromptCache: SystemPromptCache,
    val toolRegistry: ToolRegistry,
    val systemPromptAssembler: SystemPromptAssembler,
    val assistantRepository: AssistantRepository,
    val ragService: RagService,
    val conversationService: ConversationService,
    val streamCoordinator: ChatStreamCoordinator,
    val mcpRegistry: McpRegistry?,
    val milestoneChecker: MilestoneChecker?,
    val runToolLoop: suspend (StreamRunState) -> Boolean,
    val persistInterruptedAssistant: suspend (
        sessionId: String,
        partialMsg: UIMessage?,
        expectedAssistantId: Uuid?,
        durationMs: Long?,
    ) -> Unit,
    val classifyErrorType: (message: String, throwable: Throwable?) -> ChatErrorType,
    val transformerPipeline: TransformerPipeline,
    val memoryTicker: MemoryTicker,
    val refreshContextInfo: suspend () -> Unit,
    val triggerAutoCompress: suspend (sessionId: String) -> Unit,
    /** 自动任务路由;返回 true 表示已完成委派,本轮不再启动普通模型生成。 */
    val maybeAutoRoute: suspend (text: String, assistantMessageId: Uuid, sessionId: String) -> Boolean,
    /**
     * v2.6.9: 用户开口时的旁路通知（主动消息反馈闭环）。
     *
     * 拿成回调而不是直接依赖 ProactiveScheduler：那种依赖会让 ChatViewModel
     * 与调度器互引，也会把主动消息的存储细节泄到会话层。
     * 默认空实现，未装配时行为与旧版一致。
     *
     * 非挂起：调用方是发送热路径，不能为了记一笔反馈而阻塞。
     * 实现方自己决定在哪里起协程。
     */
    val onUserSpoke: (sessionId: String) -> Unit = {},
)
