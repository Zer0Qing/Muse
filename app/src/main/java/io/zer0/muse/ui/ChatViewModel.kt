package io.zer0.muse.ui

import android.content.Context
import android.net.Uri
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.ai.ChatService
import io.zer0.ai.ProviderRegistry
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelContextWindowRegistry
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderError
import io.zer0.ai.core.ProviderException
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.RagCitation
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.limitContextWithContext
import io.zer0.ai.core.inferFromMessage
import io.zer0.ai.image.ImageService
import io.zer0.ai.registry.ModelRegistry
import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.util.ErrorMessages
import io.zer0.muse.data.ExperimentsConfig
import io.zer0.muse.data.MultiAgentConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.artifact.ArtifactExtractor
import io.zer0.muse.network.NetworkMonitor
import io.zer0.muse.data.artifact.ArtifactRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.data.milestone.MilestoneChecker
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.data.quickmsg.QuickMessageEntity
import io.zer0.muse.data.quickmsg.QuickMessageRepository
import io.zer0.muse.data.session.SearchResult
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.doc.DocumentParser
import io.zer0.muse.R
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.schedule.ChatGenerationService
import io.zer0.muse.schedule.ConversationEndType
import io.zer0.muse.schedule.UserActivityProfile
import io.zer0.muse.tools.ToolApprovalPolicy
import io.zer0.muse.tools.AgentRouter
import io.zer0.muse.tools.DelegationContract
import io.zer0.muse.tools.DelegationContextBuilder
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.ToolConfigStore
import io.zer0.muse.tools.ToolPermissionResolver
import io.zer0.muse.privacy.PiiGuard
import io.zer0.muse.tools.SessionPermissionMode
import io.zer0.muse.tools.SessionPermissionStore
import io.zer0.muse.tools.ToolLoopHost
import io.zer0.muse.tools.StreamRoundParams
import io.zer0.muse.tools.StreamRoundResult
import io.zer0.muse.tools.ToolLoopParams
import io.zer0.muse.tools.ToolLoopResult
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.chat.PendingToolCallStore
import io.zer0.muse.data.chat.ConversationTree
import io.zer0.muse.data.chat.ConversationTreeSnapshotStore
import io.zer0.muse.data.chat.mergeRebuildMessages
import io.zer0.muse.transformer.ContextCompressTransformer
import io.zer0.muse.transformer.LorebookTransformer
import io.zer0.muse.transformer.MemoryInjectionTransformer
import io.zer0.muse.transformer.PromptInjectionTransformer
import io.zer0.muse.transformer.TemplateTransformer
import io.zer0.muse.transformer.ThinkTagTransformer
import io.zer0.muse.transformer.TimeReminderTransformer
import io.zer0.muse.transformer.TransformContext
import io.zer0.muse.transformer.TransformerPipeline
import io.zer0.muse.ui.chat.ChatStateAccessor
import io.zer0.muse.ui.chat.ChatAudioCoordinator
import io.zer0.muse.ui.chat.ChatDocumentCoordinator
import io.zer0.muse.ui.chat.ChatExportCoordinator
import io.zer0.muse.ui.chat.ChatMiscCoordinator
import io.zer0.muse.ui.chat.ChatStreamCoordinator
import io.zer0.muse.ui.chat.ChatTaskCardCoordinator
import io.zer0.muse.ui.chat.ImageGenCoordinator
import io.zer0.muse.ui.chat.buildQuotedContent
import io.zer0.muse.ui.chat.SlashCommand
import io.zer0.muse.ui.chat.StreamRunState
import io.zer0.muse.ui.chat.TokenCountSnapshot
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.speech.TtsManager
import io.zer0.muse.ui.speech.PlaybackState
import io.zer0.muse.ui.speech.VoiceConversationState
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.util.TokenEstimator
import io.zer0.muse.util.retryOnNetworkError
import io.zer0.muse.web.WebSearchConfig
import io.zer0.muse.web.WebSearchService
import io.zer0.muse.web.SearchRateLimitException
import io.zer0.muse.asr.ASRController
import io.zer0.muse.asr.ASRState
import io.zer0.muse.asr.AsrProviderType
import io.zer0.muse.asr.DashScopeAsrController
import io.zer0.muse.asr.StepAsrController
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * v0.49: 聊天错误信息(支持多错误并存)。
 *
 * @param id 错误唯一 id(用于 dismiss)
 * @param type 错误类型
 * @param message 错误消息
 * @param timestamp 发生时间
 * @param isRecoverable 是否可恢复(如网络错误可重试,API key 错误需用户处理)
 */
data class ChatError(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: ChatErrorType,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isRecoverable: Boolean = true,
)

/** v0.49: 错误类型枚举,用于分类展示与处理策略。 */
enum class ChatErrorType {
    NETWORK,        // 网络错误
    API_KEY,        // API key 无效
    RATE_LIMIT,     // 限流
    MODEL_ERROR,    // 模型返回错误
    TOOL_ERROR,     // 工具执行错误
    UNKNOWN,        // 未知错误
}

/**
 * 聊天页状态。
 *
 * # v1.0.20: 字段分组说明(渐进式拆分,控制风险)
 *
 * 当前 [ChatUiState] 含数十个字段,任一变化都触发 state 新值,引发意外重组。
 * 完整拆分(把字段挪到子状态 data class)改动面极大,所有访问点都要改,风险高。
 * 故采用渐进式:保持顶层结构不变,按变化频率分组注释,UI 层用 derivedStateOf 收窄重组范围。
 *
 * ## 高频变化字段(UI 重组敏感,建议用 derivedStateOf 包裹读取)
 *  - [input]:用户每次按键都变化
 *  - [isStreaming]:流式开始/结束变化
 *  - [isWaitingFirstToken]:首 token 到达前后变化
 *  - [visionProgress]:视觉分析每完成一张图变化
 *  - [isOcrProcessing] / [toolProgressMessage]:短暂等待阶段变化
 *  - [isSpeaking] / [speakingMessageId]:TTS 朗读状态变化
 *  - [asrState]:ASR 流式状态变化
 *  - [messages]:流式过程中每个 delta 都更新(已被 StreamController 节流)
 *
 * ## 低频变化字段(配置/会话/列表等,重组影响小)
 *  - [sessions] / [archivedSessions] / [folders]:会话列表变化
 *  - [assistants] / [currentAssistant]:助手配置变化
 *  - [providers] / [activeProviderId] / [selectedModelId]:Provider 配置变化
 *  - [chatPreferences] / [mediaConfig] / [webSearchConfig]:设置变化
 *  - [favoriteMessages] / [quickMessages] / [lorebooks]:列表数据变化
 *
 * ## 派生/瞬态字段(由其他字段推导,不直接驱动重组)
 *  - [isConfigured]:由 providers 派生
 *  - [error]:由 errors.firstOrNull() 派生
 *  - [toast]:一次性 Toast,UI 消费后清空
 *
 * 后续完整拆分路线图(本次不做,标注 TODO):
 *  - TODO(state-split): 把高频字段抽到 `ChatUIState`(input/isStreaming/isWaitingFirstToken/visionProgress)
 *  - TODO(state-split): 把工具相关抽到 `ToolsState`(toolCallHistory/pendingToolApprovals/taskCards)
 *  - TODO(state-split): 把 Agent 相关抽到 `ChatAgentState`(isAgentMode/agentSessionId/delegationChain)
 *
 * @param messages 完整消息列表(含占位的 assistant 流式消息)
 * @param input 当前输入框文本
 * @param isStreaming 是否正在流式输出
 * @param errors v0.49: 错误信息列表(支持多错误并存),空表示无错误
 * @param isConfigured 是否已配置 Provider(决定显示"去设置"引导)
 * @param currentSessionId 当前会话 id(null 表示未初始化)
 * @param sessions 全部会话列表(侧栏用,按 updatedAt 降序)
 * @param isDrawerOpen 侧栏是否展开
 * @param searchQuery 搜索框文本
 * @param searchResults 搜索结果列表(空列表表示无搜索或无结果)
 * @param isSearching 是否正在搜索
 * @param isDrawMode 绘图模式(P5-G):开启后 send 调用 ImageService 而非 ChatService
 * @param isGeneratingImage 是否正在生成图片(P5-G)
 * @param isTranslating 是否正在翻译(P5-F)
 * @param translatingMessageId 正在翻译的消息 id(null 表示无)
 * @param assistants 全部 Assistant 列表(Phase 8.2 侧栏选择器用)
 * @param currentAssistant 当前会话绑定的 Assistant(Phase 8.2,影响 systemPrompt/模型/工具/记忆)
 * @param favoriteMessages 跨会话收藏的消息列表(Phase 8.3,收藏面板用)
 */
data class ChatStreamState(
    val isStreaming: Boolean = false,
    /**
         * v1.0.3: 流式启动后是否仍在等待首个 token(含 ContentDelta / ReasoningDelta)。
         *
         * - true: 处于"思考中"阶段,UI 显示 ShimmerBubble 骨架屏 + "思考中"文字
         * - false: 已收到首 token,进入"正在写"阶段,UI 显示 StreamingCursor 光标 + 流式文本
         *
         * 与 [isStreaming] 的关系:
         *  - 发送时:isStreaming=true, isWaitingFirstToken=true
         *  - 首 token 到达:isStreaming=true, isWaitingFirstToken=false
         *  - 流式结束/出错/停止:isStreaming=false, isWaitingFirstToken=false
         *
         * 区分两阶段的好处:
         *  1. 首 token 到达后立即让 ShimmerBubble 消失,避免"loading → 突然大量文字"的视觉断层
         *  2. UI 可以分别给两阶段不同的视觉反馈(骨架屏 vs 流式光标)
         *  3. reasoning-only 阶段(模型只输出思考链,content 仍空)不会被误判为"还在等待"
         */
        val isWaitingFirstToken: Boolean = false,
    /**
         * v1.0.4: 视觉辅助分析进度(null=未在分析)。
         *
         * 当纯文本模型需要通过视觉辅助分析图片时,UI 可据此显示"正在分析图片 2/4…"。
         * 与 [isWaitingFirstToken] 配合:视觉分析期间 ShimmerBubble 显示视觉进度文字。
         */
        val visionProgress: io.zer0.muse.vision.VisionProgress? = null,
    /**
         * v1.138: 已视觉辅助处理过的用户消息 ID 集合。
         *
         * 当主模型不支持视觉、通过 VisionBridge 分析图片后,将用户消息 ID 加入此集合。
         * UI 层据此在图片下方显示"辅助视觉 · 已分析"标签,让用户知道图片经过了视觉辅助处理。
         */
        val visionAssistedMessageIds: Set<String> = emptySet(),
    /**
         * v1.0.4 (P0): OCR 识别进度标志。
         *
         * 用户从相册选图并以 OCR 模式识别文字时,识别期间(1-3s)设置 true,
         * ShimmerBubble 显示"正在识别图片文字…"。
         */
        val isOcrProcessing: Boolean = false,
    /**
         * v1.0.4 (P0): 工具调用恢复时的进度文本(断点续传路径用)。
         *
         * 当用户进入有未完成工具调用的会话并点击恢复时,ShimmerBubble 显示此文本。
         * 流式工具调用路径已通过 TaskCard 显示进度,无需此字段。
         */
        val toolProgressMessage: String? = null,
    /**
         * 断点续传:当前会话未完成的工具调用数量。
         *
         * 大于 0 时 ChatScreen 顶部展示恢复 Banner(恢复执行 / 丢弃)。
         * 由 [ChatViewModel.switchSession] / [ChatViewModel.resumePendingToolCalls] /
         * [ChatViewModel.discardPendingToolCalls] 维护。
         */
        val pendingToolCallCount: Int = 0,
)

data class ChatToolsState(
    val isCompressing: Boolean = false,
    val taskCards: Map<String, io.zer0.muse.ui.taskcard.TaskCardData> = emptyMap(),
    val toolCallHistory: List<ToolCallRecord> = emptyList(),
    val pendingToolApprovals: List<PendingToolApproval> = emptyList(),
)


data class ChatInputState(
    val input: String = "",
    /** Phase 8.6: 待发送的本地图片 base64 列表(无 data: 前缀)。 */
        val pendingImages: List<String> = emptyList(),
    /** v1.136 T10: 待发送的文档列表(已解析为纯文本,发送时合并到消息内容)。 */
        val pendingDocuments: List<io.zer0.muse.ui.chat.PendingDocument> = emptyList(),
    /** v1.91: ASR 状态机(流式模式)。 */
        val asrState: ASRState = ASRState(),
    /** 引用回复:当前正在回复的目标消息(仅 UI 层,发送后清空)。 */
        val replyingTo: UIMessage? = null,
    /** v1.57: 引用回复的自定义引用文本(用户可在引用卡片编辑裁剪,null 时用 replyingTo.content)。 */
        val replyQuoteOverride: String? = null,
    /**
         * v1.0.47 P5: 输入历史(本会话内已发送的消息文本,用于输入框上箭头回调)。
         *
         * 最近 N 条(默认 50),上箭头按时间倒序遍历,下箭头正向遍历。
         * 仅存内存,不持久化(会话结束清空)。
         */
        val inputHistory: List<String> = emptyList(),
    /** v1.0.47 P5: 输入历史导航索引(null=不在历史导航中,0=最近一条)。 */
        val inputHistoryIndex: Int? = null,
    /** 功能2: 当前输入是否为从 DataStore 恢复的草稿。 */
        val hasDraft: Boolean = false,
)

data class ChatSessionState(
    val currentSessionId: String? = null,
    val sessions: List<SessionEntity> = emptyList(),
    /** v1.72: 会话列表首次加载标志(避免闪空状态,DB 首次 emit 前显示 loading) */
        val isSessionsLoading: Boolean = true,
    /** v1.0.62: 会话列表加载失败信息(null=正常)。 */
        val sessionsError: String? = null,
    /** v0.45: 已归档会话列表(归档 FilterCard 用)。 */
        val archivedSessions: List<SessionEntity> = emptyList(),
    /** Phase 9.1 (M13): 全部文件夹(Drawer 按 folderId 分组渲染会话)。 */
        val folders: List<io.zer0.muse.data.session.FolderEntity> = emptyList(),
)

data class ChatAgentState(
    /** v1.28: 是否为 Agent Tab 模式(决定 send 用 agentSessionId 还是 currentSessionId)。 */
        val isAgentMode: Boolean = false,
    /** v1.28: Agent Tab 专用会话 id(独立于任务的 currentSessionId)。 */
        val agentSessionId: String? = null,
    val assistants: List<AssistantEntity> = emptyList(),
    /** v1.72: 助手列表首次加载标志(避免闪空状态) */
        val isAssistantsLoading: Boolean = true,
    val currentAssistant: AssistantEntity? = null,
    /** v1.201: 委派链路根节点(空列表表示无委派)。 */
        val delegationChain: List<io.zer0.muse.tools.DelegationChainTracker.ChainNode> = emptyList(),
    /** v1.201: 当前活跃的委派暂停请求(null 表示无待确认)。 */
        val activePauseRequest: io.zer0.muse.tools.DelegationPauseManager.PauseRequest? = null,
    /** v1.202: 当前会话活跃的后台子 agent 线程(SubagentTaskListCard 渲染用,空列表表示无)。 */
        val activeSubagentThreads: List<io.zer0.muse.data.subagent.SubagentThreadStore.ThreadEntry> = emptyList(),
    /** v1.202: 当前会话待处理(PENDING)的后台子 agent 任务(SubagentTaskListCard 渲染用)。 */
        val pendingSubagentTasks: List<io.zer0.muse.tools.DeferredResultStore.DeferredTask> = emptyList(),
    // ── P6: Agent Mode 增强 ──────────────────────────────────────────
        /** v1.0.47 P6-2: 当前模型是否为弱工具调用模型(Agent Mode 下显示降级提示)。 */
        val isWeakToolModel: Boolean = false,
    /** v1.0.47 P6-2: 弱工具模型降级提示文案(null = 非弱工具模型,不显示)。 */
        val weakToolHint: String? = null,
    /** v1.0.47 P6-3: 会话锁定(Agent Mode ON 时锁定,禁止切换助手/清空消息)。 */
        val isSessionLocked: Boolean = false,
    /** v1.0.47 P6-4: Agent Mode 提示卡片文案(null = 不显示卡片)。 */
        val agentModeHint: String? = null,
)

class ChatUiState(
    val streamState: ChatStreamState = ChatStreamState(),
    val inputState: ChatInputState = ChatInputState(),
    val sessionState: ChatSessionState = ChatSessionState(),
    val agentState: ChatAgentState = ChatAgentState(),
    val toolsState: ChatToolsState = ChatToolsState(),
    /** B7-01: 消息多选模式。 */
    val selectionMode: Boolean = false,
    /** B7-01: 已选消息 id 集合。 */
    val selectedMessageIds: Set<String> = emptySet(),
    /** v1.137 B2: 会话切换中标志 — true 时 UI 保持上一帧消息(不显示空列表),消除切换闪烁。 */
        val isSwitchingSession: Boolean = false,
    val errors: List<ChatError> = emptyList(),
    val isConfigured: Boolean = false,
    /** 是否正在拉取上游模型列表(底部模型切换面板用)。 */
        val isFetchingModels: Boolean = false,
    /** 拉取上游模型错误信息。 */
        val fetchModelsError: String? = null,
    /**
         * v1.93+: 本次 switchSession 是否命中内存 LRU 缓存(调试用)。
         *
         * - true: 命中 [SessionMemoryCache],跳过了 DB 查询,直接复用内存消息快照
         * - false: 未命中(或后台正在生成需走 DB),从 DB 分页加载
         *
         * 仅用于性能追踪与调试,不影响业务逻辑。
         */
        val memoryCacheHit: Boolean = false,
    val isDrawerOpen: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
    /**
         * v2.x: 搜索页 Tab 当前选中索引(0=会话, 1=消息内容)。
         *
         * Tab=0 沿用原有"会话标题/预览匹配 + 设置项匹配"逻辑(展示 [searchResults] + matchedSessions);
         * Tab=1 展示 [messageResults](消息内容搜索,FTS4 snippet + 点击跳转传 messageId)。
         */
        val searchTab: Int = 0,
    /** v2.x: 消息内容搜索结果(仅在 searchTab=1 时展示,由 FTS4 + snippet 生成)。 */
        val messageResults: List<SearchResult> = emptyList(),
    /** v2.x: 是否正在执行消息内容搜索(独立于 [isSearching],避免两个 Tab 互相干扰)。 */
        val isSearchingMessages: Boolean = false,
    /** v1.0.62: 搜索失败信息(null=正常),会话/消息内容 Tab 共用。 */
        val searchError: String? = null,
    /**
         * v2.x: 从搜索结果点击消息跳转时,目标消息 id。
         *
         * ChatScreen 进入会话后会读取此字段滚动到该消息并触发高亮,完成后由
         * [ChatViewModel.consumeTargetMessage] 清空(避免重复触发)。
         */
        val targetMessageId: String? = null,
    /**
         * v2.x: 搜索高亮关键词(从搜索结果跳转时携带,用于 MessageBubble 内文本高亮)。
         *
         * 仅当 [highlightedMessageId] 非空时有效;高亮结束后清空。
         */
        val searchHighlightQuery: String? = null,
    /**
         * v2.x: 当前正在短暂高亮的消息 id(从搜索结果跳转滚动定位后设置,几秒后清空)。
         *
         * ChatScreen 据此为对应 MessageBubble 传 highlightText,实现"进入会话高亮目标消息"。
         */
        val highlightedMessageId: String? = null,
    val isDrawMode: Boolean = false,
    /** v0.34: 当前绘图参数(可临时覆盖设置中的默认值)。 */
        val imageGenParams: io.zer0.ai.image.ImageGenParams = io.zer0.ai.image.ImageGenParams(),
    val isGeneratingImage: Boolean = false,
    /**
         * v1.0.4 (P1): 视频生成中标志。
         *
         * LLM 调用 generate_video 工具时设为 true,完成/失败/取消时设为 false。
         * ChatScreen 显示 VideoGenerationPlaceholder 占位卡片,与 ImageGenerationPlaceholder 对称。
         * 注:execGenerateVideo 还会通过 updateAssistant(content="正在生成视频...") 在助手消息气泡
         * 内同步进度,本字段是补充占位卡片反馈,不是替代。
         */
        val isGeneratingVideo: Boolean = false,
    val isTranslating: Boolean = false,
    val translatingMessageId: Uuid? = null,
    val favoriteMessages: List<UIMessage> = emptyList(),
    /** v1.77: 收藏列表首次加载标志(避免闪空状态) */
        val isFavoritesLoading: Boolean = true,
    /**
         * v1.104 U7: 已命名的收藏分组标签列表(去重升序,不含 NULL 未分组)。
         *
         * 由 DAO observeAllFavoriteTags 聚合,UI 据此渲染顶部 FilterChip 行。
         */
        val favoriteTags: List<String> = emptyList(),
    /**
         * v1.104 U7: 当前选中的分组筛选条件。
         *
         *  - null = 显示全部收藏
         *  - 非空字符串 = 仅显示该 tag 下的收藏
         *  - 特殊值 [FAVORITE_TAG_UNGROUPED] = 仅显示未分组(favoriteTag=null)的收藏
         *
         * FavoritesScreen 在本地对 favoriteMessages 做过滤,不在 ViewModel 预过滤,
         * 保证 favoriteMessages 始终是全量(便于"全部"chip 显示总数)。
         */
        val favoriteTagFilter: String? = null,
    /**
         * v2.0: 预设分类筛选(全部/灵感/代码/学习/自定义)。
         * 与 favoriteTagFilter 互斥,优先级高于 favoriteTagFilter。
         * null = 不按预设分类筛选,回退到 favoriteTagFilter。
         */
        val favoriteGroup: String? = null,
    /** Phase 8.4: 是否启用联网搜索(InputBar 上的开关,运行时切换)。 */
        val webSearchEnabled: Boolean = false,
    /**
         * v0.45: 当前上下文 token 占用估算(含 system prompt + 全部消息文本)。
         *
         * 由 [ChatViewModel.updateContextTokenCount] 实时更新,UI 据此渲染占用圆环。
         * 流式过程中每 50 字符更新一次(避免过于频繁),非流式时立即更新。
         */
        val contextTokenCount: Int = 0,
    /**
         * v0.45: 当前模型的上下文窗口大小(token 数)。
         *
         * 0 表示未知(模型未声明 contextWindow),UI 不显示占用圆环。
         * 切换会话 / 选择模型时由 [ChatViewModel.refreshContextMaxTokens] 刷新。
         */
        val contextMaxTokens: Int = 0,
    /**
         * v1.0.47: Token 估算开关(默认关闭)。
         *
         * 用户在设置页显式开启后,输入框显示当前输入 token 估算,
         * AI 消息底部显示 completionTokens,顶部栏显示上下文占用。
         * 关闭时不做任何 token 计算,避免 BPE 编码性能开销。
         */
        val tokenEstimateEnabled: Boolean = false,
    /** v1.0.47 P5-2: 长文本粘贴转文件开关(默认开启)。 */
        val pasteAsFileEnabled: Boolean = true,
    /** v1.0.47 P5-2: 长文本粘贴转文件阈值(字符数)。 */
        val pasteAsFileThreshold: Int = 2000,
    /** v1.0.47 P5-3: Token 计数菜单是否展开。仅 tokenEstimateEnabled=true 时可触发。 */
        val tokenCountVisible: Boolean = false,
    /** v1.0.47 P5-3: Token 计数快照(打开菜单时计算一次,避免每次按键重算 BPE)。 */
        val tokenSnapshot: TokenCountSnapshot? = null,
    /**
         * v0.45: 是否正在执行"更新记忆并压缩"。
         *
         * 手动压缩按钮点击后置 true,完成后置 false。UI 据此显示转圈 + 禁用按钮。
         */
    /** v0.39: 深度思考开关(聊天时临时启用 HIGH 推理,覆盖助手默认 reasoningLevel)。 */
        val deepThinkingEnabled: Boolean = false,
    /**
         * v1.0.47 P5-6: 深度思考级别(仅在 [deepThinkingEnabled]=true 时生效)。
         *
         * 默认 HIGH(向后兼容 v0.39 行为)。用户可点击输入栏级别胶囊在
         * LOW → MEDIUM → HIGH → XHIGH 之间循环。
         * 不支持推理的模型在 [ChatStreamCoordinator] 内自动降级为 AUTO,不会报错。
         */
        val deepThinkingLevel: ReasoningLevel = ReasoningLevel.HIGH,
    /** Phase 8.4: Web 搜索配置(用于 Settings 页编辑)。 */
        val webSearchConfig: WebSearchConfig = WebSearchConfig(),
    /** Phase 8.5: 当前会话绑定的快捷消息列表(InputBar 上方 chip 行用)。 */
        val quickMessages: List<QuickMessageEntity> = emptyList(),
    /** Phase 8.5: 当前激活的模式(用于 PromptInjection,default 表示无注入)。 */
        val currentMode: String = "default",
    /** Phase 8.5: 全部 Lorebook 条目(管理页用)。 */
        val lorebooks: List<LorebookEntity> = emptyList(),
    /** Phase 8.5: 全部 PromptInjection 条目(管理页用)。 */
        val promptInjections: List<PromptInjectionEntity> = emptyList(),
    /** Phase 8.5: 全部 QuickMessage 条目(管理页用,含 global + 各 Assistant 绑定的)。 */
        val allQuickMessages: List<QuickMessageEntity> = emptyList(),
    /** v1.58: Prompt 模板列表(预置场景提示词,从 plus 菜单进入模板库选择)。 */
        val promptTemplates: List<io.zer0.muse.data.prompttemplate.PromptTemplate> = emptyList(),
    /** Phase 8.7: TTS 是否正在朗读(用于 InputBar 禁用/MessageBubble 高亮)。 */
        val isSpeaking: Boolean = false,
    /** Phase 8.7: 正在朗读的消息 id(null 表示无)。 */
        val speakingMessageId: Uuid? = null,
    /** Phase 8.8: 任务卡映射(assistant 消息 id → TaskCardData),工具调用计划展示。 */
    /** v1.55: Agent 工作流计划映射(planId → AgentPlan),结构化任务规划展示。 */
        val agentPlans: Map<String, io.zer0.muse.ui.taskcard.AgentPlan> = emptyMap(),
    /** Phase 9.3 (M2): ASR 配置(provider/apiKey/model)。 */
        val asrConfig: io.zer0.muse.asr.AsrConfig = io.zer0.muse.asr.AsrConfig(),
    /** 阶段 5: 全部已配置 Provider(底部模型切换面板用)。 */
        val providers: List<io.zer0.ai.core.ProviderConfig> = emptyList(),
    /** 阶段 5: 当前激活 Provider id。 */
        val activeProviderId: String? = null,
    /** 阶段 5: 当前选中模型 id(null 表示回退到激活 Provider 的首个模型)。 */
        val selectedModelId: String? = null,
    /** v1.60-A: 工具模型 id(工具调用轮次使用,null 表示沿用主对话模型)。 */
        val toolModelId: String? = null,
    /** v0.31: 聊天行为偏好(从设置读取,UI 据此控制渲染与交互)。 */
        val chatPreferences: io.zer0.muse.data.ChatPreferences = io.zer0.muse.data.ChatPreferences(),
    /** v0.32: 分享模板(从设置读取,exportSessionAsMarkdown 据此过滤内容)。 */
        val shareTemplate: io.zer0.muse.data.ShareTemplateConfig = io.zer0.muse.data.ShareTemplateConfig(),
    /** v0.33: 媒体配置(从设置读取,录音/TTS 据此控制采样率/语速/音高/语言/输出方式)。 */
        val mediaConfig: io.zer0.muse.data.MediaConfig = io.zer0.muse.data.MediaConfig(),
    /**
         * v2.3: 流式结束后填充的本次回复性能摘要,UI 可以在 MessageBubble 底部显示。
          *
          * 格式:"模型:xxx | 耗时:xxms | TTFT:xxms | 速率:xx tok/s | 字符:xx | 工具调用:N | 轮次:N"。
          * 仅 debugMode=true 时填充,否则为 null。每次 launchStream 启动时重置。
          */
        val debugInfo: String? = null,
    /**
         * v0.51: 一次性 Toast 提示(模型切换等场景)。
         *
         * 用 Toast 而非 Snackbar:Snackbar 通道已被 [errors] 占用,模型切换提示不应被错误
         * 消息挤掉,所以走独立 Toast 通道。UI 用 LaunchedEffect 观察本字段,非空时弹 Toast
         * 并立即调 [ChatViewModel.clearToast] 清空(避免重组时重复弹)。
         */
        val toast: String? = null,
    /** v1.25: 多 Agent 协作配置(团队列表与总开关)。 */
        val multiAgentConfig: MultiAgentConfig = MultiAgentConfig(),
    /** v1.43: 当前选中的产物卡片(用于 ArtifactViewerDialog 弹窗)。 */
        val selectedArtifact: io.zer0.muse.data.artifact.ArtifactEntity? = null,
    /** v1.45: 列表滚动位置缓存(切页/后台后恢复,避免回到顶端)。 */
        val listFirstVisibleItemIndex: Int = 0,
    val listFirstVisibleItemScrollOffset: Int = 0,
    /** v1.45: 消息级展开状态缓存(mood/reasoning 折叠状态不因切页丢失)。 */
        val messageExpandedStates: Map<String, MessageExpandedState> = emptyMap(),
    /** v1.53-A1: 是否还有更早的历史消息可加载(上滑加载更多用)。 */
        val hasMoreHistory: Boolean = false,
    /** v1.53-A1: 是否正在加载更多历史消息(防止重复触发)。 */
        val isLoadingMore: Boolean = false,
    /**
         * v1.53-A1: 最近一次"加载更多"插入的历史条数。
         *
         * UI 监听该字段变化(>0)后,通过 [listState.scrollToItem] 跳过新插入的条数,
         * 保持用户视觉位置不跳动(原来在顶部的消息现在在该 index),然后调
         * [ChatViewModel.clearHistoryLoadCount] 清空。
         */
        val lastHistoryLoadCount: Int = 0,
    /** v1.94: 当前会话的工具调用记录(用于 InputBar 动态胶囊展示)。 */
    /** v2.3: 任务模型路由开关(开启后根据输入内容自动推荐模型)。 */
        val taskRoutingEnabled: Boolean = false,
    /** 待审批的工具调用列表(ToolApprovalCard 用)。 */
    /**
         * v1.0.47 P1: 上下文压缩状态(流式 Compaction UI)。
         *
         * 压缩进行中时 ChatScreen 顶部显示"正在压缩上下文..."进度条,
         * 压缩完成后显示"已压缩 N 条历史"短暂提示。
         * null 表示无压缩活动。
         */
        val compactionState: CompactionState? = null,
    /** P3: 当前会话的工具权限模式(TRUSTED/ASK/STRICT),默认 ASK。 */
        val sessionPermissionMode: SessionPermissionMode = SessionPermissionMode.ASK,
    /**
         * v1.0.16: 本次应用开启期间批准全部工具调用(内存态,不持久化)。
         *
         * 用户在 ToolApprovalCard 勾选"本次开启期间批准全部工具"后置 true,
         * 之后所有工具调用直接 Auto 执行,不再弹审批卡片。
         * 应用冷启动后 ChatUiState 重建,自动回到 false。
         */
        val appRunAllowAllTools: Boolean = false,
) {
    val isStreaming: Boolean get() = streamState.isStreaming
    val isWaitingFirstToken: Boolean get() = streamState.isWaitingFirstToken
    val visionProgress: io.zer0.muse.vision.VisionProgress? get() = streamState.visionProgress
    val visionAssistedMessageIds: Set<String> get() = streamState.visionAssistedMessageIds
    val isOcrProcessing: Boolean get() = streamState.isOcrProcessing
    val toolProgressMessage: String? get() = streamState.toolProgressMessage
    val pendingToolCallCount: Int get() = streamState.pendingToolCallCount
    val isCompressing: Boolean get() = toolsState.isCompressing
    val taskCards: Map<String, io.zer0.muse.ui.taskcard.TaskCardData> get() = toolsState.taskCards
    val toolCallHistory: List<ToolCallRecord> get() = toolsState.toolCallHistory
    val pendingToolApprovals: List<PendingToolApproval> get() = toolsState.pendingToolApprovals
    val input: String get() = inputState.input
    val hasDraft: Boolean get() = inputState.hasDraft
    val inputHistory: List<String> get() = inputState.inputHistory
    val inputHistoryIndex: Int? get() = inputState.inputHistoryIndex
    val pendingImages: List<String> get() = inputState.pendingImages
    val pendingDocuments: List<io.zer0.muse.ui.chat.PendingDocument> get() = inputState.pendingDocuments
    val replyingTo: UIMessage? get() = inputState.replyingTo
    val replyQuoteOverride: String? get() = inputState.replyQuoteOverride
    val asrState: ASRState get() = inputState.asrState
    val sessions: List<SessionEntity> get() = sessionState.sessions
    val currentSessionId: String? get() = sessionState.currentSessionId
    val archivedSessions: List<SessionEntity> get() = sessionState.archivedSessions
    val folders: List<io.zer0.muse.data.session.FolderEntity> get() = sessionState.folders
    val isSessionsLoading: Boolean get() = sessionState.isSessionsLoading
    val sessionsError: String? get() = sessionState.sessionsError
    val isAgentMode: Boolean get() = agentState.isAgentMode
    val agentSessionId: String? get() = agentState.agentSessionId
    val assistants: List<AssistantEntity> get() = agentState.assistants
    val isAssistantsLoading: Boolean get() = agentState.isAssistantsLoading
    val currentAssistant: AssistantEntity? get() = agentState.currentAssistant
    val delegationChain: List<io.zer0.muse.tools.DelegationChainTracker.ChainNode> get() = agentState.delegationChain
    val activePauseRequest: io.zer0.muse.tools.DelegationPauseManager.PauseRequest? get() = agentState.activePauseRequest
    val activeSubagentThreads: List<io.zer0.muse.data.subagent.SubagentThreadStore.ThreadEntry> get() = agentState.activeSubagentThreads
    val pendingSubagentTasks: List<io.zer0.muse.tools.DeferredResultStore.DeferredTask> get() = agentState.pendingSubagentTasks
    val isWeakToolModel: Boolean get() = agentState.isWeakToolModel
    val weakToolHint: String? get() = agentState.weakToolHint
    val isSessionLocked: Boolean get() = agentState.isSessionLocked
    val agentModeHint: String? get() = agentState.agentModeHint

    /** v0.49: 向后兼容 — 现有读 state.error 的地方取第一条错误消息。 */
    val error: String? get() = errors.firstOrNull()?.message

    fun copy(
        streamState: ChatStreamState = this.streamState,
        inputState: ChatInputState = this.inputState,
        sessionState: ChatSessionState = this.sessionState,
        agentState: ChatAgentState = this.agentState,
        toolsState: ChatToolsState = this.toolsState,
        isSwitchingSession: Boolean = this.isSwitchingSession,
        selectionMode: Boolean = this.selectionMode,
        selectedMessageIds: Set<String> = this.selectedMessageIds,
        errors: List<ChatError> = this.errors,
        isConfigured: Boolean = this.isConfigured,
        isFetchingModels: Boolean = this.isFetchingModels,
        fetchModelsError: String? = this.fetchModelsError,
        memoryCacheHit: Boolean = this.memoryCacheHit,
        isDrawerOpen: Boolean = this.isDrawerOpen,
        searchQuery: String = this.searchQuery,
        searchResults: List<SearchResult> = this.searchResults,
        isSearching: Boolean = this.isSearching,
        searchTab: Int = this.searchTab,
        messageResults: List<SearchResult> = this.messageResults,
        isSearchingMessages: Boolean = this.isSearchingMessages,
        searchError: String? = this.searchError,
        targetMessageId: String? = this.targetMessageId,
        searchHighlightQuery: String? = this.searchHighlightQuery,
        highlightedMessageId: String? = this.highlightedMessageId,
        isDrawMode: Boolean = this.isDrawMode,
        imageGenParams: io.zer0.ai.image.ImageGenParams = this.imageGenParams,
        isGeneratingImage: Boolean = this.isGeneratingImage,
        isGeneratingVideo: Boolean = this.isGeneratingVideo,
        isTranslating: Boolean = this.isTranslating,
        translatingMessageId: Uuid? = this.translatingMessageId,
        favoriteMessages: List<UIMessage> = this.favoriteMessages,
        isFavoritesLoading: Boolean = this.isFavoritesLoading,
        favoriteTags: List<String> = this.favoriteTags,
        favoriteTagFilter: String? = this.favoriteTagFilter,
        favoriteGroup: String? = this.favoriteGroup,
        webSearchEnabled: Boolean = this.webSearchEnabled,
        contextTokenCount: Int = this.contextTokenCount,
        contextMaxTokens: Int = this.contextMaxTokens,
        tokenEstimateEnabled: Boolean = this.tokenEstimateEnabled,
        pasteAsFileEnabled: Boolean = this.pasteAsFileEnabled,
        pasteAsFileThreshold: Int = this.pasteAsFileThreshold,
        tokenCountVisible: Boolean = this.tokenCountVisible,
        tokenSnapshot: TokenCountSnapshot? = this.tokenSnapshot,
        deepThinkingEnabled: Boolean = this.deepThinkingEnabled,
        deepThinkingLevel: ReasoningLevel = this.deepThinkingLevel,
        webSearchConfig: WebSearchConfig = this.webSearchConfig,
        quickMessages: List<QuickMessageEntity> = this.quickMessages,
        currentMode: String = this.currentMode,
        lorebooks: List<LorebookEntity> = this.lorebooks,
        promptInjections: List<PromptInjectionEntity> = this.promptInjections,
        allQuickMessages: List<QuickMessageEntity> = this.allQuickMessages,
        promptTemplates: List<io.zer0.muse.data.prompttemplate.PromptTemplate> = this.promptTemplates,
        isSpeaking: Boolean = this.isSpeaking,
        speakingMessageId: Uuid? = this.speakingMessageId,
        taskCards: Map<String, io.zer0.muse.ui.taskcard.TaskCardData> = this.taskCards,
        agentPlans: Map<String, io.zer0.muse.ui.taskcard.AgentPlan> = this.agentPlans,
        asrConfig: io.zer0.muse.asr.AsrConfig = this.asrConfig,
        providers: List<io.zer0.ai.core.ProviderConfig> = this.providers,
        activeProviderId: String? = this.activeProviderId,
        selectedModelId: String? = this.selectedModelId,
        toolModelId: String? = this.toolModelId,
        chatPreferences: io.zer0.muse.data.ChatPreferences = this.chatPreferences,
        shareTemplate: io.zer0.muse.data.ShareTemplateConfig = this.shareTemplate,
        mediaConfig: io.zer0.muse.data.MediaConfig = this.mediaConfig,
        debugInfo: String? = this.debugInfo,
        toast: String? = this.toast,
        multiAgentConfig: MultiAgentConfig = this.multiAgentConfig,
        selectedArtifact: io.zer0.muse.data.artifact.ArtifactEntity? = this.selectedArtifact,
        listFirstVisibleItemIndex: Int = this.listFirstVisibleItemIndex,
        listFirstVisibleItemScrollOffset: Int = this.listFirstVisibleItemScrollOffset,
        messageExpandedStates: Map<String, MessageExpandedState> = this.messageExpandedStates,
        hasMoreHistory: Boolean = this.hasMoreHistory,
        isLoadingMore: Boolean = this.isLoadingMore,
        lastHistoryLoadCount: Int = this.lastHistoryLoadCount,
        toolCallHistory: List<ToolCallRecord> = this.toolCallHistory,
        taskRoutingEnabled: Boolean = this.taskRoutingEnabled,
        pendingToolApprovals: List<PendingToolApproval> = this.pendingToolApprovals,
        compactionState: CompactionState? = this.compactionState,
        sessionPermissionMode: SessionPermissionMode = this.sessionPermissionMode,
        appRunAllowAllTools: Boolean = this.appRunAllowAllTools,
        isStreaming: Boolean = this.isStreaming,
        isWaitingFirstToken: Boolean = this.isWaitingFirstToken,
        visionProgress: io.zer0.muse.vision.VisionProgress? = this.visionProgress,
        visionAssistedMessageIds: Set<String> = this.visionAssistedMessageIds,
        isOcrProcessing: Boolean = this.isOcrProcessing,
        toolProgressMessage: String? = this.toolProgressMessage,
        pendingToolCallCount: Int = this.pendingToolCallCount,
        input: String = this.input,
        hasDraft: Boolean = this.hasDraft,
        inputHistory: List<String> = this.inputHistory,
        inputHistoryIndex: Int? = this.inputHistoryIndex,
        pendingImages: List<String> = this.pendingImages,
        pendingDocuments: List<io.zer0.muse.ui.chat.PendingDocument> = this.pendingDocuments,
        replyingTo: UIMessage? = this.replyingTo,
        replyQuoteOverride: String? = this.replyQuoteOverride,
        asrState: ASRState = this.asrState,
        sessions: List<SessionEntity> = this.sessions,
        currentSessionId: String? = this.currentSessionId,
        archivedSessions: List<SessionEntity> = this.archivedSessions,
        folders: List<io.zer0.muse.data.session.FolderEntity> = this.folders,
        isSessionsLoading: Boolean = this.isSessionsLoading,
        sessionsError: String? = this.sessionsError,
        isAgentMode: Boolean = this.isAgentMode,
        agentSessionId: String? = this.agentSessionId,
        assistants: List<AssistantEntity> = this.assistants,
        isAssistantsLoading: Boolean = this.isAssistantsLoading,
        currentAssistant: AssistantEntity? = this.currentAssistant,
        delegationChain: List<io.zer0.muse.tools.DelegationChainTracker.ChainNode> = this.delegationChain,
        activePauseRequest: io.zer0.muse.tools.DelegationPauseManager.PauseRequest? = this.activePauseRequest,
        activeSubagentThreads: List<io.zer0.muse.data.subagent.SubagentThreadStore.ThreadEntry> = this.activeSubagentThreads,
        pendingSubagentTasks: List<io.zer0.muse.tools.DeferredResultStore.DeferredTask> = this.pendingSubagentTasks,
        isWeakToolModel: Boolean = this.isWeakToolModel,
        weakToolHint: String? = this.weakToolHint,
        isSessionLocked: Boolean = this.isSessionLocked,
        agentModeHint: String? = this.agentModeHint,
    ): ChatUiState = ChatUiState(
        streamState = streamState.copy(
            isStreaming = isStreaming,
            isWaitingFirstToken = isWaitingFirstToken,
            visionProgress = visionProgress,
            visionAssistedMessageIds = visionAssistedMessageIds,
            isOcrProcessing = isOcrProcessing,
            toolProgressMessage = toolProgressMessage,
            pendingToolCallCount = pendingToolCallCount,
        ),
        inputState = inputState.copy(
            input = input,
            hasDraft = hasDraft,
            inputHistory = inputHistory,
            inputHistoryIndex = inputHistoryIndex,
            pendingImages = pendingImages,
            pendingDocuments = pendingDocuments,
            replyingTo = replyingTo,
            replyQuoteOverride = replyQuoteOverride,
            asrState = asrState,
        ),
        sessionState = sessionState.copy(
            sessions = sessions,
            currentSessionId = currentSessionId,
            archivedSessions = archivedSessions,
            folders = folders,
            isSessionsLoading = isSessionsLoading,
            sessionsError = sessionsError,
        ),
        agentState = agentState.copy(
            isAgentMode = isAgentMode,
            agentSessionId = agentSessionId,
            assistants = assistants,
            isAssistantsLoading = isAssistantsLoading,
            currentAssistant = currentAssistant,
            delegationChain = delegationChain,
            activePauseRequest = activePauseRequest,
            activeSubagentThreads = activeSubagentThreads,
            pendingSubagentTasks = pendingSubagentTasks,
            isWeakToolModel = isWeakToolModel,
            weakToolHint = weakToolHint,
            isSessionLocked = isSessionLocked,
            agentModeHint = agentModeHint,
        ),
        isSwitchingSession = isSwitchingSession,
        selectionMode = selectionMode,
        selectedMessageIds = selectedMessageIds,
        errors = errors,
        isConfigured = isConfigured,
        isFetchingModels = isFetchingModels,
        fetchModelsError = fetchModelsError,
        memoryCacheHit = memoryCacheHit,
        isDrawerOpen = isDrawerOpen,
        searchQuery = searchQuery,
        searchResults = searchResults,
        isSearching = isSearching,
        searchTab = searchTab,
        messageResults = messageResults,
        isSearchingMessages = isSearchingMessages,
        searchError = searchError,
        targetMessageId = targetMessageId,
        searchHighlightQuery = searchHighlightQuery,
        highlightedMessageId = highlightedMessageId,
        isDrawMode = isDrawMode,
        imageGenParams = imageGenParams,
        isGeneratingImage = isGeneratingImage,
        isGeneratingVideo = isGeneratingVideo,
        isTranslating = isTranslating,
        translatingMessageId = translatingMessageId,
        favoriteMessages = favoriteMessages,
        isFavoritesLoading = isFavoritesLoading,
        favoriteTags = favoriteTags,
        favoriteTagFilter = favoriteTagFilter,
        favoriteGroup = favoriteGroup,
        webSearchEnabled = webSearchEnabled,
        contextTokenCount = contextTokenCount,
        contextMaxTokens = contextMaxTokens,
        tokenEstimateEnabled = tokenEstimateEnabled,
        pasteAsFileEnabled = pasteAsFileEnabled,
        pasteAsFileThreshold = pasteAsFileThreshold,
        tokenCountVisible = tokenCountVisible,
        tokenSnapshot = tokenSnapshot,
        toolsState = toolsState.copy(
            taskCards = taskCards,
            toolCallHistory = toolCallHistory,
            pendingToolApprovals = pendingToolApprovals,
        ),
        deepThinkingEnabled = deepThinkingEnabled,
        deepThinkingLevel = deepThinkingLevel,
        webSearchConfig = webSearchConfig,
        quickMessages = quickMessages,
        currentMode = currentMode,
        lorebooks = lorebooks,
        promptInjections = promptInjections,
        allQuickMessages = allQuickMessages,
        promptTemplates = promptTemplates,
        isSpeaking = isSpeaking,
        speakingMessageId = speakingMessageId,
        agentPlans = agentPlans,
        asrConfig = asrConfig,
        providers = providers,
        activeProviderId = activeProviderId,
        selectedModelId = selectedModelId,
        toolModelId = toolModelId,
        chatPreferences = chatPreferences,
        shareTemplate = shareTemplate,
        mediaConfig = mediaConfig,
        debugInfo = debugInfo,
        toast = toast,
        multiAgentConfig = multiAgentConfig,
        selectedArtifact = selectedArtifact,
        listFirstVisibleItemIndex = listFirstVisibleItemIndex,
        listFirstVisibleItemScrollOffset = listFirstVisibleItemScrollOffset,
        messageExpandedStates = messageExpandedStates,
        hasMoreHistory = hasMoreHistory,
        isLoadingMore = isLoadingMore,
        lastHistoryLoadCount = lastHistoryLoadCount,
        taskRoutingEnabled = taskRoutingEnabled,
        compactionState = compactionState,
        sessionPermissionMode = sessionPermissionMode,
        appRunAllowAllTools = appRunAllowAllTools,
    )
}

data class ToolCallRecord(
    val toolName: String,
    val arguments: String,
    val result: String,
    val isSuccess: Boolean,
    val timestamp: Long,
)

/** 待审批的工具调用(ToolApprovalCard 用)。 */
/**
 * v1.0.47 P1: 上下文压缩状态机。
 *
 * - [Compacting]: 压缩进行中,显示进度(messageCount 为待压缩消息数)
 * - [Compacted]: 压缩完成,显示短暂成功提示(compressedCount 为已压缩条数)
 * - [Failed]: 压缩失败,降级为截断(reason 为失败原因)
 */
sealed class CompactionState {
    data class Compacting(val messageCount: Int) : CompactionState()
    data class Compacted(val compressedCount: Int) : CompactionState()
    data class Failed(val reason: String) : CompactionState()
}

data class PendingToolApproval(
    val toolCallId: String,
    val toolName: String,
    val argumentsPreview: String,
    val alwaysAllow: Boolean = false,
    /** v1.0.16: 本次开启期间批准全部工具(内存态,不持久化) */
    val appRunAllowAll: Boolean = false,
    /**
     * v1.x: 用户在审批卡片中选择的参考图(data URI 格式,如 "data:image/jpeg;base64,...")。
     *
     * 仅对 generate_image 等支持参考图的工具有效;批准后通过
     * [ToolApprovalState.Approved.argOverrides] 注入工具执行参数(reference_image 键)。
     * LLM 自身无法访问用户本地相册,故本字段是图生图参考图的主要来源。
     */
    val referenceImageOverride: String? = null,
)

/**
 * v1.x: 支持在审批卡片中选取本地参考图的工具名集合。
 *
 * 这些工具的 reference_image 参数(LLM 难以凭空生成 base64)允许用户在审批 UI 中
 * 从相册选择本地图片,选中后通过 [ToolApprovalState.Approved.argOverrides] 注入。
 */
private val REFERENCE_IMAGE_TOOL_NAMES: Set<String> = setOf("generate_image")

/**
 * v1.45: 单条消息的 UI 展开状态缓存。
 *
 * 用 null 表示"尚未被用户手动切换过,使用 chatPreferences 中的默认值"。
 */
data class MessageExpandedState(
    val isMoodExpanded: Boolean? = null,
    val isReasoningExpanded: Boolean? = null,
    val isReflectionExpanded: Boolean? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
/**
 * 聊天页 ViewModel — 整个 App 的核心业务中枢。
 *
 * 职责:
 *  - 会话/消息的加载、发送、流式接收、停止、重试、编辑、删除
 *  - 多模态:文本 + 图片(用户拍照/相册 + AI 生成图)+ 附件(文档 OCR 解析)
 *  - 工具调用:Skill 系统(9 个内置实现 + 用户导入 .skill.json)+ MCP 动态工具
 *  - 上下文管道:6 步 Transformer 顺序处理(Memory → Time → Lorebook → PromptInjection → Template → ThinkTag/MoodTag → 压缩)
 *  - 系统提示组装:SystemPromptAssembler 把 Assistant 配置 + 用户画像 + 记忆组合成最终 system prompt
 *  - Web 搜索:深思考开关开启时,LLM 自主决定何时调用 web_search/web_fetch
 *  - 深思考:开关切换 ReasoningLevel.HIGH(8000 tokens 预算)
 *  - TTS:流式生成完成后可触发系统 TTS 播报
 *  - 通知:流式启动/进度/完成/错误/停止处调用 MuseNotificationManager
 *
 * 状态管理:单一 [state] StateFlow,UI 通过 collectAsStateWithLifecycle 订阅。
 * 协程:所有网络/DB 操作用 viewModelScope,工具调用有 2 分钟超时([TOOL_TIMEOUT_MS])。
 */
// i18n 后续提取路线图(整体迁移时统一处理,本处暂缓)— 本 ViewModel 已注入
// appContext(见下方构造函数),后续可用 appContext.getString(R.string.xxx) 或
// ErrorMessage.toLocalizedString(appContext) 替换以下区域的中文字符串:
// MuseToast.show / reportError / addError / errors.emit / toast /
// fetchModelsError / 各 ChatError.message / 通知文本 / 步骤进度文案 / 导出 markdown 标签等。
// Logger 日志/TAG/LLM 提示词不提取。
// @REFACTOR: 建议拆分的子组件
//   - ChatSessionManager: 会话创建/切换/归档逻辑
//   - ChatStreamOrchestrator: 流式请求/重连/超时
//   - ChatToolDispatcher: ToolCall 路由与执行
//   - ChatContextController: 上下文管理与压缩
//   - ChatAudioController: TTS/ASR 状态管理
//   - ChatErrorHandler: 错误分类与用户提示

/**
 * v1.131: 剥离 @文档名 标记的正则 — 文件级常量,避免每次 [buildWebSearchQuery] 调用都新建。
 *
 * 用于在 web 搜索前清理 user query 中的 @mention(知识库引用语义),
 * 防止其污染搜索关键词。
 */
private val KNOWLEDGE_MENTION_REGEX = Regex("@[^\\s@]+")

/** R-TEST-06: 发送守卫纯逻辑（防空消息/流式中/Agent 会话创建重入）。 */
internal fun canStartGeneration(
    text: String,
    images: List<String>,
    isStreaming: Boolean,
    isCreatingAgentSession: Boolean,
): Boolean = (text.isNotBlank() || images.isNotEmpty()) && !isStreaming && !isCreatingAgentSession

/** R-TEST-06: 发送前合并待发送文档内容与用户输入(文档文本 + 用户输入)。 */
internal fun buildSendText(rawText: String, documentContents: List<String>): String {
    val docText = documentContents.joinToString("\n\n---\n\n")
    return when {
        documentContents.isEmpty() -> rawText
        rawText.isBlank() -> docText
        else -> "$docText\n\n---\n\n$rawText"
    }
}

/** R-TEST-06: 仅当非流式、最后一条为带 [已中断] 标记的助手消息时才允许续写。 */
internal fun canContinueGeneration(isStreaming: Boolean, lastMessage: UIMessage?): Boolean =
    !isStreaming && lastMessage != null &&
        lastMessage.role == MessageRole.ASSISTANT &&
        lastMessage.content.contains("[已中断]")

/** R-TEST-06: 去掉 [已中断] 尾部标记,保留断点前内容。 */
internal fun resumeFromInterrupted(content: String): String =
    content.removeSuffix("\n\n[已中断]").removeSuffix("[已中断]")

/** R-TEST-06: 重生成仅当非流式、有会话且当前用户变体可选时可用。 */
internal fun canRegenerate(
    isStreaming: Boolean,
    hasSession: Boolean,
    hasSelectedUserVariant: Boolean,
): Boolean = !isStreaming && hasSession && hasSelectedUserVariant

class ChatViewModel(
    private val chatService: ChatService,
    private val settings: SettingsRepository,
    private val memoryTicker: MemoryTicker,
    private val sessionRepository: SessionRepository,
    private val imageService: ImageService,
    private val videoGenerationService: io.zer0.ai.video.VideoGenerationService,
    private val documentParser: DocumentParser,
    private val toolRegistry: ToolRegistry,
    private val assistantRepository: AssistantRepository,
    private val webSearchService: WebSearchService,
    private val lorebookRepository: LorebookRepository,
    private val quickMessageRepository: QuickMessageRepository,
    private val promptInjectionRepository: PromptInjectionRepository,
    private val ocrManager: io.zer0.muse.doc.OcrManager,
    private val ttsManager: TtsManager,
    private val skillRepository: io.zer0.muse.data.skill.SkillRepository,
    private val skillExecutor: io.zer0.muse.tools.SkillExecutor,
    // v1.201: 委派暂停管理器 + 链路追踪器(与 SkillExecutor 共享同一 Koin single 实例)
    private val delegationPauseManager: io.zer0.muse.tools.DelegationPauseManager,
    private val delegationChainTracker: io.zer0.muse.tools.DelegationChainTracker,
    private val agentRouter: AgentRouter,
    private val folderRepository: io.zer0.muse.data.session.FolderRepository,
    private val notificationManager: MuseNotificationManager,
    // v0.30-a: 系统提示组装器(6 步工作流第 1 步)
    private val systemPromptAssembler: io.zer0.muse.transformer.SystemPromptAssembler,
    // v1.43: 应用级生成管理器(切页/后台保持生成)
    private val chatGenerationManager: ChatGenerationManager,
    // v1.43: 产物仓库(会话内嵌产物卡片)
    private val artifactRepository: ArtifactRepository,
    // v1.43: 应用 Context,用于启动前台服务
    private val appContext: Context,
    // v1.54: RAG 服务(知识库自动注入)
    private val ragService: io.zer0.muse.rag.RagService,
    // v1.25: 视觉辅助桥接器(让纯文本模型通过视觉模型"看到"图片)
    private val visionBridge: io.zer0.muse.vision.VisionBridge,
    // P2-4: 审计日志记录器(在消息发送入口记录用户发消息事件)
    private val auditLogger: AuditLogger,
    // P3: 会话级工具权限模式持久化
    private val sessionPermissionStore: SessionPermissionStore,
    // v1.0.15: 网络状态监听器(StreamInterrupted 后等待网络恢复自动重连)
    private val networkMonitor: NetworkMonitor,
    // v2.1: 用户活跃度画像,用户发消息时记录活动 + 更新对话结束类型,驱动自适应主动消息调度
    private val activityProfile: UserActivityProfile,
    // v1.202: 异步委派结果回灌(订阅 completedTasks,把后台完成的子 agent 结果作为 interlude 注入主对话)
    private val deferredResultStore: io.zer0.muse.tools.DeferredResultStore,
    // v1.202: 子 agent 线程管理器(暴露活跃子 agent 线程给 UI 展示)
    private val subagentThreadStore: io.zer0.muse.data.subagent.SubagentThreadStore,
    // v1.x: 会话级资源管理器(引用计数 + idle 清理)
    private val sessionManager: io.zer0.muse.session.ConversationSessionManager,
    // P1-1: Hook 注册表(注入 ToolOrchestrator + 消息处理 Hook)
    private val hookRegistry: io.zer0.muse.hook.HookRegistry? = null,
    // v1.0.52 P2-3: AI 记忆自动保存(对话中实时提取实体/关系/合并/分类)
    private val memoryAutoSaveScheduler: io.zer0.memory.ai.MemoryAutoSaveScheduler? = null,
    // B0-08: 里程碑检查器(生成结束后检查是否触发里程碑)
    private val milestoneChecker: MilestoneChecker? = null,
    // B2-04: 统一由 Koin 注入 ToolOrchestrator(主会话/子代理共享审批链路)
    private val toolOrchestrator: io.zer0.muse.tools.ToolOrchestrator,
    // B2-04: 子代理审批路由(注册本实例为 delegate,子代理需审批时弹主会话审批卡)
    private val toolApprovalRouter: io.zer0.muse.tools.ToolApprovalRouter,
    /** P0 对话树选择快照存储(可为 null,测试环境不注入)。 */
    private val treeSnapshotStore: ConversationTreeSnapshotStore? = null,
    // v1.x: 会话级浏览器实例注册表(每个会话独立 WebView,删除会话时释放)
    private val browserManagerRegistry: io.zer0.muse.tools.BrowserManagerRegistry? = null,
    // v1.x: 工具配置存储(审批策略持久化) — Koin 注入单例,
    // 消除直接 new 导致的 DataStore 同文件多实例崩溃
    private val toolConfigStore: io.zer0.muse.tools.ToolConfigStore? = null,
) : ViewModel(), ChatStateAccessor, io.zer0.muse.tools.ToolApprovalBridge {
    // v1.0.54: autoSave 去重状态(30 秒内同会话只跑一次,防堆积)
    private var lastAutoSaveSessionId: String? = null
    private var lastAutoSaveAt: Long = 0L

    companion object {
        /** v0.47: 工具调用超时阈值(2 分钟),超时则终止,避免阻塞流式输出。 */
        private const val TOOL_TIMEOUT_MS = 120_000L
        /** v1.53-A1: 消息分页页大小(初始加载 + 上滑加载更多的窗口大小)。 */
        private const val MESSAGE_PAGE_SIZE = 50
        /** v1.78 (#32): 手动压缩保留最近消息条数上限(自适应 min(此值, size-1))。 */
        private const val MANUAL_COMPRESS_KEEP_RECENT = 10
        /** v1.79 (L-CV1): 工具调用最大轮次(防死循环安全网)。 */
        private const val MAX_TOOL_ROUNDS = 25
        /**
         * v1.134 P1-1: 流式自动重试最大次数(NETWORK/RATE_LIMIT 错误)。
         * v1.0.16: 从 2 提到 3,退避 3s/10s/30s,覆盖典型切后台时长(5-15s)。
         */
        private const val MAX_STREAM_RETRIES = 3
        /**
         * v1.0.17: StreamInterrupted 智能续传等待网络恢复的超时时间(毫秒)。
         *
         * 网络中断后轮询 [NetworkMonitor.isOnline],最多等待 30s;
         * 超时未恢复则降级为手动重试(保留部分内容 + isRecoverable)。
         * 30s 覆盖典型切后台 / 网络切换时长(5-15s),避免无限等待。
         */
        private const val NETWORK_RECOVERY_TIMEOUT_MS = 30_000L
        /** v1.0.17: 等待网络恢复时的轮询间隔(毫秒)。 */
        private const val NETWORK_POLL_INTERVAL_MS = 2_000L
        /** v1.200: 自动路由置信度阈值，低于此值仍走当前助手。 */
        private const val AUTO_ROUTE_CONFIDENCE_THRESHOLD = 0.55f
        // v1.80 (L-CV1): 流式/绘图/压缩相关魔法数字提取为常量
        /** 自动压缩触发阈值:token 占用超过 80% 时后台压缩。 */
        private const val AUTO_COMPRESS_TOKEN_RATIO = 0.8f
        // v1.105 阶段 4: IMAGE_SCALE_TARGET / IMAGE_JPEG_QUALITY / DOC_MAX_CHARS 已下沉到对应 Coordinator
        /**
         * v1.0.3: 流式 UI 更新字符阈值(节流)。
         *
         * 从 40 降到 12,让内容更平滑地流入,避免"一段一段"的视觉断层。
         * 首 token 会绕过此阈值立即刷新(见 [launchStream] 内 isFirstToken 分支)。
         */
        private const val STREAM_UI_CHAR_THRESHOLD = 12
        /**
         * v1.0.3: 流式 UI 更新时间阈值(毫秒,节流)。
         *
         * 从 80ms 降到 50ms,配合更小的字符阈值让流式更连贯。
         * 50ms ≈ 20fps,人眼感知为"连续流动"而非"一段一段"。
         */
        private const val STREAM_UI_TIME_THRESHOLD_MS = 50L
        // v1.0.4: 流式 UI 自适应切片(50ms 节流 + 自适应切片)。
        // 把"字符数 OR 时间"双条件改为"固定 50ms 节流 + 自适应切片大小":
        // - delta 先累积到 pendingBuilder,50ms 定时器触发时按切片大小取前 N 个字符输出
        // - 切片大小根据流式速率(最近 10 个 chunk 间隔滑动平均)动态调整:
        //   慢速流(Reasoning)间隔大 → rate 小 → 切片小(细粒度,2-40)
        //   快速流(纯文本)间隔小 → rate 大 → 切片大(批量,40-240)
        /** 固定节流间隔(毫秒),时间到就触发一次切片输出。 */
        private const val STREAM_THROTTLE_MS = 50L
        /** 自适应切片下限:慢速流也至少输出 2 字符,避免空刷新。 */
        private const val STREAM_SLICE_MIN = 2
        /** 自适应切片基准:rate=1.0 时的切片大小,对应 avgInterval≈50ms 的中速流。 */
        private const val STREAM_SLICE_BASE = 40
        /** 自适应切片上限:快速流单次最多输出 240 字符,避免一次刷新过多造成视觉断层。 */
        private const val STREAM_SLICE_MAX = 240
        /** chunk 间隔滑动窗口大小(最近 N 个 chunk 的间隔用于计算平均速率)。 */
        private const val STREAM_SLIDE_WINDOW = 10
        /** v1.0.47 P5: 输入历史保留条数(本会话内,内存态,不持久化)。 */
        private const val MAX_INPUT_HISTORY = 50
        // v1.117: 删除 6 个孤儿常量(STREAM_NOTIF_*/STREAM_TOKEN_*/STREAM_PERSIST_*),
        // 实际节流逻辑在 launchStream 内用字面量实现,这些常量从未被引用。

        // v1.116 (C1-1): 单个工具结果送入 LLM 上下文的最大字符数,防止超长结果撑爆上下文。
        // 8000 字符约 2000-3000 token,足以覆盖常规工具输出(web 搜索摘要/文件读取片段等)。
        private const val MAX_TOOL_RESULT_CHARS = 8000
        // v1.116 (C1-2): 工具调用循环内 conversationHistory 的工具链部分最大消息条数。
        // 超过时丢弃较早的工具调用轮次(保留初始上下文 + 最近工具链)。
        private const val MAX_TOOL_CHAIN_MESSAGES = 30
        // v1.116 (C1-3): 连续工具失败早停阈值,避免跑满 25 轮白耗 API 额度。
        private const val MAX_CONSECUTIVE_TOOL_FAILURES = 3
        // v1.116 (C1-4): 发送前上下文 token 占用预警比例,超过则激进截断历史。
        private const val PRESEND_TOKEN_WARNING_RATIO = 0.9f
        // v1.116: 表情包相关工具 ID 集合,用于概率控制时过滤
        private val STICKER_TOOL_IDS = setOf("list_stickers", "send_sticker")

        /**
         * v1.104 U7: [favoriteTagFilter] 的特殊值,表示"仅显示未分组"。
         *
         * 用一个不会与用户自定义标签冲突的内部字符串。
         * FavoritesScreen 顶部 FilterChip "未分组" 选中时调 setFavoriteTagFilter(FAVORITE_TAG_UNGROUPED)。
         */
        const val FAVORITE_TAG_UNGROUPED = "__ungrouped__"

        // v2.0: 预设收藏分类常量
        const val FAVORITE_GROUP_INSPIRATION = "__group_inspiration__"
        const val FAVORITE_GROUP_CODE = "__group_code__"
        const val FAVORITE_GROUP_LEARNING = "__group_learning__"
        const val FAVORITE_GROUP_CUSTOM = "__group_custom__"
    }

    private val _state = MutableStateFlow(ChatUiState())
    // v1.100: StateFlow 本身已是 conflated(只保留最新值),流式高频更新时
    // collectAsStateWithLifecycle 只会拿到最新值。实际瓶颈在重组范围(P1-P3 已
    // 通过 @Immutable + derivedStateOf 收窄),无需额外 sample。
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** B2-01: 消息列表独立 StateFlow,不再放进 ChatUiState。 */
    private val _messages = MutableStateFlow<List<UIMessage>>(emptyList())
    val messages: StateFlow<List<UIMessage>> = _messages.asStateFlow()

    // v1.93+: 会话消息内存 LRU 缓存。
    // ChatViewModel 改为 Koin single 后会话消息列表永不释放,长时间使用内存累积明显。
    // 此处以 LRU 策略保留最近 SessionMemoryCache.MAX_CACHE_SIZE 个会话的内存副本,
    // 超出后自动驱逐最久未访问的会话,切回时从 DB 重新加载。
    // 注:无外部依赖,直接实例化,不走 Koin(避免改动 single{} 注册的位置参数列表)。
    private val sessionMemoryCache = SessionMemoryCache()

    // ── 语音对话模式(录音 → 识别 → 思考 → 播报循环)──────────
    // 状态机:IDLE → LISTENING → THINKING → SPEAKING → LISTENING(循环)
    // IDLE:等待用户点击主按钮
    // LISTENING:ASR 录音中,实时回调写入 transcript
    // THINKING:用户消息已发送,等待 AI 流式回复完成
    // SPEAKING:TTS 朗读 AI 回复,朗读完恢复 LISTENING(连续对话)
    private val _voiceConversationState = MutableStateFlow(VoiceConversationState.IDLE)
    val voiceConversationState: StateFlow<VoiceConversationState> = _voiceConversationState.asStateFlow()

    /** 语音对话模式实时识别文本(LISTENING 时 ASR 回调写入,UI 据此显示转写)。 */
    private val _voiceConversationTranscript = MutableStateFlow("")
    val voiceConversationTranscript: StateFlow<String> = _voiceConversationTranscript.asStateFlow()

    /** 语音对话模式当前 AI 回复文本(SPEAKING 时填充,UI 据此显示朗读内容)。 */
    private val _voiceConversationAiReply = MutableStateFlow("")
    val voiceConversationAiReply: StateFlow<String> = _voiceConversationAiReply.asStateFlow()

    /** TTS 播放状态(透传 TtsManager.playbackState,供语音对话 UI 显示进度)。 */
    val ttsPlaybackStateFlow: StateFlow<PlaybackState> get() = ttsManager.playbackState

    /** 语音对话循环观察协程(监听 ASR/流式/TTS 状态切换,驱动状态机自动循环)。 */
    private var voiceConversationJob: Job? = null

    // 消息分支管理器
    // P0 对话树: 两级结构(用户提问组 → 助手回复组)的事实源,
    // _messages 在流式期间保持扁平列表,稳定点(发送/重试/编辑/切会话/流结束)重建树并同步显示。
    private val _conversationTree = MutableStateFlow(ConversationTree())
    val conversationTree: StateFlow<ConversationTree> = _conversationTree.asStateFlow()
    // P0 对话树: 记录当前树所属会话,防止切会话/切 Agent 时把上一个会话的分支带过来。
    @Volatile private var _conversationTreeSessionId: String? = null
    // v1.0.30: 待写入的变体信息（regenerate 流完成后应用）
    private data class VariantInfo(val groupId: String, val index: Int, val count: Int)
    @Volatile private var _pendingVariantInfo: VariantInfo? = null
    // P0 对话树: 待编辑的用户消息 id(用户点击编辑后回填输入框,发送时应用为新的用户变体)
    // v1.0.30: 回话跟踪 — onAppForeground 用
    @Volatile private var _lastSessionSwitchTimestamp: Long = 0L
    @Volatile private var _lastSessionSwitchId: String? = null
    // 工具配置存储(审批策略持久化) — 见构造参数 toolConfigStore

    // v1.135: 当前工具调用轮次对应的助手消息 id,
    // 供 generate_image / generate_video / generate_qr_code 等工具更新消息媒体字段。
    private var toolAssistantId: Uuid? = null

    // v5: 消息发送队列 — 串行化处理,防止快速连续发送导致竞态
    private data class SendRequest(
        val text: String,
        val images: List<String>,
        val sessionId: String,
        val retryCount: Int = 0,
        // 乐观更新回滚用:enqueueSend 创建的 user/assistant 消息 id
        // P0 修复: 携带完整 userMessage(含原始 id + createdAt),消费端直接复用落盘,
        //   避免 consumer 重新 new UIMessage 导致 createdAt 取到消费时刻(晚于 assistantMsg.createdAt),
        //   切页重载后按 createdAt 排序时 user 消息会掉到 assistant 之下。
        val userMessage: UIMessage,
        val assistantMessageId: Uuid,
        // v1.0.15: outbox 记录 id(持久化发送队列,进程被杀后恢复用)
        val outboxId: String,
    )

    // 限制容量为 8,防止含 base64 图片的请求无界堆积导致 OOM
    private val sendChannel = Channel<SendRequest>(capacity = 8)

    // v5: 乐观更新 — 用户消息立即显示到 UI,不等待 DB 写入
    private fun enqueueSend(text: String, images: List<String>, sessionId: String) {
        // v2.1: 记录用户活动到活跃度画像,并更新对话结束类型(驱动自适应主动消息调度)
        // 在 enqueueSend 入口记录 = 用户点击发送的时刻,无论后续是否 session mismatch / 重试
        activityProfile.recordActivity()
        activityProfile.setConversationEndType(
            if (UserActivityProfile.containsEndKeyword(text)) ConversationEndType.USER_EXPLICIT_END
            else ConversationEndType.NATURAL_FADE
        )
        // P2-4: 审计日志 — 发送消息
        auditLogger.log(
            category = "user_action",
            action = "send_message",
            target = sessionId,
            detail = mapOf(
                "text_length" to text.length,
                "image_count" to images.size,
                "assistant_id" to currentAssistantId(),
            ),
        )
        // v2.3: 任务模型路由——根据输入内容自动推荐模型
        val routedModelId = settings.recommendModelForTask(text, _state.value.selectedModelId)
        if (routedModelId != null && routedModelId != _state.value.selectedModelId) {
            viewModelScope.launch { settings.saveSelectedModel(routedModelId) }
            _state.update { it.copy(selectedModelId = routedModelId) }
        }
        // v1.0.47 P5: 记录输入历史(新→旧,去重,截断到 MAX_INPUT_HISTORY)
        val newHistory = (listOf(text) + _state.value.inputHistory.filter { it != text })
            .take(MAX_INPUT_HISTORY)
        val userMsg = UIMessage(
            role = MessageRole.USER,
            content = text,
            imageBase64List = images,
        )
        // P0 修复: 强制 assistantMsg.createdAt 严格晚于 userMsg.createdAt(+1ms),
        //   避免同毫秒碰撞导致 DB ORDER BY createdAt ASC 排序不稳定(user/assistant 顺序错乱)。
        //   原实现两者各自取 System.currentTimeMillis(),快速连续调用可能返回同值。
        val assistantMsg = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            createdAt = userMsg.createdAt + 1,
        )
        // v1.0.15: 异步写入 outbox(保证"刚点击发送就退出"时消息不丢失)
        // 原 runBlocking 在主线程同步阻塞 5-10ms,低配设备可能 ANR;改为 viewModelScope.launch 异步写入。
        // 权衡:launch 是 fire-and-forget,若用户立即退出 App,viewModelScope 取消协程,
        // outbox 可能没写完导致此条消息丢失(极端情况,概率极低,可接受)。
        // 保留 outboxInsertJob 引用,队列满回滚时通过 join 等待 insert 完成再 delete,避免竞态。
        val outboxId = Uuid.random().toString()
        val outboxInsertJob = viewModelScope.launch(Dispatchers.IO) {
            resultOf {
                sessionRepository.insertOutbox(io.zer0.muse.data.session.MessageOutboxEntity(
                    id = outboxId,
                    sessionId = sessionId,
                    text = text,
                    imageBase64Json = idListJson.encodeToString(images),
                    userMessageId = userMsg.id.toString(),
                    assistantMessageId = assistantMsg.id.toString(),
                    createdAt = System.currentTimeMillis(),
                ))
            }.onError { _, t -> Logger.w("ChatVM", "outbox 写入失败,进程被杀可能丢失此消息", t) }
        }
        _messages.value = _messages.value + userMsg + assistantMsg
        _state.update {
            it.copy(
                input = "",
                hasDraft = false,
                pendingImages = emptyList(),
                pendingDocuments = emptyList(),
                replyingTo = null,
                replyQuoteOverride = null,
                isStreaming = true,
                // v1.0.3: 进入"等待首 token"阶段,UI 显示 ShimmerBubble
                isWaitingFirstToken = true,
                errors = emptyList(),
                // v1.0.47 P5: 记录输入历史,重置导航索引(发送后退出历史导航)
                inputHistory = newHistory,
                inputHistoryIndex = null,
            )
        }
        // v1.0.72: 草稿功能已砍掉,无防抖保存任务
        val sendResult = sendChannel.trySend(SendRequest(text, images, sessionId, userMessage = userMsg, assistantMessageId = assistantMsg.id, outboxId = outboxId))
        if (sendResult.isFailure) {
            // 队列已满,回滚乐观更新 + 删除 outbox(消息未入队,outbox 无用)
            // 异步删除:先 join 等待 insert 协程完成,避免 delete 先于 insert 落盘的竞态导致残留记录
            viewModelScope.launch(Dispatchers.IO) {
                outboxInsertJob.join()
                resultOf { sessionRepository.deleteOutbox(outboxId) }
            }
            _state.update {
                val filtered = _messages.value.filterNot { msg ->
                    msg.id == userMsg.id || msg.id == assistantMsg.id
                }
                _messages.value = filtered
                it.copy(
                    isStreaming = false,
                    isWaitingFirstToken = false,
                )
            }
            addError(ChatErrorType.UNKNOWN, appContext.getString(R.string.err_chat_queue_full))
            return
        }
        rebuildConversationTree()
    }

    // v1.105 拆分: ChatStateAccessor 实现 — 供各 Coordinator 读写 state
    override val snapshot: ChatUiState get() = _state.value
    override fun update(transform: (ChatUiState) -> ChatUiState) = _state.update(transform)
    override val messagesSnapshot: List<UIMessage> get() = _messages.value
    override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) = _messages.update(transform)
    override val coroutineScope: kotlinx.coroutines.CoroutineScope get() = viewModelScope

    // v1.105 阶段 1 拆分: 各职责域 Coordinator(无 state,持有依赖,通过 accessor 读写)
    private val imageGenCoordinator = ImageGenCoordinator(
        accessor = this,
        imageService = imageService,
        settings = settings,
        sessionRepository = sessionRepository,
        ocrManager = ocrManager,
        chatService = chatService,
        appContext = appContext,
    )
    private val exportCoordinator = ChatExportCoordinator(
        accessor = this,
        settings = settings,
        sessionRepository = sessionRepository,
        auditLogger = auditLogger,
    )
    private val audioCoordinator = ChatAudioCoordinator(
        accessor = this,
        ttsManager = ttsManager,
        settings = settings,
    )
    private val documentCoordinator = ChatDocumentCoordinator(
        accessor = this,
        documentParser = documentParser,
        settings = settings,
    )
    // v1.105 阶段 2 拆分: 杂项 Coordinator(文件夹 / 搜索 / 收藏 / 管理页 CRUD)
    private val miscCoordinator = ChatMiscCoordinator(
        accessor = this,
        sessionRepository = sessionRepository,
        folderRepository = folderRepository,
        lorebookRepository = lorebookRepository,
        promptInjectionRepository = promptInjectionRepository,
        quickMessageRepository = quickMessageRepository,
        assistantRepository = assistantRepository,
        appContext = appContext,
    )
    /** Phase 8.5: 复用的 Json 实例(避免每次解析都新建)。 */
    private val idListJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    // v0.45: 提取为字段,manualCompress 直接调用 transform 做手动压缩
    // v1.0.17: 注入 ConversationCompressor,启用分块并行 + 独立便宜模型
    private val conversationCompressor = io.zer0.muse.transformer.ConversationCompressor(chatService, settings)
    private val contextCompressTransformer = ContextCompressTransformer(chatService, conversationCompressor)

    private val transformerPipeline: TransformerPipeline by lazy { buildTransformerPipeline() }

    // v1.105 阶段 3 拆分: 流式辅助 Coordinator(detach / updateAssistant / 持久化 / 标签提取)
    private val streamCoordinator = ChatStreamCoordinator(
        accessor = this,
        sessionRepository = sessionRepository,
        memoryTicker = memoryTicker,
        settings = settings,
        appContext = appContext,
        notificationManager = notificationManager,
        assistantRepository = assistantRepository,
        visionBridge = visionBridge,
        toolRegistry = toolRegistry,
        skillRepository = skillRepository,
        idListJson = idListJson,
        lorebookRepository = lorebookRepository,
        promptInjectionRepository = promptInjectionRepository,
        transformerPipeline = transformerPipeline,
        hookRegistry = hookRegistry,
    )
    // v1.134 P1-5: 任务卡 Coordinator(任务卡阶段/步骤/展开/重试/工具结果判定)
    private val taskCardCoordinator = ChatTaskCardCoordinator(
        accessor = this,
        toolRegistry = toolRegistry,
    )

    /**
     * Phase 8.1 H1 + Phase 8.2 + Phase 8.5: Transformer 管道。
     * 顺序: MemoryInjection → TimeReminder → Lorebook → PromptInjection → Template(变量替换) → ThinkTag
     * - TemplateTransformer 接管 Assistant.messageTemplate 的 {{var}} 替换
     * - Assistant 配置通过 [TransformContext.extras] 注入,各 Transformer 自行读取
     * - Phase 8.5: LorebookTransformer(关键词触发) + PromptInjectionTransformer(模式开关)
     */
    private fun buildTransformerPipeline(): TransformerPipeline = TransformerPipeline.Builder()
        // v8: MemoryInjectionTransformer 新增可选 factStore 参数(默认 null)用于按 scope 过滤。
        // 本文件按任务约定"仅输出修改建议不直接修改",这里仍用单参数构造(走 fallback 路径)。
        // 启用 scope 过滤需补 factStore 参数,详见最终回复 ChatViewModel.kt 修改建议清单。
        .add(MemoryInjectionTransformer(memoryTicker))
        .add(TimeReminderTransformer())
        .add(LorebookTransformer(lorebookRepository))
        .add(PromptInjectionTransformer())
        // v1.97: 传入 appContext 以读取电池电量等系统变量
        .add(TemplateTransformer(appContext))
        // v1.97: 助手级正则替换规则(visualOnly=false 走管道,影响 LLM 输入)
        .add(io.zer0.muse.transformer.RegexMessageTransformer())
        .add(ThinkTagTransformer())
        // v0.30-b: MOOD 标签剥离(6 步工作流第 6 步,放 ThinkTag 后)
        .add(io.zer0.muse.transformer.MoodTagTransformer())
        // v0.25: 长上下文压缩(消息数超阈值时调用 LLM 生成摘要替换旧消息)
        .add(contextCompressTransformer)
        .build()

    /** Phase 8.5 修复: 首次会话初始化标记,防止 observeSessions 竞态重复创建会话。 */
    @Volatile
    private var initializing: Boolean = false
    /** 当前图片生成任务(P5-G)。 */
    private var imageJob: Job? = null
    /** v1.79 (M-CV8): 防止 Agent 模式创建会话重入 */
    @Volatile
    private var _isCreatingAgentSession = false
    /** 当前翻译任务(P5-F)。 */
    private var translateJob: Job? = null

    init {
        // B2-04: 子代理遇到需审批工具时,复用主会话的审批卡
        toolApprovalRouter.delegate = this
        // 监听配置变化,刷新 isConfigured 标志
        // v1.22: 本地不再硬编码模型列表,isConfigured 只要有激活 Provider 即为 true。
        // 若激活 Provider 没有模型,自动触发上游 /models 拉取。
        // 真正缺凭证时由 chatService 发送抛错,通过 state.error 提示用户。
        viewModelScope.launch {
            settings.providerConfigFlow.collect { config ->
                _state.update {
                    it.copy(
                        isConfigured = config != null
                    )
                }
                // 自动拉取:激活 Provider 已配置 apiKey 但 models 为空
                if (config != null && config.models.isEmpty() && config.apiKey.isNotBlank()) {
                    refreshModels(config.id)
                }
            }
        }
        // 阶段 5: 观察全部 Provider / 激活 Provider / 选中模型(底部切换面板用)
        viewModelScope.launch {
            settings.providersFlow.collect { providers ->
                _state.update { it.copy(providers = providers) }
            }
        }
        viewModelScope.launch {
            settings.activeProviderIdFlow.collect { id ->
                _state.update { it.copy(activeProviderId = id) }
            }
        }
        viewModelScope.launch {
            settings.selectedModelIdFlow.collect { modelId ->
                _state.update { it.copy(selectedModelId = modelId) }
            }
        }
        // v1.60-A: 收集工具模型 id(工具调用轮次使用)
        viewModelScope.launch {
            settings.toolModelIdFlow.collect { modelId ->
                _state.update { it.copy(toolModelId = modelId) }
            }
        }
        // v1.0.47: Token 估算开关
        viewModelScope.launch {
            settings.tokenEstimateEnabledFlow.collect { enabled ->
                _state.update { it.copy(tokenEstimateEnabled = enabled) }
            }
        }
        // v1.0.47 P5-2: 长文本粘贴转文件开关 + 阈值
        viewModelScope.launch {
            settings.pasteAsFileEnabledFlow.collect { enabled ->
                _state.update { it.copy(pasteAsFileEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.pasteAsFileThresholdFlow.collect { threshold ->
                _state.update { it.copy(pasteAsFileThreshold = threshold) }
            }
        }
        // 观察会话列表(侧栏用)
        viewModelScope.launch {
            try {
                sessionRepository.observeSessions().collect { sessions ->
                    _state.update { it.copy(sessions = sessions, isSessionsLoading = false, sessionsError = null) }
                    // 首次加载:无会话则新建,有则切换到最近一个
                    // Phase 8.5 修复: 用 initializing flag 防止 observeSessions 在 createNewSession
                    // 异步设置 currentSessionId 前再次发射时重复创建多个会话
                    if (_state.value.currentSessionId == null && !initializing) {
                        initializing = true
                        // R-UI-02: 进程恢复优先还原用户离开时查看的会话,而非最近活跃会话。
                        val viewedId = resultOf { settings.getViewedSessionId() }.getOrNull()
                        val target = sessions.firstOrNull { it.id == viewedId } ?: sessions.firstOrNull()
                        if (target != null) {
                            switchSession(target.id)
                        } else {
                            createNewSession()
                        }
                    }
                }
            } catch (t: Throwable) {
                // v1.x: 协程取消必须重抛,不能当成"加载失败"展示(否则显示
                // "请求失败: Job was cancelled"且协程死亡后错误永远挂着)
                if (t is kotlinx.coroutines.CancellationException) throw t
                Logger.e("ChatVM", "observeSessions failed", t)
                _state.update {
                    it.copy(
                        isSessionsLoading = false,
                        sessionsError = appContext.getString(
                            R.string.err_chat_request_failed,
                            t.message ?: appContext.getString(R.string.err_chat_unknown),
                        ),
                    )
                }
            }
        }
        // v0.45: 观察已归档会话(归档 FilterCard 用)
        viewModelScope.launch {
            sessionRepository.observeArchived().collect { archived ->
                _state.update { it.copy(archivedSessions = archived) }
            }
        }
        // Phase 8.2: 观察 Assistant 列表(侧栏选择器用)
        viewModelScope.launch {
            assistantRepository.observeAll.collect { list ->
                _state.update { it.copy(assistants = list, isAssistantsLoading = false) }
            }
        }
        // Phase 8.3: 观察跨会话收藏消息(收藏面板用)
        viewModelScope.launch {
            sessionRepository.observeAllFavorites().collect { favs ->
                _state.update { it.copy(favoriteMessages = favs, isFavoritesLoading = false) }
            }
        }
        // v1.104 U7: 观察已命名的收藏分组标签(供 FavoritesScreen 顶部 FilterChip 渲染)
        viewModelScope.launch {
            sessionRepository.observeAllFavoriteTags().collect { tags ->
                _state.update { it.copy(favoriteTags = tags) }
            }
        }
        // Phase 8.4: 观察 Web 搜索配置(同步 webSearchEnabled / webSearchConfig)
        viewModelScope.launch {
            settings.webSearchConfigFlow.collect { cfg ->
                _state.update {
                    it.copy(
                        webSearchConfig = cfg,
                        webSearchEnabled = cfg.enabled,
                    )
                }
            }
        }
        // v0.34: 观察图片生成默认参数配置
        viewModelScope.launch {
            settings.imageGenConfigFlow.collect { cfg ->
                _state.update {
                    it.copy(
                        imageGenParams = io.zer0.ai.image.ImageGenParams(
                            model = cfg.modelId,
                            size = cfg.size,
                            quality = cfg.quality,
                            style = cfg.style,
                            responseFormat = cfg.responseFormat,
                            n = cfg.n,
                        ),
                    )
                }
            }
        }
        // Phase 8.5: 观察当前 Assistant 绑定的快捷消息
        // 用 flatMapLatest 在 currentAssistant 变化时自动切到新 Assistant 的快捷消息流
        viewModelScope.launch {
            _state
                .map { it.currentAssistant?.id ?: "default" }
                .distinctUntilChanged()
                .flatMapLatest { astId ->
                    quickMessageRepository.observeForAssistant(astId)
                }
                .collect { list ->
                    _state.update { it.copy(quickMessages = list) }
                }
        }
        // v1.97 (P1-1): Lorebook / PromptInjection / allQuickMessages 改为懒加载
        // (refreshLorebooks / refreshPromptInjections / refreshAllQuickMessages),
        // 由 LorebookScreen / PromptInjectionScreen / QuickMessageScreen 进入时触发。
        // 这三项只在管理页使用,不在聊天主流程读取,无需常驻 Flow 收集器。
        // AssistantDetailPages 已用 rememberFlowList 独立收集,不依赖 ChatViewModel state。

        // v1.58: 订阅 Prompt 模板列表
        viewModelScope.launch {
            settings.promptTemplatesFlow.collect { templates ->
                _state.update { it.copy(promptTemplates = templates) }
            }
        }
        // Phase 9.1 (M13): 观察文件夹列表(Drawer 分组渲染用)
        viewModelScope.launch {
            folderRepository.observeAll().collect { folders ->
                _state.update { it.copy(folders = folders) }
            }
        }
        // Phase 9.3 (M2): 观察 ASR 配置(SYSTEM/DashScope/Step)
        viewModelScope.launch {
            settings.asrConfigFlow.collect { cfg ->
                _state.update { it.copy(asrConfig = cfg) }
            }
        }
        // v0.31: 订阅聊天行为偏好(MessageBubble/ChatScreen/InputBar 据此控制渲染与交互)
        viewModelScope.launch {
            settings.chatPreferencesFlow.collect { prefs ->
                _state.update { it.copy(chatPreferences = prefs) }
            }
        }
        // v1.97 (P1-1): shareTemplate 改为 exportSessionAsMarkdown 内 first() 加载,
        // 不再常驻 Flow 收集器(只在导出时用一次)。
        // v0.33: 订阅媒体配置(TTS / 录音采样率等)
        viewModelScope.launch {
            settings.mediaConfigFlow.collect { cfg ->
                _state.update { it.copy(mediaConfig = cfg) }
            }
        }
        // v1.25: 订阅多 Agent 协作配置(团队列表与总开关)
        viewModelScope.launch {
            settings.multiAgentConfigFlow.collect { cfg ->
                _state.update { it.copy(multiAgentConfig = cfg) }
            }
        }
        // v1.43: 监听应用级生成状态。切页后新 ViewModel 创建时,若同一会话仍在后台生成,
        // 自动把 isStreaming 置 true,UI 会显示停止按钮并继续观察消息流。
        // v1.136 修复:按 isAgentMode 区分当前会话,避免 Agent/任务模式互相干扰。
        viewModelScope.launch {
            chatGenerationManager.activeGeneration.collect { gen ->
                val state = _state.value
                val currentSessionId = if (state.isAgentMode) state.agentSessionId else state.currentSessionId
                if (gen != null && gen.sessionId == currentSessionId && gen.isStreaming) {
                    _state.update { it.copy(isStreaming = true) }
                }
            }
        }
        // v1.0.4: 收集视觉辅助分析进度,驱动 UI 显示"正在分析图片 2/4…"
        viewModelScope.launch {
            visionBridge.progressFlow.collect { progress ->
                _state.update {
                    it.copy(visionProgress = if (progress.isActive) progress else null)
                }
            }
        }
        // Phase 8.7: 注册 TTS 状态回调,驱动 isSpeaking / speakingMessageId
        // 回调来自后台线程(UtteranceProgressListener),用 update 保证线程安全
        ttsManager.onStateChange = { utteranceId, isSpeaking ->
            _state.update {
                it.copy(
                    isSpeaking = isSpeaking,
                    speakingMessageId = utteranceId?.let { id ->
                        runCatching { Uuid.parse(id) }.getOrNull()
                    },
                )
            }
        }
        // v2.3: 订阅任务模型路由配置
        viewModelScope.launch {
            settings.taskRoutingConfigFlow.collect { config ->
                _state.update { it.copy(taskRoutingEnabled = config.enabled) }
            }
        }
        // Phase 10.3: 启动时检查 FTS 索引一致性,不一致则后台 rebuild
        // - v8→v9 迁移后 messages_fts 为空,首次启动会全量索引历史消息
        // - ngram 转换是 CPU 密集,放 IO 线程;不阻塞 UI(万级消息约数百毫秒)
        // - 失败静默(只记日志),不影响主流程
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // H-01 修复: ensureFtsIndexConsistent 是 suspend,改用 resultOf 避免吞没 CancellationException
                resultOf { sessionRepository.ensureFtsIndexConsistent() }
                    .onError { msg, t -> Logger.w("ChatVM", "FTS ensureFtsIndexConsistent failed: $msg") }
            }
        }
        // v5: 消息发送队列消费者 — 串行处理,支持自动重试
        // v1.136 修复:Agent 模式与任务模式会话完全隔离,消费者按 isAgentMode 取当前会话 id,
        // 避免 Agent 页面的消息因 currentSessionId 指向任务会话而被跳过(导致只显示输入中动画)。
        viewModelScope.launch {
            for (req in sendChannel) {
                val state = _state.value
                val currentSid = if (state.isAgentMode) {
                    state.agentSessionId ?: req.sessionId
                } else {
                    state.currentSessionId ?: req.sessionId
                }
                if (currentSid != req.sessionId) {
                    // 会话已切换,该 req 被跳过 — 回滚 enqueueSend 的乐观更新,
                    // 移除属于该 req 的 user/assistant 消息并重置 isStreaming
                    _state.update {
                        val filtered = _messages.value.filterNot { msg ->
                            msg.id == req.userMessage.id || msg.id == req.assistantMessageId
                        }
                        _messages.value = filtered
                        it.copy(
                            isStreaming = false,
                            isWaitingFirstToken = false,
                        )
                    }
                    // v1.0.15: 消息未投递,删除 outbox
                    resultOf { sessionRepository.deleteOutbox(req.outboxId) }
                    continue
                }
                try {
                    // P0 修复: 直接复用 enqueueSend 创建的 userMessage(含原始 id + createdAt),
                    //   保证 user 消息的 createdAt 严格 < assistant 消息(assistantMsg.createdAt = userMsg.createdAt + 1),
                    //   切页重载按 createdAt ASC 排序时顺序正确(user 在前,assistant 在后)。
                    //   原实现 new UIMessage 会让 createdAt 取到消费时刻(晚于 assistantMsg.createdAt),
                    //   且 id 与乐观更新 id 不一致(导致 outbox 恢复时 messageExists 误判)。
                    sessionRepository.appendMessage(currentSid, req.userMessage)
                } catch (e: Exception) {
                    Logger.e("ChatVM", "appendMessage failed", e)
                    if (req.retryCount < 1) {
                        Logger.i("ChatVM", "重试发送 (attempt ${req.retryCount + 1})")
                        val retryResult = sendChannel.trySend(req.copy(retryCount = req.retryCount + 1))
                        if (retryResult.isFailure) {
                            Logger.w("ChatVM", "重试入队失败(队列已满)")
                            addError(ChatErrorType.UNKNOWN, appContext.getString(R.string.err_chat_msg_save_failed, e.message ?: appContext.getString(R.string.err_chat_unknown)))
                            _state.update { it.copy(isStreaming = false) }
                            // v1.0.15: 重试也失败,删除 outbox(消息无法投递)
                            resultOf { sessionRepository.deleteOutbox(req.outboxId) }
                        }
                    } else {
                        addError(ChatErrorType.UNKNOWN, appContext.getString(R.string.err_chat_msg_save_failed, e.message ?: appContext.getString(R.string.err_chat_unknown)))
                        _state.update { it.copy(isStreaming = false) }
                        // v1.0.15: 重试耗尽,删除 outbox
                        resultOf { sessionRepository.deleteOutbox(req.outboxId) }
                    }
                    continue
                }
                launchStream(assistantId = _messages.value.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
                    ?: kotlin.uuid.Uuid.random(), sessionId = currentSid)
                // v1.0.15: 生成已启动,outbox 完成使命,删除记录
                resultOf { sessionRepository.deleteOutbox(req.outboxId) }
            }
        }

        // v1.135: 把媒体生成类工具注册到 ToolRegistry,让 LLM 在对话中直接调用。
        registerMediaTools()

        // v1.201: 订阅委派链路 + 暂停请求,同步到 UiState
        viewModelScope.launch {
            delegationChainTracker.chains.collect { all ->
                val roots = all.values
                    .filter { it.parentRequestId == null }
                    .sortedBy { it.startedAt }
                _state.update { it.copy(delegationChain = roots) }
            }
        }
        viewModelScope.launch {
            delegationPauseManager.activePauses.collect { pauses ->
                _state.update { it.copy(activePauseRequest = pauses.values.firstOrNull()) }
            }
        }

        // v1.202: 订阅非阻塞委派结果回灌 —— 后台子 agent 完成后,把结果作为 interlude
        // 消息追加到当前会话,让用户看到子 agent 的工作产出。
        // 注意:consumeCompleted 会消费并清除,所以每次 emit 都要拉取当前会话的待回灌列表。
        viewModelScope.launch {
            deferredResultStore.completedTasks.collect { _ ->
                val sessionId = currentSessionIdForApproval() ?: return@collect
                val tasks = deferredResultStore.consumeCompleted(sessionId)
                if (tasks.isEmpty()) return@collect
                // 把每个完成的子 agent 结果作为一条 ASSISTANT interlude 消息追加到当前会话
                val interludes = tasks.map { task ->
                    val prefix = task.label?.let { "[${it}] " } ?: ""
                    val content = if (task.status == io.zer0.muse.tools.DeferredResultStore.TaskStatus.RESOLVED) {
                        "${prefix}子 agent 已完成任务:\n${task.result}"
                    } else {
                        "${prefix}子 agent 任务失败: ${task.error}"
                    }
                    UIMessage(role = MessageRole.ASSISTANT, content = content)
                }
                _messages.value = _messages.value + interludes
                // 同步持久化到 DB,确保切页/重启后仍可恢复
                for (msg in interludes) {
                    try {
                        sessionRepository.appendMessage(sessionId, msg)
                    } catch (e: Exception) {
                        Logger.w("ChatVM", "interlude appendMessage 失败", e)
                    }
                }
            }
        }

        // v1.202: 订阅活跃子 agent 线程 + 待处理任务,同步到 UiState 供 SubagentTaskListCard 渲染
        // listActiveThreads 是普通 List(非 Flow),通过 deferredResultStore.tasks 的 StateFlow emit
        // 间接触发重组(任务状态变化时一并刷新线程列表快照)
        viewModelScope.launch {
            deferredResultStore.tasks.collect { tasksMap ->
                val sid = currentSessionIdForApproval()
                val activeThreads = subagentThreadStore.listActiveThreads()
                    .filter { sid == null || it.parentSessionId == sid }
                val pendingTasks = tasksMap.values
                    .filter { it.status == io.zer0.muse.tools.DeferredResultStore.TaskStatus.PENDING }
                    .filter { sid == null || it.parentSessionId == sid }
                _state.update {
                    it.copy(
                        activeSubagentThreads = activeThreads,
                        pendingSubagentTasks = pendingTasks,
                    )
                }
            }
        }

        // v1.0.15: 恢复未完成的 outbox 消息(进程被杀后重启时)
        // 扫描 outbox 表,确保用户消息已持久化到 messages 表,然后删除 outbox 记录。
        // 用户打开对应会话时可看到消息并手动重发。
        viewModelScope.launch {
            val pending = resultOf { sessionRepository.getPendingOutbox() }.getOrNull()
            if (pending.isNullOrEmpty()) return@launch
            Logger.i("ChatVM", "outbox 恢复: ${pending.size} 条未完成消息")
            for (req in pending) {
                // 检查用户消息是否已持久化(消费端可能已 appendMessage 但进程在 launchStream 前被杀)
                val alreadySaved = resultOf { sessionRepository.messageExists(req.sessionId, req.userMessageId) }
                    .getOrNull() ?: false
                if (!alreadySaved) {
                    val images = runCatching {
                        idListJson.decodeFromString<List<String>>(req.imageBase64Json)
                    }.getOrDefault(emptyList())
                    resultOf {
                        // P0 修复: 复用 outbox 记录的 userMessageId + createdAt,
                        //   避免重新 new UIMessage 让 createdAt 取到恢复时刻(远晚于原 assistant 消息),
                        //   切页重载按 createdAt 排序时 user 消息会掉到 assistant 之下。
                        //   createdAt 用 outbox 写入时刻(≈ enqueueSend 时刻),早于 assistant 消息的持久化时间。
                        val userMsgId = runCatching { Uuid.parse(req.userMessageId) }.getOrElse {
                            Logger.w("ChatVM", "outbox userMessageId 非 UUID,回退随机 id: ${req.userMessageId}")
                            Uuid.random()
                        }
                        sessionRepository.appendMessage(req.sessionId, UIMessage(
                            id = userMsgId,
                            role = MessageRole.USER,
                            content = req.text,
                            imageBase64List = images,
                            createdAt = req.createdAt,
                        ))
                    }.onError { msg, t ->
                        Logger.w("ChatVM", "outbox 恢复 appendMessage 失败: $msg", t)
                    }
                }
                // outbox 完成使命(用户消息已持久化),删除记录
                resultOf { sessionRepository.deleteOutbox(req.id) }
            }
        }
    }

    /** v1.201: 用户提交委派暂停决策。 */
    fun submitPauseDecision(
        requestId: String,
        response: io.zer0.muse.tools.DelegationPauseManager.PauseResponse,
    ) {
        delegationPauseManager.submitDecision(requestId, response)
    }

    /** v1.201: 用户取消进行中的委派。 */
    fun cancelDelegation(requestId: String) {
        delegationPauseManager.cancelDelegation(requestId)
    }

    /**
     * v1.202: 取消后台子 agent 任务。
     *
     * 通过 [DeferredResultStore.abort] 把任务标记为 ABORTED,
     * 后台 SkillExecutor 检查到状态变化后会停止推进。
     */
    fun cancelSubagentTask(taskId: String) {
        viewModelScope.launch {
            deferredResultStore.abort(taskId)
        }
    }

    /** v1.135: 注册 generate_image / generate_video / generate_qr_code 等媒体工具。 */
    private fun registerMediaTools() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "generate_image",
                description = "根据用户描述生成图片。仅在用户明确要求画图、设计、头像、海报等场景调用。会消耗绘图 API 额度。",
                parameters = mapOf(
                    "prompt" to "必填,详细的图片描述,英文或中文均可",
                    "model" to "可选,绘图模型 ID,如 dall-e-3 / gpt-image-1 / agnes-image-2.1-flash;未指定时使用供应商默认",
                    "size" to "可选,图片尺寸,如 1024x1024 / 1792x1024 / 1024x1792;Agnes 也支持比例如 1:1 / 16:9 / 3:2",
                    "quality" to "可选,图片质量,如 standard / hd",
                    "style" to "可选,图片风格,如 vivid / natural",
                    "n" to "可选,生成数量,默认 1",
                    "reference_image" to "可选,参考图 URL 或 base64(用于图生图/图片编辑);非空时调用图生图端点。注意:LLM 无法访问用户本地相册,本地参考图由用户在工具审批卡片中从相册选择后注入,LLM 调用时无需也无法填入本参数",
                ),
                required = setOf("prompt"),
                riskLevel = ToolRiskLevel.HIGH,
                parameterTypes = mapOf("n" to "integer"),
            )
        ) { execGenerateImage(it) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "generate_video",
                description = "根据用户描述生成短视频。仅在用户明确要求视频、动画等场景调用。会自动选择已配置且支持视频输出的供应商/模型。",
                parameters = mapOf(
                    "prompt" to "必填,视频内容描述,英文或中文均可",
                    "model" to "可选,视频模型 ID;未指定时自动选择第一个支持视频输出的模型",
                    "provider_id" to "可选,供应商 ID;未指定时自动选择第一个支持视频输出的供应商",
                    "duration" to "可选,视频时长(秒),仅支持 5 或 10,默认 5",
                    "resolution" to "可选,分辨率,如 720p / 1080p,默认 720p",
                ),
                required = setOf("prompt"),
                riskLevel = ToolRiskLevel.HIGH,
                parameterTypes = mapOf("duration" to "integer"),
            )
        ) { execGenerateVideo(it) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "generate_qr_code",
                description = "把任意文本(如链接、WiFi 密码、联系方式)转换为二维码图片,并在对话中展示。",
                parameters = mapOf(
                    "content" to "必填,要编码成二维码的文本",
                    "size" to "可选,二维码边长像素,默认 400,范围 128-1024",
                ),
                required = setOf("content"),
                riskLevel = ToolRiskLevel.SAFE,
                parameterTypes = mapOf("size" to "integer"),
            )
        ) { execGenerateQrCode(it) }
    }

    fun updateInput(text: String) {
        // v1.0.47 P5: 用户手动编辑输入时退出历史导航,重置 inputHistoryIndex
        // v1.0.72: 草稿功能已砍掉(不再防抖写 DataStore)
        _state.update { it.copy(input = text, hasDraft = false, inputHistoryIndex = null) }
    }
    /**
     * v1.0.47 P5: 输入框上/下箭头回调,遍历本会话输入历史。
     *
     * 约定:[ChatUiState.inputHistory] 按"新→旧"顺序存储(index 0 = 最近一条)。
     * - direction < 0(上箭头):向更旧的历史移动
     *   - 当前未在导航中(index==null):跳到 index 0(最近一条)
     *   - 当前在 index i:跳到 i+1(更旧);已到末尾则不动
     * - direction > 0(下箭头):向更新的历史移动
     *   - 当前在 index 0:退出导航,清空输入
     *   - 当前在 index i>0:跳到 i-1
     *   - 未在导航中:不动
     * - direction == 0:无操作
     * - 历史为空时直接返回
     *
     * 用户手动编辑输入(见 [updateInput])会重置 [ChatUiState.inputHistoryIndex] 为 null,
     * 再次按上箭头会从最近一条开始。
     */
    fun navigateInputHistory(direction: Int) {
        if (direction == 0) return
        val history = _state.value.inputHistory
        if (history.isEmpty()) return
        val current = _state.value.inputHistoryIndex
        val next: Int? = when {
            direction < 0 -> {
                // 上箭头:向更旧
                val candidate = (current ?: -1) + 1
                if (candidate >= history.size) return // 已到末尾,保持当前
                candidate
            }
            else -> {
                // 下箭头:向更新
                val c = current ?: return // 未在导航中,不动
                val candidate = c - 1
                if (candidate < 0) null else candidate
            }
        }
        if (next == null) {
            // 退出导航,清空输入
            _state.update { it.copy(input = "", inputHistoryIndex = null) }
        } else {
            _state.update { it.copy(input = history[next], inputHistoryIndex = next) }
        }
    }

    /**
     * v1.0.47 P5-3: 打开 Token 计数菜单。
     *
     * 仅在 [ChatUiState.tokenEstimateEnabled] 开启时由 UI 调用。
     * 计算一次性快照(当前输入 + 历史消息 + 模型上下文窗口),存入 state 供菜单展示,
     * 避免每次按键都做 BPE 编码(性能开销大)。
     *
     * BPE 编码是 CPU 密集型操作,在 Dispatchers.Default 上执行;
     * [settings.getSelectedModel] 是 suspend,需在协程中调用。
     * 历史消息 token 数复用 [TokenEstimator.estimate] 的 messages 重载,
     * 已计入 reasoning/mood/reflection/toolCalls.arguments 与每条 4 token 固定开销。
     */
    fun showTokenCountMenu() {
        val input = _state.value.input
        val messages = _messages.value
        viewModelScope.launch(Dispatchers.Default) {
            val inputTokens = TokenEstimator.estimate(input)
            val historyTokens = TokenEstimator.estimate(messages)
            val contextWindow = resultOf { settings.getSelectedModel() }
                .getOrNull()
                ?.contextWindow
                ?.takeIf { it > 0 }
            val snapshot = TokenCountSnapshot(
                inputTokens = inputTokens,
                historyTokens = historyTokens,
                contextWindow = contextWindow,
            )
            _state.update { it.copy(tokenSnapshot = snapshot, tokenCountVisible = true) }
        }
    }

    /** v1.0.47 P5-3: 关闭 Token 计数菜单。 */
    fun dismissTokenCountMenu() {
        _state.update { it.copy(tokenCountVisible = false) }
    }

    /** 清空全部错误(向后兼容入口,UI"关闭"按钮调用)。 */
    fun clearError() {
        clearErrors()
    }

    /** P5-D/E: UI 层报告错误(语音失败、文件解析失败等)。v0.49: 走 addError,支持 5 秒自动消失。 */
    fun reportError(msg: String) {
        addError(ChatErrorType.UNKNOWN, msg)
    }

    // ── v0.49: 多错误管理 ──────────────────────────────────────────────────

    /**
     * v0.49: 追加一条错误到 [ChatUiState.errors]。
     *
     * 可恢复错误([isRecoverable]=true)5 秒后自动移除(避免堆积);
     * 不可恢复错误(如 API key 无效)需用户手动 dismiss。
     */
    fun addError(type: ChatErrorType, message: String, isRecoverable: Boolean = true) {
        val error = ChatError(type = type, message = message, isRecoverable = isRecoverable)
        _state.update { it.copy(errors = it.errors + error) }
        // 5 秒后自动移除(如果是可恢复的)
        if (isRecoverable) {
            viewModelScope.launch {
                delay(5000)
                dismissError(error.id)
            }
        }
    }

    /** v0.49: 移除指定 id 的错误(用户手动 dismiss)。 */
    fun dismissError(errorId: String) {
        _state.update { it.copy(errors = it.errors.filter { er -> er.id != errorId }) }
    }

    /** v0.49: 清空全部错误。 */
    fun clearErrors() {
        _state.update { it.copy(errors = emptyList()) }
    }

    /**
     * v0.49: 根据异常消息分类错误类型(用于 launchStream 主要 catch 块)。
     *
     * v1.0.1 (P4): 改用 [ProviderError] 类型分类,替代原字符串 contains 匹配。
     * v1.0.27 Phase 5-B: 优先走类型路径 `(throwable as? ProviderException)?.providerError`,
     * 兜底才用 [inferFromMessage] 字符串推断 (向后兼容)。
     *
     *  - ProviderError.Network → NETWORK(IOException / timeout / 连接断开)
     *  - ProviderError.RateLimit → RATE_LIMIT(429 / RESOURCE_EXHAUSTED)
     *  - ProviderError.ServerError → NETWORK(5xx / 529 overloaded,可重试)
     *  - ProviderError.AuthError → API_KEY(401 / 403)
     *  - 其余 → UNKNOWN
     */
    private fun classifyErrorType(message: String, throwable: Throwable? = null): ChatErrorType {
        // 优先走类型路径 (Provider 已抛 ProviderException)
        val providerError = (throwable as? ProviderException)?.providerError
            ?: inferFromMessage(message, throwable)
        return when (providerError) {
            is ProviderError.Network -> ChatErrorType.NETWORK
            is ProviderError.RateLimit -> ChatErrorType.RATE_LIMIT
            // v1.0.1 (P4): 5xx 纳入 NETWORK(可重试),原落入 UNKNOWN 不重试
            is ProviderError.ServerError -> ChatErrorType.NETWORK
            is ProviderError.AuthError -> ChatErrorType.API_KEY
            is ProviderError.InvalidRequest -> ChatErrorType.UNKNOWN
            is ProviderError.Cancelled -> ChatErrorType.UNKNOWN
            is ProviderError.Unknown -> ChatErrorType.UNKNOWN
            null -> ChatErrorType.UNKNOWN
        }
    }

    /**
     * v1.0.17: 等待网络恢复(StreamInterrupted 智能续传用)。
     *
     * 优先使用 [NetworkMonitor.isOnline] StateFlow(实时反映网络状态);
     * 若 [NetworkMonitor] 不可用则回退到 [isNetworkAvailable] 同步轮询 ConnectivityManager。
     *
     * @param timeoutMs 最大等待时间(毫秒),超时返回 false
     * @return true 表示网络已恢复;false 表示超时未恢复
     */
    private suspend fun waitForNetworkRecovery(timeoutMs: Long): Boolean {
        // 当前已在线则立即返回(避免无谓等待)
        if (networkMonitor.isOnline.value) return true
        // withTimeoutOrNull 超时返回 null → false;轮询期间任一来源显示在线即返回 true
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            // 轮询网络状态(每 NETWORK_POLL_INTERVAL_MS 检查一次),兼容 NetworkMonitor callback 未触发的场景
            while (!networkMonitor.isOnline.value && !isNetworkAvailable()) {
                kotlinx.coroutines.delay(NETWORK_POLL_INTERVAL_MS)
            }
            true
        } ?: false
    }

    /**
     * v1.0.17: 同步检查网络是否可用(回退方案,当 [NetworkMonitor] 不可用时使用)。
     *
     * 基于 ConnectivityManager.activeNetwork + NET_CAPABILITY_INTERNET 判断,
     * 与 [NetworkMonitor] 的 checkOnline 逻辑一致(但不要求 VALIDATED,更宽松)。
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Logger.w("ChatVM", "isNetworkAvailable check failed: ${e.message}")
            false
        }
    }

    /**
     * 任务 3: 统一网络错误提示文案。
     * 按异常消息关键词分类,给用户更友好的中文提示。
     */
    private fun classifyNetworkError(e: Throwable): String {
        val raw = e.message ?: ""
        val resolved = ErrorMessages.resolve(appContext, raw)
        if (resolved != raw) return resolved
        val msg = raw.lowercase()
        return when {
            msg.contains("unable to resolve") || msg.contains("unknownhost") -> appContext.getString(R.string.err_chat_network_unresolvable)
            msg.contains("timeout") -> appContext.getString(R.string.err_chat_network_timeout)
            msg.contains("401") || msg.contains("403") -> appContext.getString(R.string.err_chat_auth_invalid)
            msg.contains("429") -> appContext.getString(R.string.err_chat_rate_limited)
            msg.contains("500") || msg.contains("502") || msg.contains("503") -> appContext.getString(R.string.err_chat_server_error)
            msg.contains("stream") || msg.contains("eof") -> appContext.getString(R.string.err_chat_stream_broken)
            else -> appContext.getString(R.string.err_chat_request_failed, e.localizedMessage?.take(80) ?: appContext.getString(R.string.err_chat_unknown))
        }
    }

    // ── v0.45: 上下文 token 占用估算 ──────────────────────────────────────

    /** v0.45: 缓存的 system prompt 文本(避免流式过程中每 50 字符都重建)。 */
    private var cachedSystemPrompt: String = ""

    /**
     * 静态 system prompt 快照。
     *
     * 静态部分(人格/风格/用户画像/记忆/工具清单/纪律/安全/MOOD/Artifact 等)
     * 在同一会话内连续发消息时复用,只追加动态"当前时间"section。
     */
    private var cachedStaticSystemPrompt: String = ""
    /**
     * 静态快照失效 key。当 assistant、settings、chatPreferences 等变化时,
     * key 改变,触发重建。
     */
    private var cachedStaticSnapshotKey: String = ""

    /**
     * v0.45: 刷新上下文信息(切换会话/发送/停止/压缩后调用)。
     *
     * 1. 读取当前选中模型的 contextWindow → 写入 contextMaxTokens
     * 2. 重建 system prompt 并缓存(SystemPromptAssembler.build)
     * 3. 立即更新 token 计数
     *
     * 这是 suspend 方法,在 viewModelScope.launch 中调用。
     */
    private suspend fun refreshContextInfo() {
        // 1. 加载模型的 contextWindow
        val model = resultOf { settings.getSelectedModel() }.getOrNull()
        // 模型未声明 contextWindow 时,用 ModelContextWindowRegistry 按 id 前缀兜底;
        // 仍查不到则用 32768 作为通用 fallback(多数现代模型至少 32K)
        val maxTokens = model?.contextWindow
            ?: ModelContextWindowRegistry.lookup(model?.id ?: "")
            ?: 32768
        // 2. 重建 system prompt(6 步工作流第 1 步,含人格/记忆/工具等)
        // 拆分为静态快照 + 动态时间,静态部分在同一会话内复用。
        val assistant = _state.value.currentAssistant
            ?: assistantRepository.getById("default")
        val memoryEnabled = assistant?.memoryEnabled ?: true
        val timeReminderEnabled = assistant?.enableTimeReminder ?: true
        val effectiveMemoryEnabled = memoryEnabled && settings.isMemoryEnabled()
        // v1.0.72: 本会话不参考记忆标志
        val state = _state.value
        val effSessionId = if (state.isAgentMode) state.agentSessionId else state.currentSessionId
        val sessionIgnoreMemory = state.sessions
            .firstOrNull { it.id == effSessionId }?.ignoreMemory ?: false
        // 2.1 重建并缓存静态快照
        val staticSnapshot = resultOf {
            systemPromptAssembler.buildStaticSnapshot(
                assistant = assistant,
                memoryEnabled = effectiveMemoryEnabled,
                ignoreMemory = sessionIgnoreMemory,
            )
        }.getOrNull() ?: ""
        cachedStaticSystemPrompt = staticSnapshot
        cachedStaticSnapshotKey = computeStaticSnapshotKey(assistant, effectiveMemoryEnabled)
        // 2.2 组合完整 system prompt(静态快照 + 当前时间)
        val dynamicSection = if (timeReminderEnabled) systemPromptAssembler.buildDynamicSection() else ""
        cachedSystemPrompt = buildString {
            if (staticSnapshot.isNotBlank()) append(staticSnapshot)
            if (dynamicSection.isNotBlank()) {
                if (isNotEmpty()) append("\n\n---\n\n")
                append(dynamicSection)
            }
        }
        // 3. 更新 state(contextMaxTokens + contextTokenCount)
        // v1.97 性能修复: TokenEstimator.estimate 是 CPU 密集操作(jtokkit BPE 编码),
        // 对长历史消息列表(200 条 × 几百字符)单次可达 50-200ms。
        // 原先在主线程同步执行,流式期间每秒叠加一次,直接造成卡顿。
        // 现移到 Dispatchers.Default 后台线程执行,结果回主线程写 state。
        val msgsSnapshot = _messages.value
        val sysPromptSnapshot = cachedSystemPrompt
        val tokenCount = withContext(Dispatchers.Default) {
            TokenEstimator.estimate(msgsSnapshot, sysPromptSnapshot)
        }
        _state.update {
            it.copy(
                contextMaxTokens = maxTokens,
                contextTokenCount = tokenCount,
            )
        }
    }

    /**
     * 计算静态 system prompt 快照的失效 key。
     *
     * 当 assistant 配置、settings、chatPreferences 等发生变化时,key 改变,
     * 触发 [launchStream] 重建静态快照。
     */
    private fun computeStaticSnapshotKey(assistant: AssistantEntity?, memoryEnabled: Boolean): String {
        val prefs = _state.value.chatPreferences
        // v1.0.47 P3: 会话级 skill 覆盖影响工具列表,加入缓存键(随 currentSessionId 变化失效)
        val state = _state.value
        val effectiveSessionId = if (state.isAgentMode) state.agentSessionId else state.currentSessionId
        val sessionSkillHash = state.sessions
            .firstOrNull { it.id == effectiveSessionId }?.skillIdsJson?.hashCode() ?: 0
        // v1.0.72: 本会话不参考记忆标志加入缓存键
        val sessionIgnoreMemory = state.sessions
            .firstOrNull { it.id == effectiveSessionId }?.ignoreMemory ?: false
        return buildString {
            append(assistant?.id ?: "null")
            append("|")
            append(assistant?.updatedAt ?: 0)
            append("|")
            append(assistant?.systemPrompt?.hashCode() ?: 0)
            append("|")
            append(assistant?.toolIdsJson?.hashCode() ?: 0)
            append("|")
            append(assistant?.skillIdsJson?.hashCode() ?: 0)
            append("|")
            append(sessionSkillHash)
            append("|")
            append(assistant?.memoryEnabled ?: true)
            append("|")
            append(memoryEnabled)
            append("|")
            append(settings.experienceEnabledCache)
            append("|")
            append(_state.value.multiAgentConfig.enabled)
            append("|")
            append(prefs.showMoodBlock)
            append("|")
            append(prefs.responseStyle)
            append("|")
            append(prefs.responseTone)
            append("|")
            append(sessionIgnoreMemory)
        }
    }

    /**
     * v0.45: 快速更新 token 计数(流式过程中每 200 字符或 1000ms 调用)。
     *
     * 使用 [cachedSystemPrompt] 避免每次都重建 system prompt(IO 密集)。
     * 非流式场景应调用 [refreshContextInfo](会重建 system prompt + 加载 contextWindow)。
     *
     * v1.97 性能修复: 改为 suspend,TokenEstimator.estimate(jtokkit BPE 编码,CPU 密集)
     * 移到 Dispatchers.Default 执行。原先在主线程同步,长历史下单次 50-200ms,
     * 流式期间每秒叠加一次,是卡顿的主要根因。
     */
    private suspend fun updateContextTokenCount() {
        // 先在当前线程 snapshot(避免 withContext 切换后 _state 被其他协程修改导致读到中间态)
        val msgsSnapshot = _messages.value
        val sysPromptSnapshot = cachedSystemPrompt
        // v1.79 (M-CV6): try-catch 防止 TokenEstimator 异常中断流式
        val tokenCount = withContext(Dispatchers.Default) {
            runCatching {
                TokenEstimator.estimate(msgsSnapshot, sysPromptSnapshot)
            }.onFailure { Logger.w("ChatVM", "TokenEstimator failed: ${it.message}") }.getOrDefault(0)
        }
        _state.update { it.copy(contextTokenCount = tokenCount) }
    }

    /**
     * 退出对话时触发 AI 摘要命名。
     * 仅当标题仍为默认值"新会话"且有至少一轮完整对话时触发。
     */
    fun autoTitleOnExit(sessionId: String) {
        autoTitleSession(sessionId)
    }

    /**
     * 功能2: 对话自动命名。
     *
     * 流式完成后,若会话标题为默认值(如"新会话"或空),调用 LLM 用 6 字以内概括对话。
     * 只在至少有一条 user 消息时触发,避免空对话生成无意义标题。
     */
    private fun autoTitleSession(sessionId: String) {
        val state = _state.value
        val title = state.sessions.firstOrNull { it.id == sessionId }?.title ?: return
        val defaultTitle = appContext.getString(R.string.session_repo_default_title)
        if (title.isNotBlank() && title != defaultTitle) return
        val messages = _messages.value.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        if (messages.size < 2) return
        val preview = messages.take(4).joinToString("\n") { it.content.take(100) }
        if (preview.isBlank()) return
        viewModelScope.launch(AppDispatchers.io) {
            // v1.0.52: 读取用户自定义对话命名 prompt(null/空串表示用默认)
            val customTitlePrompt = resultOf { settings.customTitlePromptFlow.first() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            val prompt = if (customTitlePrompt != null) {
                "$customTitlePrompt\n\n$preview"
            } else {
                "请用4到8个字概括以下对话的主题,直接输出标题文字,不要加引号或其他标点:\n\n$preview"
            }
            resultOf {
                val completion = retryOnNetworkError {
                    chatService.completeText(
                        messages = listOf(UIMessage(role = MessageRole.USER, content = prompt)),
                    )
                }
                completion.text.trim().removeSurrounding("\"").removeSurrounding("'").take(20)
            }.onSuccess { newTitle ->
                if (newTitle.isNotBlank()) {
                    sessionRepository.renameSession(sessionId, newTitle)
                }
            }.onError { _, _ -> }
        }
    }

    /**
     * v1.42: 自动上下文压缩触发器。
     *
     * 当 token 占用超过 80% 时,在后台 IO 线程压缩历史,不阻塞 UI。
     * 只压缩内存中的 messages,不持久化到 DB(DB 保留完整历史)。
     */
    private fun triggerAutoCompress(sessionId: String) {
        val maxTokens = _state.value.contextMaxTokens
        val currentTokens = _state.value.contextTokenCount
        if (maxTokens <= 0 || currentTokens <= 0) return
        val ratio = currentTokens.toFloat() / maxTokens
        if (ratio <= AUTO_COMPRESS_TOKEN_RATIO) return
        val currentMessages = _messages.value
        if (currentMessages.size < 2) return

        viewModelScope.launch(AppDispatchers.io) {
            val keepRecent = minOf(MANUAL_COMPRESS_KEEP_RECENT, currentMessages.size - 1).coerceAtLeast(1)
            val compressContext = TransformContext(
                sessionId = sessionId,
                modelId = _state.value.currentAssistant?.modelId,
                extras = mapOf(
                    "compress_enabled" to true,
                    "compress_threshold" to 1,
                    "compress_keep_recent" to keepRecent,
                ),
            )
            // H-01 修复: transform 是 suspend 函数,改用 resultOf 避免吞没 CancellationException
            val compressed = resultOf {
                contextCompressTransformer.transform(currentMessages, compressContext)
            }.onError { msg, t ->
                Logger.w("ChatVM", "Auto-compress transform failed: $msg")
            }.getOrNull() ?: currentMessages
            if (compressed.size < currentMessages.size) {
                // v1.80 (H-CVM2): 压缩在后台 IO 异步执行,期间用户可能已发送新消息。
                // 不能用 compressed 直接覆盖整个 messages 列表(会丢失新增消息)。
                // 仅替换被压缩的旧区间(currentMessages),保留之后新增的消息。
                _state.update { state ->
                    val newAppended = if (_messages.value.size >= currentMessages.size) {
                        _messages.value.drop(currentMessages.size)
                    } else {
                        // 消息被截断/删除,直接用压缩结果
                        emptyList()
                    }
                    _messages.value = compressed + newAppended
                    state
                }
                updateContextTokenCount()
                Logger.i("ChatVM", "Auto-compress triggered: ratio=${"%.2f".format(ratio)}, ${currentMessages.size} → ${compressed.size} 条")
            }
        }
    }

    /**
     * v0.45: 手动触发上下文压缩(记忆压缩常态化)。
     *
     * @param updateMemoryFirst true = 先调 [MemoryTicker.forceCompileNow] 更新记忆(fact/摘要),
     *                          再压缩历史;false = 只压缩历史(纯压缩)
     *
     * UI 按钮文案统一为"更新并压缩",默认调用 manualCompress(updateMemoryFirst = true)。
     * 纯压缩模式暂不暴露 UI(内部用),未来可长按按钮弹选择菜单。
     *
     * 压缩结果只替换内存中的 messages(_messages.value),不持久化到 DB
     * (DB 保留完整历史用于搜索/导出,内存版本用于 LLM 上下文)。
     * 切换会话后从 DB 重新加载,下次发送时自动压缩器会再次处理。
     */
    fun manualCompress(updateMemoryFirst: Boolean = true) {
        val sessionId = _state.value.currentSessionId ?: return
        val currentMessages = _messages.value
        // 至少 2 条消息才压缩(否则 toCompress 为空,无意义)
        if (currentMessages.size < 2) {
            reportError(appContext.getString(R.string.err_chat_compress_too_few))
            return
        }
        if (_state.value.isStreaming || _state.value.isCompressing) return

        _state.update { it.copy(toolsState = it.toolsState.copy(isCompressing = true)) }
        viewModelScope.launch(AppDispatchers.io) {
            try {
                // 1. 可选:先更新记忆(强制提炼 fact + deep memory + 刷新 today)
                if (updateMemoryFirst) {
                    val model = resultOf { settings.getSelectedModel() }.getOrNull()
                    resultOf {
                        memoryTicker.forceCompileNow(model = model)
                    }.onError { msg, t ->
                        Logger.w("ChatVM", "forceCompileNow failed: $msg")
                        // v1.78 (#31): 记忆更新失败时提示用户,不阻断后续压缩
                        MuseToast.show(appContext.getString(R.string.err_chat_compress_memory_failed))
                    }
                }
                // 2. 压缩历史:用 contextCompressTransformer 直接 transform
                // threshold=1 强制触发(只要 messages.size > keepRecent 就压缩)
                // keepRecent 自适应:保留最近 min(MANUAL_COMPRESS_KEEP_RECENT, size-1) 条,确保至少压缩 1 条
                val keepRecent = minOf(MANUAL_COMPRESS_KEEP_RECENT, currentMessages.size - 1).coerceAtLeast(1)
                val context = TransformContext(
                    sessionId = sessionId,
                    modelId = _state.value.currentAssistant?.modelId,
                    extras = mapOf(
                        "compress_enabled" to true,
                        "compress_threshold" to 1,  // 强制触发
                        "compress_keep_recent" to keepRecent,
                    ),
                )
                // H-01 修复: transform 是 suspend 函数,改用 resultOf 避免吞没 CancellationException
                val compressed = resultOf {
                    contextCompressTransformer.transform(currentMessages, context)
                }.onError { msg, t ->
                    Logger.w("ChatVM", "manualCompress transform failed: $msg")
                }.getOrNull() ?: currentMessages  // 失败时保留原消息
                // 3. 替换内存中的 messages(不持久化,DB 保留完整历史)
                if (compressed.size < currentMessages.size) {
                    // v1.117: 修复消息丢失竞态 — 压缩是 suspend LLM 调用,耗时数秒,
                    // 期间用户可能继续发送新消息(已 append 到 _messages.value)。
                    // 直接用旧快照的 compressed 覆盖会丢弃这些新消息。
                    // 对齐 triggerAutoCompress(line 940-948)的修复:保留压缩期间新增的消息。
                    _state.update { state ->
                        val newAppended = if (_messages.value.size > currentMessages.size) {
                            _messages.value.drop(currentMessages.size)
                        } else {
                            emptyList()
                        }
                        _messages.value = compressed + newAppended
                        state
                    }
                    Logger.i("ChatVM", "manualCompress: ${currentMessages.size} → ${compressed.size} 条 (keepRecent=$keepRecent)")
                    // v1.78 (#33): 压缩成功反馈,让用户知道压缩生效
                    MuseToast.show(appContext.getString(R.string.err_chat_compress_done, currentMessages.size, compressed.size))
                } else if (updateMemoryFirst) {
                    // 压缩未生效(可能消息太少或 LLM 返回空摘要),但记忆已更新
                    MuseToast.show(appContext.getString(R.string.err_chat_compress_no_need))
                }
                // 4. 刷新 token 计数(重建 system prompt 因为记忆可能已更新)
                refreshContextInfo()
            } catch (e: Exception) {
                Logger.w("ChatVM", "manualCompress failed: ${e.message}")
                reportError(appContext.getString(R.string.err_chat_compress_failed, e.message ?: ""))
            } finally {
                _state.update { it.copy(toolsState = it.toolsState.copy(isCompressing = false)) }
            }
        }
    }

    /**
     * v1.97: 执行斜杠命令。
     *
     * 解析输入文本,若为斜杠命令则执行对应操作并返回 true(表示已处理,不应发送给 LLM)。
     * 非 / 开头或未知命令返回 false。
     *
     * @return true 表示已作为斜杠命令执行(不应发送),false 表示不是斜杠命令
     */
    fun executeSlashCommand(text: String): Boolean {
        val cmd = SlashCommand.parse(text) ?: return false
        val sessionId = _state.value.currentSessionId
        when (cmd) {
            SlashCommand.NEW -> {
                // 新建会话 — 复用现有 createNewSession(内部会创建 DB 会话并切换状态)
                MuseToast.show(appContext.getString(R.string.slash_command_new_done))
                createNewSession()
            }
            SlashCommand.COMPACT -> {
                // 压缩会话历史 — 纯压缩,不先更新记忆
                MuseToast.show(appContext.getString(R.string.slash_command_compact_done))
                manualCompress(updateMemoryFirst = false)
            }
            SlashCommand.RESET -> {
                // 重置上下文 — 清空内存中的消息(不删 DB),下次发送时从 DB 重新加载
                _messages.value = emptyList()
                MuseToast.show(appContext.getString(R.string.slash_command_reset_done))
            }
            SlashCommand.PIN -> {
                // 切换置顶 — 复用现有 togglePinned
                val id = sessionId ?: run {
                    MuseToast.show(appContext.getString(R.string.slash_command_unknown, text))
                    return true
                }
                togglePinned(id)
                MuseToast.show(appContext.getString(R.string.slash_command_pin_done))
            }
            SlashCommand.ARCHIVE -> {
                // 归档当前会话 — 复用现有 setSessionArchived(内部会切换到剩余会话)
                val id = sessionId ?: run {
                    MuseToast.show(appContext.getString(R.string.slash_command_unknown, text))
                    return true
                }
                setSessionArchived(id, true)
                MuseToast.show(appContext.getString(R.string.slash_command_archive_done))
            }
        }
        // 清空输入框
        _state.update { it.copy(input = "") }
        return true
    }

    /** 切换侧栏开合。 */
    fun toggleDrawer(open: Boolean) {
        _state.update { it.copy(isDrawerOpen = open) }
    }

    /** P5-G: 切换绘图模式。开启后输入栏 placeholder 变化,send 走 ImageService。 */
    fun toggleDrawMode() {
        if (_state.value.isStreaming) return
        val newMode = !_state.value.isDrawMode
        _state.update {
            it.copy(
                isDrawMode = newMode,
                // 退出绘图模式时清空临时参考图
                imageGenParams = if (!newMode) it.imageGenParams.copy(referenceImageUri = null)
                else it.imageGenParams,
            )
        }
    }

    /** v0.34: 更新当前绘图参数(可临时覆盖设置默认值)。 */
    fun updateImageGenParams(params: io.zer0.ai.image.ImageGenParams) {
        _state.update { it.copy(imageGenParams = params) }
    }

    /**
     * 阶段 5: 切换激活 Provider(底部模型切换面板调用)。
     *
     * v1.0.28 修复: 之前注释说"内部会清空 selectedModelId"但实际未清空,
     * 导致用户从 A Provider 切到 B Provider 后,旧 selectedModelId 仍指向 A 的模型,
     * resolveToolsAndModel 跨 Provider 查找命中 A 的模型 → 请求误发到 A。
     * 现显式调用 saveSelectedModel(null) 清空,对齐注释承诺。
     *
     * v1.22: 若目标 Provider 尚未拉取到模型,自动触发 /models 拉取。
     */
    fun setActiveProvider(providerId: String) {
        if (_state.value.isStreaming) return
        viewModelScope.launch {
            settings.setActiveProvider(providerId)
            // v1.0.28: 切换 Provider 必须清空旧 selectedModelId,否则跨 Provider 误用旧模型
            settings.saveSelectedModel(null)
            val provider = _state.value.providers.firstOrNull { it.id == providerId }
            if (provider != null && provider.models.isEmpty() && provider.apiKey.isNotBlank()) {
                refreshModels(providerId)
            }
        }
    }

    /**
     * v1.22: 手动/自动拉取指定 Provider 的上游模型列表。
     * 拉取成功后更新 ProviderConfig.models 并持久化,失败则写入 fetchModelsError。
     *
     * v1.132: 拉取成功后同步写入 [ModelListCache],让 ProviderSection 编辑页能复用结果(5 分钟内)。
     */
    fun refreshModels(providerId: String) {
        if (_state.value.isFetchingModels) return
        val provider = _state.value.providers.firstOrNull { it.id == providerId } ?: return
        viewModelScope.launch {
            _state.update { it.copy(isFetchingModels = true, fetchModelsError = null) }
            // H-01 修复: listModels 是 suspend,改用 resultOf 避免吞没 CancellationException
            val result = resultOf {
                withContext(Dispatchers.IO) {
                    ProviderRegistry.create(provider).listModels(provider)
                }
            }
            _state.update { it.copy(isFetchingModels = false) }
            result.onSuccess { models ->
                if (models.isEmpty()) {
                    _state.update { it.copy(fetchModelsError = appContext.getString(R.string.err_chat_fetch_models_empty)) }
                } else {
                    // v1.132: 写入缓存,ProviderSection 编辑页 5 分钟内复用
                    io.zer0.ai.core.ModelListCache.put(provider, models)
                    val updated = provider.copy(models = models)
                    settings.updateProvider(updated)
                    _state.update { it.copy(fetchModelsError = null) }
                }
            }.onError { _, t ->
                val msg = t?.message ?: appContext.getString(R.string.err_chat_fetch_models_failed)
                _state.update {
                    it.copy(
                        fetchModelsError = when {
                            msg.contains("401") || msg.contains("403") -> appContext.getString(R.string.err_chat_auth_invalid)
                            msg.contains("Unable to resolve") || msg.contains("UnknownHost") -> appContext.getString(R.string.err_chat_fetch_models_no_server)
                            msg.contains("timeout", ignoreCase = true) -> appContext.getString(R.string.err_chat_fetch_models_timeout)
                            msg.contains("404") -> appContext.getString(R.string.err_chat_fetch_models_not_supported)
                            else -> appContext.getString(R.string.err_chat_fetch_models_failed_msg, msg.take(120))
                        }
                    )
                }
            }
        }
    }

    /**
     * 阶段 5: 选择当前 Provider 下的具体模型(底部模型切换面板调用)。
     * 传 null 清空选择,回退到 Provider 的首个模型。
     *
     * v0.51: 切换非空模型后,弹 Toast 提示"已切换模型,新消息将使用新模型生成(历史消息不变)"。
     * 走独立 toast 字段(非 Snackbar),避免被错误消息挤掉。
     */
    fun setSelectedModel(modelId: String?) {
        if (_state.value.isStreaming) return
        viewModelScope.launch {
            val prevId = _state.value.selectedModelId
            settings.saveSelectedModel(modelId)
            // v0.51: 仅在真正切换到不同模型(modelId 非空且与之前不同)时弹 Toast,
            // 避免用户点已选中的模型或清空选择(modelId=null)时也弹提示。
            if (modelId != null && modelId != prevId) {
                _state.update {
                    it.copy(toast = appContext.getString(R.string.err_chat_model_switched_toast))
                }
            }
        }
    }

    /** v1.60-A: 设置工具模型(null 清除,沿用主对话模型)。 */
    fun setToolModel(modelId: String?) {
        viewModelScope.launch {
            settings.saveToolModel(modelId)
        }
    }

    /**
     * v0.51: 清空一次性 toast(Toast 弹出后由 UI 立即调用,避免重组时重复弹)。
     */
    fun clearToast() {
        _state.update { it.copy(toast = null) }
    }

    /**
     * v1.0.72: 设置当前会话"不参考记忆"标志(空白对话页选项)。
     * 持久化到会话,切换会话/重启保持;开启后 system prompt 跳过记忆注入。
     */
    fun setSessionIgnoreMemory(ignore: Boolean) {
        val sessionId = if (_state.value.isAgentMode) {
            _state.value.agentSessionId
        } else {
            _state.value.currentSessionId
        } ?: return
        viewModelScope.launch {
            sessionRepository.setSessionIgnoreMemory(sessionId, ignore)
            // 更新本地会话状态(驱动 EmptyChatGuide 开关 + system prompt 缓存键)
            _state.update { st ->
                st.copy(
                    sessions = st.sessions.map {
                        if (it.id == sessionId) it.copy(ignoreMemory = ignore) else it
                    },
                )
            }
            // 记忆开关影响静态快照,刷新上下文
            refreshContextInfo()
        }
    }

    /** 设置/取消引用回复目标。 */
    fun setReplyingTo(message: UIMessage?) {
        // v1.0.72: 加日志排查"叉不掉引用"问题(点击 X 后引用块是否收到清除事件)
        Logger.i("ChatVM", "setReplyingTo: ${if (message == null) "null(清除引用)" else message.id}")
        _state.update { it.copy(replyingTo = message, replyQuoteOverride = null) }
    }

    /** v1.57: 设置自定义引用文本(用于引用卡片编辑裁剪,精准引用部分内容)。 */
    fun setReplyQuoteOverride(text: String?) {
        _state.update { it.copy(replyQuoteOverride = text?.takeIf { it.isNotBlank() }) }
    }

    /**
     * v1.58: 从指定消息处分叉对话 — 创建新会话并复制到该消息为止的历史,然后切换过去。
     * 用户可在新会话中探索不同方向,不影响原对话。
     */
    fun forkSessionFromMessage(messageId: kotlin.uuid.Uuid) {
        val sourceSessionId = _state.value.currentSessionId ?: return
        if (_state.value.isStreaming) detachStreaming()
        viewModelScope.launch {
            try {
                val newId = sessionRepository.forkSession(sourceSessionId, messageId.toString())
                if (newId != null) {
                    switchSession(newId)
                }
            } catch (e: Exception) {
                Logger.w("ChatViewModel", "forkSession failed: ${e.message}")
                reportError(appContext.getString(R.string.err_chat_fork_failed, e.message ?: ""))
            }
        }
    }

    /** 新建会话。 */
    fun createNewSession() {
        if (_state.value.isStreaming) detachStreaming()
        // Phase 8.7: 切换会话时停止 TTS(避免跨会话继续朗读)
        stopTts()
        // v1.91: 释放流式 ASR(避免跨会话继续占用麦克风)
        disposeAsr()
        // 通知 ticker: 旧 session 结束
        notifySessionEndForCurrent()
        // v1.x: 清理旧会话的"本会话允许"临时缓存(会话结束自动失效)
        currentSessionIdForApproval()?.let { sessionPermissionStore.clearSession(it) }
        // v1.0.72: 草稿功能已砍掉,不再保存旧会话输入
        val currentSession = _state.value.currentSessionId
        // v1.x: ConversationSessionManager 引用计数 — 释放旧会话(新会话 id 在异步块内创建后再 acquire)
        currentSession?.let { sessionManager.release(it) }
        viewModelScope.launch {
            // v1.0.63: 新任务使用设置里的默认助手
            val currentAssistantId = settings.defaultAssistantIdFlow.first().ifBlank { "default" }
            val id = sessionRepository.createSession(assistantId = currentAssistantId)
            // v1.x: 新会话权限模式跟随全局默认(修复:新建会话硬编码 ASK,完全放权设置被绕过)
            val permissionMode = sessionPermissionStore.getMode(
                id,
                settings.defaultSessionPermissionModeFlow.first(),
            )
            // v1.x: 获取新会话引用(与 switchSession 的 acquire 配对)
            sessionManager.acquire(id)
            val assistant = assistantRepository.getById(currentAssistantId)
                ?: assistantRepository.getById("default")
            _messages.value = emptyList()
            _state.update {
                it.copy(
                    currentSessionId = id,
                    input = "",
                    hasDraft = false,
                    errors = emptyList(),
                    isDrawerOpen = false,
                    currentAssistant = assistant,
                    // v1.110: 读取 ChatPreferences.defaultDeepThinking 作为新会话初始值
                    deepThinkingEnabled = it.chatPreferences.defaultDeepThinking,
                    // v1.99: 新会话清空 taskCards,避免旧会话的工具调用胶囊/待办残留
                    taskCards = emptyMap(),
                    // v1.136: 新会话清空工具调用历史与 Agent 计划,避免跨会话残留
                    toolCallHistory = emptyList(),
                    agentPlans = emptyMap(),
                    // v1.0.47 P5: 新会话清空输入历史(本会话内内存态)
                    inputHistory = emptyList(),
                    inputHistoryIndex = null,
                    // 清空视觉辅助状态,避免跨会话残留
                    visionAssistedMessageIds = emptySet(),
                    visionProgress = null,
                    // P3: 新会话权限模式跟随全局默认(会话 store 无记录时用设置值)
                    sessionPermissionMode = permissionMode,
                )
            }
            // v0.45: 刷新上下文 token 占用(新会话 messages 为空,只加载 contextWindow)
            refreshContextInfo()
            // R-UI-02: 新建会话后同步持久化查看焦点。
            resultOf { settings.saveViewedSessionId(id) }
                .onError { msg, _ -> Logger.w("ChatVM", "saveViewedSessionId 失败: $msg") }
        }
    }

    /**
     * v1.97 gap8: 将文本发送到新会话。
     *
     * 原子地创建新会话、填充输入并触发发送,避免调用方在异步 createNewSession
     * 完成前调用 send() 导致消息丢失。
     */
    fun sendToNewChat(text: String) {
        if (_state.value.isStreaming) detachStreaming()
        stopTts()
        disposeAsr()
        notifySessionEndForCurrent()
        // v1.x: 清理旧会话的"本会话允许"临时缓存(会话结束自动失效)
        currentSessionIdForApproval()?.let { sessionPermissionStore.clearSession(it) }
        val currentSession = _state.value.currentSessionId
        val currentInput = _state.value.input
        // v1.x: ConversationSessionManager 引用计数 — 释放旧会话
        currentSession?.let { sessionManager.release(it) }
        viewModelScope.launch {
            if (currentSession != null && currentInput.isNotBlank()) {
                settings.saveChatDraft(currentSession, currentInput)
            }
            // v1.0.63: 新任务使用设置里的默认助手
            val currentAssistantId = settings.defaultAssistantIdFlow.first().ifBlank { "default" }
            val id = sessionRepository.createSession(assistantId = currentAssistantId)
            // v1.x: 新会话权限模式跟随全局默认
            val permissionMode = sessionPermissionStore.getMode(
                id,
                settings.defaultSessionPermissionModeFlow.first(),
            )
            // v1.x: 获取新会话引用
            sessionManager.acquire(id)
            val assistant = assistantRepository.getById(currentAssistantId)
                ?: assistantRepository.getById("default")
            _messages.value = emptyList()
            _state.update {
                it.copy(
                    currentSessionId = id,
                    input = text,
                    hasDraft = false,
                    errors = emptyList(),
                    isDrawerOpen = false,
                    currentAssistant = assistant,
                    deepThinkingEnabled = it.chatPreferences.defaultDeepThinking,
                    taskCards = emptyMap(),
                    toolCallHistory = emptyList(),
                    agentPlans = emptyMap(),
                    visionAssistedMessageIds = emptySet(),
                    visionProgress = null,
                    sessionPermissionMode = permissionMode,
                )
            }
            refreshContextInfo()
            send()
            // R-UI-02: 新建会话并发送时同步持久化查看焦点。
            resultOf { settings.saveViewedSessionId(id) }
                .onError { msg, _ -> Logger.w("ChatVM", "saveViewedSessionId 失败: $msg") }
        }
    }

    /**
     * v1.24: Agent 重启上下文 — 保留当前助手,新建一个空会话,
     * 让长期陪伴的 Agent 从零开始继续对话,同时 Toast 提示用户。
     */
    fun restartContext() {
        if (_state.value.isStreaming) detachStreaming()
        stopTts()
        notifySessionEndForCurrent()
        // v1.x: 清理旧会话的"本会话允许"临时缓存(会话结束自动失效)
        currentSessionIdForApproval()?.let { sessionPermissionStore.clearSession(it) }
        // v1.x: ConversationSessionManager 引用计数 — 释放旧会话(新会话 id 在异步块内创建后再 acquire)
        currentSessionIdForApproval()?.let { sessionManager.release(it) }
        viewModelScope.launch {
            val currentAssistantId = _state.value.currentAssistant?.id ?: "default"
            // v1.28: Agent 模式下创建 Agent 会话,不污染任务列表
            val id = if (_state.value.isAgentMode) {
                sessionRepository.createAgentSession(assistantId = currentAssistantId)
            } else {
                sessionRepository.createSession(assistantId = currentAssistantId)
            }
            // v1.x: 获取新会话引用
            sessionManager.acquire(id)
            val assistant = assistantRepository.getById(currentAssistantId)
                ?: assistantRepository.getById("default")
            _state.update {
                if (it.isAgentMode) {
                    _messages.value = emptyList()
                    it.copy(
                        agentSessionId = id,
                        input = "",
                        errors = emptyList(),
                        isDrawerOpen = false,
                        currentAssistant = assistant,
                        // v1.99: 重启上下文清空 taskCards
                        taskCards = emptyMap(),
                        // v1.136: 重启上下文清空工具调用历史与 Agent 计划
                        toolCallHistory = emptyList(),
                        agentPlans = emptyMap(),
                        // 清空视觉辅助状态,避免跨会话残留
                        visionAssistedMessageIds = emptySet(),
                        visionProgress = null,
                    )
                } else {
                    _messages.value = emptyList()
                    it.copy(
                        currentSessionId = id,
                        input = "",
                        errors = emptyList(),
                        isDrawerOpen = false,
                        currentAssistant = assistant,
                        // v1.99: 重启上下文清空 taskCards
                        taskCards = emptyMap(),
                        // v1.136: 重启上下文清空工具调用历史与 Agent 计划
                        toolCallHistory = emptyList(),
                        agentPlans = emptyMap(),
                        // 清空视觉辅助状态,避免跨会话残留
                        visionAssistedMessageIds = emptySet(),
                        visionProgress = null,
                    )
                }
            }
            refreshContextInfo()
            _state.update { it.copy(toast = appContext.getString(R.string.err_chat_context_restarted_toast)) }
            // R-UI-02: 任务模式下重启上下文后同步持久化查看焦点。
            if (!_state.value.isAgentMode) {
                resultOf { settings.saveViewedSessionId(id) }
                    .onError { msg, _ -> Logger.w("ChatVM", "saveViewedSessionId 失败: $msg") }
            }
        }
    }

    /**
     * v1.53-A1: 分页加载会话的最近消息(初始加载,取最近 MESSAGE_PAGE_SIZE 条)。
     *
     * @return Pair(messages, hasMoreHistory) — messages 为升序列表,hasMoreHistory 表示是否还有更早的历史可加载
     */
    private suspend fun loadMessagesPaged(sessionId: String): Pair<List<UIMessage>, Boolean> {
        val total = sessionRepository.getMessageCount(sessionId)
        if (total == 0) return emptyList<UIMessage>() to false
        val limit = minOf(MESSAGE_PAGE_SIZE, total)
        val messages = sessionRepository.getRecentMessages(sessionId, limit)
        return messages to (total > messages.size)
    }

    /** 切换到指定会话。 */
    fun switchSession(sessionId: String) {
        if (_state.value.isStreaming) detachStreaming()
        // Phase 8.7: 切换会话时停止 TTS(避免跨会话继续朗读)
        stopTts()
        // v1.91: 释放流式 ASR(避免跨会话继续占用麦克风)
        disposeAsr()
        // 通知 ticker: 旧 session 结束
        notifySessionEndForCurrent()
        // v1.201: 切换会话清空委派链路 + 暂停状态,避免跨会话残留
        delegationChainTracker.clear()
        delegationPauseManager.clearAll()
        // v1.x: 清理旧会话的"本会话允许"临时缓存(会话结束自动失效)
        currentSessionIdForApproval()?.let { sessionPermissionStore.clearSession(it) }
        // 功能2: 保存当前输入为旧会话草稿
        val currentSession = _state.value.currentSessionId
        val currentInput = _state.value.input
        // v1.x: ConversationSessionManager 引用计数 — 释放旧会话 + 获取新会话
        if (currentSession != null && currentSession != sessionId) {
            sessionManager.release(currentSession)
        }
        sessionManager.acquire(sessionId)
        // v1.93+: 切换前把当前会话消息快照存入 LRU 缓存,切回时可直接命中避免 DB 查询。
        // v1.0.44: 如果有变体分支则不缓存，强制从 DB 加载完整变体列表
        if (currentSession != null && _conversationTree.value.userNodes.none { it.variants.size > 1 || it.currentVariant?.assistantNodes?.any { a -> a.variants.size > 1 } == true }) {
            sessionMemoryCache.put(currentSession, _messages.value)
        }
        viewModelScope.launch {
            if (currentSession != null && currentInput.isNotBlank()) {
                settings.saveChatDraft(currentSession, currentInput)
            }
        }
        viewModelScope.launch {
            // v1.97: 先读取后台生成状态,既用于恢复 isStreaming,也用于判断缓存是否可用。
            // 后台正在生成的会话其消息持续变化,需走 DB 加载最新,跳过缓存避免读到过期快照。
            // chatGenerationManager.activeGeneration 在生成期间 isStreaming=true,结束后自动 false。
            val activeGen = chatGenerationManager.activeGeneration.value
            val isBackgroundStreaming = activeGen?.sessionId == sessionId && activeGen.isStreaming
            // v1.93+: 优先查内存 LRU 缓存,命中且非后台生成时直接复用,跳过 DB 查询。
            val cached = if (!isBackgroundStreaming) sessionMemoryCache.get(sessionId) else null
            val memoryCacheHit = cached != null
            val (messages, hasMore) = if (cached != null) {
                Logger.d("ChatVM", "switchSession 内存缓存命中: id=$sessionId, 消息数=${cached.size}")
                // 缓存条目数达页大小时乐观认为还有更早历史(可上滑加载更多);
                // 若实际已全部加载完,loadMoreHistory 会查 DB 得空并自动置 hasMoreHistory=false,自纠正。
                cached to (cached.size >= MESSAGE_PAGE_SIZE)
            } else {
                // v1.53-A1: 未命中缓存,分页加载,只取最近 MESSAGE_PAGE_SIZE 条,避免一次性加载全部
                loadMessagesPaged(sessionId)
            }
            // P3: 加载本会话的权限模式(未单独设置时跟随全局默认,修复"完全放权"设置不生效)
            val permissionMode = sessionPermissionStore.getMode(
                sessionId,
                settings.defaultSessionPermissionModeFlow.first(),
            )
            // Phase 8.2: 加载会话绑定的 Assistant
            val assistantId = sessionRepository.getAssistantId(sessionId)
            val assistant = assistantRepository.getById(assistantId)
                ?: assistantRepository.getById("default")
            // 功能2: 恢复目标会话的输入草稿
            // v1.0.72: 草稿功能已砍掉(定位复杂且会恢复已完成消息,弊大于利)
            _state.update {
                _messages.value = messages
                it.copy(
                    currentSessionId = sessionId,
                    input = "",
                    hasDraft = false,
                    errors = emptyList(),
                    isDrawerOpen = false,
                    currentAssistant = assistant,
                    memoryCacheHit = memoryCacheHit,
                    deepThinkingEnabled = it.chatPreferences.defaultDeepThinking,
                    hasMoreHistory = hasMore,
                    isLoadingMore = false,
                    lastHistoryLoadCount = 0,
                    isStreaming = isBackgroundStreaming,
                    taskCards = emptyMap(),
                    pendingToolApprovals = emptyList(),
                    toolCallHistory = emptyList(),
                    agentPlans = emptyMap(),
                    // v1.x: 切换会话时清除挂死的会话列表加载错误(协程取消遗留)
                    sessionsError = null,
                    // v1.0.16: visionAssistedMessageIds 按 messageId(全局唯一)存储,
                    // 切换会话不再清空 — 切回原会话时"已分析"标签仍应显示。
                    // 仅清空 visionProgress(进度是瞬态的,不跨会话保留)。
                    visionProgress = null,
                    // v1.0.16: 切换会话清空待发送图片,避免跨会话泄漏到新会话的 InputBar
                    pendingImages = emptyList(),
                    // v1.136 T10: 同步清空待发送文档
                    pendingDocuments = emptyList(),
                    // v1.0.72 fix: 切换会话清空引用(避免引用残留到其他会话,
                    //   导致"模型回复从别的会话带过来的消息")
                    replyingTo = null,
                    replyQuoteOverride = null,
                    // v1.0.47 P5: 切换会话清空输入历史(本会话内内存态,不跨会话保留)
                    inputHistory = emptyList(),
                    inputHistoryIndex = null,
                    // P3: 恢复本会话权限模式
                    sessionPermissionMode = permissionMode,
                    // v1.0.16: 切换 Tab/会话后默认滚动到最新消息底部
                    listFirstVisibleItemIndex = messages.lastIndex.coerceAtLeast(0),
                    listFirstVisibleItemScrollOffset = 0,
                )
            }
            if (isBackgroundStreaming) {
                sessionMemoryCache.remove(sessionId)
            }
            // R-UI-02: 用户实际查看的会话单独持久化,与生成会话互不覆盖。
            resultOf { settings.saveViewedSessionId(sessionId) }
                .onError { msg, _ -> Logger.w("ChatVM", "saveViewedSessionId 失败: $msg") }
            // v1.0.30: 标记回话时间戳，供 onAppForeground 判断是否需要强制刷新
            _lastSessionSwitchTimestamp = System.currentTimeMillis()
            _lastSessionSwitchId = sessionId
            // v0.45: 刷新上下文 token 占用(加载 contextWindow + 重建 system prompt)
            refreshContextInfo()
            // P0 对话树: 读取上次分支选择快照,重建时恢复用户/助手变体
            val treeSnapshot = treeSnapshotStore?.load(sessionId)
        rebuildConversationTree(previousOverride = treeSnapshot)
            // 切换会话取消所有待审批(防止幽灵审批卡片 + requestToolApproval 协程挂起)
            cancelAllPendingApprovals()
            // 断点续传:检查本会话是否有未完成的工具调用,有则更新 pendingToolCallCount
            // 让 ChatScreen 顶部 Banner 显示"上次有 N 个工具调用未完成"提示用户恢复
            val pendingCount = resultOf { PendingToolCallStore.getForChat(sessionId) }
                .onError { msg, t -> Logger.w("ChatVM", "switchSession getForChat 失败: $msg", t) }
                .getOrNull()?.size ?: 0
            if (pendingCount > 0) {
                _state.update { it.copy(pendingToolCallCount = pendingCount) }
                Logger.i("ChatVM", "switchSession 检测到 $pendingCount 个未完成工具调用,会话=$sessionId")
            } else {
                _state.update { it.copy(pendingToolCallCount = 0) }
            }
        }
    }

    /**
     * v1.28: 设置 Agent Tab 模式。
     *
     * Agent Tab 进入时调用:恢复或创建独立的 Agent 会话,不依赖任务的 currentSessionId。
     * 退出 Agent Tab 时(isAgentMode=false)恢复任务会话的消息。
     */
    fun setAgentMode(enabled: Boolean) {
        // v1.92: ChatViewModel 改为 single 后 onCleared 永不调用,
        // 切换 Tab 涉及会话切换,需在此停止 TTS/ASR/生成(与 switchSession 一致),
        // 否则 _messages.value 被覆盖后,生成闭包 update 到错误的消息列表。
        if (_state.value.isStreaming) detachStreaming()
        stopTts()
        disposeAsr()
        notifySessionEndForCurrent()
        // v1.x: ConversationSessionManager 引用计数 — 切 Tab 涉及会话切换,
        // 进入 Agent Tab 时释放任务会话引用,退出时释放 Agent 会话引用,与对应 acquire 配对。
        val prevSessionId = currentSessionIdForApproval()
        if (enabled) {
            // v1.137 B2: 修复 Agent 切换闪烁 — 先同步设置 isSwitchingSession=true 阻止 UI 渲染旧消息,
            // 再在协程中预加载消息,最后一次性更新状态(消息+模式+权限),消除空列表闪屏。
            _state.update { it.copy(isSwitchingSession = true) }
            viewModelScope.launch {
                // v1.0.54: 按"默认 Agent 助手"偏好(设置页切换/Agent Tab 内切换时同步)恢复
                //   该助手的 Agent 会话,没有则新建。替代原 getLatestAgentSession(全局最新),
                //   使设置页切换真正生效。
                // v1.0.72 修复: 偏好查不到时回退到全局最近 Agent 会话 —
                //   若用户实际用的助手与偏好不一致(如从未设置过偏好但用过其他助手),
                //   按偏好查会 miss 导致每次进入都新建空会话,
                //   表现为"Agent 对话不持久,只显示当前聊天的对话"。
                val preferredAgentId = settings.proactiveMessageConfigFlow.first()
                    .agentId.ifBlank { "default" }
                val agentSession = sessionRepository
                    .getRecentAgentByAssistant(preferredAgentId, 1).firstOrNull()
                    ?: sessionRepository.getLatestAgentSession()
                val sessionId = agentSession?.id
                    ?: sessionRepository.createAgentSession(preferredAgentId)
                // v1.x: 释放旧的任务会话引用 + 获取新的 Agent 会话引用
                prevSessionId?.let { sessionManager.release(it) }
                sessionManager.acquire(sessionId)
                // P3: 加载 Agent 会话的权限模式(未单独设置时跟随全局默认)
                val permissionMode = sessionPermissionStore.getMode(
                    sessionId,
                    settings.defaultSessionPermissionModeFlow.first(),
                )
                // v1.137 B2: 先预加载消息(不更新 UI),再一次性切换 — 消除闪烁
                val (messages, hasMore) = loadMessagesPaged(sessionId)
                val assistantId = sessionRepository.getAssistantId(sessionId)
                val assistant = assistantRepository.getById(assistantId)
                    ?: assistantRepository.getById("default")
                _messages.value = messages
                _state.update {
                    it.copy(
                        isAgentMode = true,
                        agentSessionId = sessionId,
                        isSwitchingSession = false,
                        currentAssistant = assistant,
                        errors = emptyList(),
                        hasMoreHistory = hasMore,
                        isLoadingMore = false,
                        lastHistoryLoadCount = 0,
                        // v1.0.72 fix: 进入 Agent 模式清空引用(防跨会话污染)
                        replyingTo = null,
                        replyQuoteOverride = null,
                        // v1.99: 进入 Agent 模式清空 taskCards
                        taskCards = emptyMap(),
                        // v1.136: 进入 Agent 模式清空工具调用历史与 Agent 计划
                        toolCallHistory = emptyList(),
                        agentPlans = emptyMap(),
                        // 清空视觉辅助状态,避免跨会话残留
                        visionAssistedMessageIds = emptySet(),
                        visionProgress = null,
                        // P3: 恢复 Agent 会话权限模式
                        sessionPermissionMode = permissionMode,
                        // v1.0.16: 切换 Tab/会话后默认滚动到最新消息底部
                        listFirstVisibleItemIndex = messages.lastIndex.coerceAtLeast(0),
                        listFirstVisibleItemScrollOffset = 0,
                        // v1.0.47 P6-3: 进入 Agent Mode 时锁定会话
                        isSessionLocked = true,
                    )
                }
                // v1.0.47 P6-2: 检测弱工具调用模型,设置降级提示
                val model = resultOf { settings.getSelectedModel() }.getOrNull()
                val weakHint = io.zer0.muse.tools.WeakToolUseDetector.getWeakToolHint(model)
                _state.update {
                    it.copy(
                        isWeakToolModel = weakHint != null,
                        weakToolHint = weakHint,
                    )
                }
                refreshContextInfo()
            }
        } else {
            // 退出 Agent 模式:恢复任务会话,并清空 Agent 会话 id 实现完全隔离
            // v1.136: 退出时清空 agentSessionId,避免切换到任务 Tab 后仍残留 Agent 会话状态。
            _state.update {
                it.copy(
                    isAgentMode = false,
                    agentSessionId = null,
                    taskCards = emptyMap(),
                    toolCallHistory = emptyList(),
                    agentPlans = emptyMap(),
                    // 清空视觉辅助状态,避免跨会话残留
                    visionAssistedMessageIds = emptySet(),
                    visionProgress = null,
                    // v1.0.47 P6-3: 退出 Agent Mode 时解锁会话
                    isSessionLocked = false,
                    // v1.0.47 P6-2: 清空弱工具模型提示
                    isWeakToolModel = false,
                    weakToolHint = null,
                    agentModeHint = null,
                )
            }
            // v1.x: 释放 Agent 会话引用(若存在),重新获取任务会话引用
            prevSessionId?.let { sessionManager.release(it) }
            _state.value.currentSessionId?.let { sid ->
                sessionManager.acquire(sid)
                viewModelScope.launch {
                    // v1.53-A1: 分页加载任务会话消息
                    val (messages, hasMore) = loadMessagesPaged(sid)
                    // P3: 恢复任务会话权限模式(未单独设置时跟随全局默认)
                    val permissionMode = sessionPermissionStore.getMode(
                        sid,
                        settings.defaultSessionPermissionModeFlow.first(),
                    )
                    val assistantId = sessionRepository.getAssistantId(sid)
                    val assistant = assistantRepository.getById(assistantId)
                        ?: assistantRepository.getById("default")
                    _messages.value = messages
                    _state.update {
                        it.copy(
                            currentAssistant = assistant,
                            hasMoreHistory = hasMore,
                            isLoadingMore = false,
                            lastHistoryLoadCount = 0,
                            // P3: 恢复任务会话权限模式
                            sessionPermissionMode = permissionMode,
                            // v1.0.16: 切换 Tab/会话后默认滚动到最新消息底部
                            listFirstVisibleItemIndex = messages.lastIndex.coerceAtLeast(0),
                            listFirstVisibleItemScrollOffset = 0,
                        )
                    }
                    refreshContextInfo()
                    // R-UI-02: 退出 Agent 模式后恢复并持久化任务会话焦点。
                    resultOf { settings.saveViewedSessionId(sid) }
                        .onError { msg, _ -> Logger.w("ChatVM", "saveViewedSessionId 失败: $msg") }
                }
            }
        }
    }

    /**
     * v1.0.47 P6-4: 关闭 Agent Mode 提示卡片(用户点击"知道了"后调用)。
     */
    fun dismissAgentModeHint() {
        _state.update { it.copy(agentModeHint = null) }
    }

    /**
     * v1.0.47 P6-2: 关闭弱工具模型提示(用户点击关闭后调用)。
     */
    fun dismissWeakToolHint() {
        _state.update { it.copy(weakToolHint = null) }
    }

    /**
     * v1.53-A1: 上滑加载更多历史消息。
     *
     * 取当前 messages 列表最早一条消息的 createdAt 作为锚点,从 DB 取早于该时间点的
     * MESSAGE_PAGE_SIZE 条消息,前置插入到 messages。
     *
     * - 流式期间禁用(isStreaming=true 时不加载,避免列表抖动干扰流式输出)
     * - isLoadingMore=true 时跳过(防止重复触发)
     * - hasMoreHistory=false 时跳过(已加载完)
     * - 加载完成后设置 [ChatUiState.lastHistoryLoadCount],UI 监听后调整滚动位置保持视觉位置
     */
    fun loadMoreHistory() {
        val state = _state.value
        if (state.isStreaming || state.isLoadingMore || !state.hasMoreHistory) return
        // v1.28: Agent 模式用 agentSessionId,任务模式用 currentSessionId
        val sessionId = if (state.isAgentMode) state.agentSessionId else state.currentSessionId
        val sessionIdSafe = sessionId ?: return
        val firstMsg = _messages.value.firstOrNull() ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            val older = sessionRepository.getOlderMessages(sessionIdSafe, firstMsg.createdAt, MESSAGE_PAGE_SIZE)
            if (older.isEmpty()) {
                _state.update { it.copy(hasMoreHistory = false, isLoadingMore = false) }
                return@launch
            }
            // v1.126: 重新读取最新 _messages.value,防止加载期间流式追加的新消息被覆盖
            val currentMessages = _messages.value
            val merged = older + currentMessages
            _messages.value = merged
            _state.update {
                it.copy(
                    hasMoreHistory = older.size >= MESSAGE_PAGE_SIZE,
                    isLoadingMore = false,
                    lastHistoryLoadCount = older.size,
                )
            }
        rebuildConversationTree()
        }
    }

    /**
     * v1.53-A1: 清空 [ChatUiState.lastHistoryLoadCount]。
     *
     * UI 在 [ChatScreen] 监听 lastHistoryLoadCount 变化(>0)后,调
     * [androidx.compose.foundation.lazy.LazyListState.scrollToItem] 跳过新插入的条数,
     * 保持视觉位置不跳动,然后调本方法清空(避免重组时重复跳转)。
     */
    fun clearHistoryLoadCount() {
        if (_state.value.lastHistoryLoadCount != 0) {
            _state.update { it.copy(lastHistoryLoadCount = 0) }
        }
    }

    /** v1.28: 获取当前助手 id(用于创建 Agent 会话)。 */
    private fun currentAssistantId(): String =
        _state.value.currentAssistant?.id ?: "default"

    /** 软删除会话。删除当前会话时自动切换到剩余的第一个;无剩余会话时清空状态,不创建新会话。 */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            sessionRepository.softDeleteSession(sessionId)
            // v1.x: 会话删除时释放该会话的浏览器实例
            browserManagerRegistry?.let { registry ->
                resultOf { registry.closeSession(sessionId) }
                    .onError { msg, _ -> Logger.w("ChatVM", "closeSession browser 失败: $msg") }
            }
            // v1.93+: 从内存 LRU 缓存移除,避免持有已删除会话的消息副本(防止内存泄漏与脏读)
            sessionMemoryCache.remove(sessionId)
            if (_state.value.currentSessionId == sessionId) {
                val remaining = sessionRepository.observeSessions().first()
                val target = remaining.firstOrNull()
                if (target != null) {
                    switchSession(target.id)
                } else {
                    // 无剩余会话时不创建新会话,currentSessionId 置 null,清空消息列表
                    _state.update {
                        _messages.value = emptyList()
                        it.copy(
                            currentSessionId = null,
                        )
                    }
                }
            }
        }
    }

    /** 重命名会话。 */
    fun renameSession(sessionId: String, title: String) {
        viewModelScope.launch {
            sessionRepository.renameSession(sessionId, title)
        }
    }

    /** v0.45: 切换会话归档状态。归档当前会话时切换到剩余首个会话;无剩余会话时清空状态,不创建新会话。 */
    fun setSessionArchived(sessionId: String, archived: Boolean) {
        viewModelScope.launch {
            sessionRepository.setArchived(sessionId, archived)
            if (_state.value.currentSessionId == sessionId && archived) {
                val remaining = sessionRepository.observeSessions().first()
                val target = remaining.firstOrNull()
                if (target != null) {
                    switchSession(target.id)
                } else {
                    // 无剩余会话时不创建新会话,currentSessionId 置 null,清空消息列表
                    _messages.value = emptyList()
                    _state.update {
                        it.copy(
                            currentSessionId = null,
                        )
                    }
                }
            }
        }
    }

    /** 重新加载会话列表(会话列表错误态重试)。 */
    fun retryLoadSessions() {
        viewModelScope.launch {
            _state.update { it.copy(isSessionsLoading = true, sessionsError = null) }
            try {
                val sessions = sessionRepository.observeSessions().first()
                _state.update { it.copy(sessions = sessions, isSessionsLoading = false, sessionsError = null) }
                if (_state.value.currentSessionId == null && !initializing) {
                    initializing = true
                    val target = sessions.firstOrNull()
                    if (target != null) {
                        switchSession(target.id)
                    } else {
                        createNewSession()
                    }
                }
            } catch (t: Throwable) {
                Logger.e("ChatVM", "retryLoadSessions failed", t)
                // v1.x: 协程取消必须重抛,不能显示为"请求失败: Job was cancelled"
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update {
                    it.copy(
                        isSessionsLoading = false,
                        sessionsError = appContext.getString(
                            R.string.err_chat_request_failed,
                            t.message ?: appContext.getString(R.string.err_chat_unknown),
                        ),
                    )
                }
            }
        }
    }

    /** 更新搜索框文本。空文本时清空结果。 */
    fun updateSearchQuery(query: String) = miscCoordinator.updateSearchQuery(query)

    /** 执行搜索。空查询忽略。 */
    fun search() = miscCoordinator.search()

    /** 清空搜索(返回会话列表)。 */
    fun clearSearch() = miscCoordinator.clearSearch()

    // ── v2.x: 消息内容搜索(Tab=1)+ 跳转滚动高亮 ──

    /** v2.x: 切换搜索页 Tab(0=会话, 1=消息内容)。 */
    fun switchSearchTab(tab: Int) = miscCoordinator.switchSearchTab(tab)

    /** v2.x: 执行消息内容搜索(FTS4 + snippet,失败回退 LIKE)。 */
    fun searchMessageContent() = miscCoordinator.searchMessageContent()

    /**
     * v2.x: 设置目标消息(从搜索结果点击跳转用)。
     *
     * 由 SearchScreen 点击消息项时调用,MainActivity 注入的跳转逻辑会先 switchSession,
     * 再调用本方法设置 targetMessageId / highlightedMessageId / searchHighlightQuery。
     */
    fun setTargetMessage(messageId: String?, query: String?) =
        miscCoordinator.setTargetMessage(messageId, query)

    /**
     * v2.x: 从搜索结果打开消息 — 先切换会话,等切换落地后再设置定位目标。
     *
     * 修复竞态: 此前 switchSession(异步) + setTargetMessage(立即) 并行,
     * ChatScreen 在旧会话里等目标消息 5 秒超时后清空 targetMessageId,
     * 等会话真正切过去时定位目标已丢,表现为"点击结果只回首页不定位"。
     */
    fun openMessageFromSearch(sessionId: String, messageId: String, query: String) {
        switchSession(sessionId)
        viewModelScope.launch {
            // 等待 switchSession 落地(currentSessionId 变为目标会话),超时 5s 放弃定位
            val switched = withTimeoutOrNull(5000L) {
                _state.filter { it.currentSessionId == sessionId }.first()
                true
            } ?: false
            if (switched) {
                setTargetMessage(messageId, query)
            } else {
                Logger.w("ChatVM", "openMessageFromSearch 等待会话切换超时: $sessionId")
            }
        }
    }

    /** v2.x: 消费目标消息 id(滚动定位完成后调用,避免重复触发)。 */
    fun consumeTargetMessage() = miscCoordinator.consumeTargetMessage()

    /** v2.x: 清空高亮消息 id(高亮窗口期结束后调用,停止高亮)。 */
    fun clearHighlightedMessage() = miscCoordinator.clearHighlightedMessage()

    /**
     * 发送当前输入。空文本(且无图片)或正在流式时忽略。
     * P5-G: 若 isDrawMode,走 [generateImage] 而非流式聊天。
     * Phase 8.6: 支持多模态 — 若 pendingImages 非空,把 base64 列表附在 USER 消息上。
     * v1.28: Agent 模式用 agentSessionId,无会话时自动创建(Agent 日常聊天不依赖任务)。
     */
    fun send() {
        val rawText = _state.value.input.trim()
        val images = _state.value.pendingImages
        val docs = _state.value.pendingDocuments
        // v1.136 T10: 合并待发送文档内容到消息文本(文档文本 + 用户输入)
        var text = buildSendText(rawText, docs.map { it.content })
        if (!canStartGeneration(text, images, _state.value.isStreaming, _isCreatingAgentSession)) return
        // v1.68: 引用回复必须把被引用内容拼进消息体,LLM 才能读到引用原文。
        // v1.0.72 fix: 用最新消息对象取引用内容 — 引用时捕获的旧对象可能 content 为空
        //   (流式消息内容实时更新,旧引用对象是流式中的空版本 → 引用 UI 为空 + 模型看不到)
        val replyingToLatest = _state.value.replyingTo?.let { r ->
            _messages.value.find { it.id == r.id } ?: r
        }
        val quoteText = _state.value.replyQuoteOverride?.takeIf { it.isNotBlank() }
            ?: replyingToLatest?.content?.takeIf { it.isNotBlank() }
        if (quoteText != null) {
            text = buildQuotedContent(quoteText, text)
        }

        // v1.28: Agent 模式用独立的 agentSessionId,无会话时自动创建
        // v1.79 (M-CV8): 用 isCreatingAgentSession 标志防止重入,避免快速双击创建两个会话
        val sessionId = if (_state.value.isAgentMode) {
            _state.value.agentSessionId ?: run {
                if (_isCreatingAgentSession) return
                _isCreatingAgentSession = true
                viewModelScope.launch {
                    try {
                        val id = sessionRepository.createAgentSession(currentAssistantId())
                        _state.update { it.copy(agentSessionId = id) }
                        // v1.53-A1: 分页加载 Agent 会话消息(新会话为空,同时重置 hasMoreHistory)
                        val (msgs, hasMore) = loadMessagesPaged(id)
                        _messages.value = msgs
                        _state.update {
                            it.copy(
                                hasMoreHistory = hasMore,
                                isLoadingMore = false,
                                lastHistoryLoadCount = 0,
                            )
                        }
                        enqueueSend(text, images, id)
                    } finally {
                        _isCreatingAgentSession = false
                    }
                }
                return
            }
        } else {
            _state.value.currentSessionId ?: return
        }

        if (_state.value.isDrawMode) {
            generateImage(text, sessionId)
            return
        }
        enqueueSend(text, images, sessionId)
    }

    /** v1.28: send 的内部实现(发消息 + 启动流式)。 */
    /**
     * v1.200: 尝试根据用户消息自动路由到更合适 Agent/团队。
     * 返回 true 表示已路由并填充 assistant 占位消息，调用方应跳过 launchStream。
     */
    private suspend fun maybeAutoRoute(
        text: String,
        assistantMessageId: Uuid,
        sessionId: String,
    ): Boolean {
        if (!settings.multiAgentConfigCache.autoRoutingEnabled) return false
        val currentId = currentAssistantId()
        // v2.x: LLM 语义路由开关开启时走 routeWithLlm,否则走规则路由。
        // routeWithLlm 内部会在 chatService 未注入或异常时自动降级到规则路由。
        val config = settings.multiAgentConfigCache
        val route = if (config.llmRoutingEnabled) {
            agentRouter.routeWithLlm(text, excludeAssistantId = currentId)
        } else {
            agentRouter.route(text, excludeAssistantId = currentId)
        }
        if (route.confidence < AUTO_ROUTE_CONFIDENCE_THRESHOLD || route.targetId.isNullOrBlank()) return false
        if (route.targetId == currentId) return false

        val targetType = when (route.targetType) {
            "team" -> DelegationContract.DelegationRequest.TargetType.TEAM
            else -> DelegationContract.DelegationRequest.TargetType.ASSISTANT
        }

        val request = DelegationContract.DelegationRequest(
            requestId = "auto-${System.currentTimeMillis()}",
            task = text,
            targetType = targetType,
            targetId = route.targetId,
            contextMessages = DelegationContextBuilder.build(
                sessionMessages = _messages.value,
                maxMessages = DelegationContextBuilder.DEFAULT_MAX_MESSAGES,
                includeImages = false,
            ),
            timeoutSec = 120,
        )

        // v1.202: 流式呈现 — 先显示"正在委派给 ${name}..."让用户感知中间过程,
        // 而非阻塞调用结束后一次性出现结果。isStreaming=true 让 MessageBubble 显示流式光标。
        val routeTargetName = route.targetName ?: route.targetId
        val pendingText = appContext.getString(R.string.chat_auto_route_prefix, routeTargetName) +
            "\n\n正在委派给 $routeTargetName ..."
        updateAssistant(assistantMessageId, pendingText, "", null, null, null, true)

        val result = skillExecutor.delegateAgent(request)
        val output = buildString {
            appendLine(appContext.getString(R.string.chat_auto_route_prefix, route.targetName ?: route.targetId))
            appendLine()
            if (result.success) {
                append(result.resultText)
            } else {
                append(result.error ?: appContext.getString(R.string.skill_unknown_error))
            }
        }

        updateAssistant(assistantMessageId, output, "", null, null, null, false)
        _state.update { it.copy(isStreaming = false) }
        persistCurrentAssistant(sessionId, assistantMessageId)
        return true
    }

    /**
     * Phase 8.6: 添加待发送图片(从 URI 读取,转 base64)。
     * @param uri 图片 URI(相册/相机/SAF)
     * @param context 用于 ContentResolver
     * @param asOcr 是否走 OCR 识别(若为 true,识别结果追加到输入框;否则作为视觉输入)
     *
     * v1.105: 委托至 [ImageGenCoordinator.pickImage]。
     */
    fun pickImage(uri: Uri, context: Context, asOcr: Boolean) {
        imageGenCoordinator.pickImage(uri, context, asOcr, ::reportError, ::addError)
    }

    /**
     * v1.135: 选取视频并提取关键帧加入待发送图片。
     * 当前降级为图片发送,视觉模型可通过关键帧理解视频内容。
     */
    fun pickVideo(uri: Uri, context: Context) {
        imageGenCoordinator.pickVideo(uri, context, ::reportError)
    }

    /** Phase 8.6: 移除指定索引的待发送图片。v1.105: 委托至 [ImageGenCoordinator]。 */
    fun removePendingImage(index: Int) {
        imageGenCoordinator.removePendingImage(index)
    }

    /**
     * P5-G + Phase 10.2: 生成图片。
     *
     * 按 Provider 类型分支:
     *  - OpenAI 兼容: 走 ImageService(独立 /images/generations 端点,返回 URL)
     *  - Gemini:      走 streamChat 多模态路径(responseModalities 含 image,收集 ImageDelta base64)
     *  - Anthropic:   不支持(Claude 无原生图像生成能力)
     *
     * Gemini 绘图结果以 data URI 存入 imageUrls(便于 MessageBubble 用 AsyncImage 渲染),
     * 同时存入 imageBase64List(多模态输入回传)。
     */
    private fun generateImage(prompt: String, sessionId: String) {
        imageGenCoordinator.generateImage(
            prompt, sessionId, ::addError,
        ) { id, content, reasoning, b64, urls, streaming ->
            updateAssistant(id, content, reasoning, b64, urls, null, streaming)
        }
    }

    /**
     * P5-E: 选取文档后解析文本,追加到输入框(以分隔符隔开)。
     * 支持 TXT / Markdown / PDF(原生 PdfRenderer)。
     *
     * v1.105: 委托至 [ChatDocumentCoordinator.pickDocument]。
     */
    fun pickDocument(uri: Uri, context: Context) {
        documentCoordinator.pickDocument(uri, context, ::reportError)
    }

    /** v1.136 T10: 移除待发送文档(用户在 InputBar 芯片上点击移除)。 */
    fun removePendingDocument(index: Int) {
        val docs = _state.value.pendingDocuments.toMutableList()
        if (index in docs.indices) {
            docs.removeAt(index)
            _state.update { it.copy(pendingDocuments = docs) }
        }
    }

    /**
     * v1.0.47 P5-2: 把大段粘贴文本作为 txt 附件加入 [ChatUiState.pendingDocuments]。
     *
     * 用户在输入框粘贴超阈值文本并选择"作为文件附加"时调用。
     * 命名带时间戳避免重名;内容截断到 [io.zer0.muse.ui.chat.ChatDocumentCoordinator.DOC_MAX_CHARS]。
     * 不清空输入框(保留用户已输入的其他内容)。
     */
    fun addPastedTextAsDocument(text: String) {
        if (text.isBlank()) return
        val maxChars = io.zer0.muse.ui.chat.ChatDocumentCoordinator.DOC_MAX_CHARS
        val safeContent = if (text.length > maxChars) text.take(maxChars) else text
        val stamp = java.text.SimpleDateFormat("MMdd_HHmm", java.util.Locale.getDefault())
            .format(java.util.Date())
        val doc = io.zer0.muse.ui.chat.PendingDocument(
            name = "粘贴文本_$stamp.txt",
            content = safeContent,
            charCount = text.length,
        )
        _state.update { it.copy(pendingDocuments = it.pendingDocuments + doc) }
    }

    /**
     * P5-F / P6-B3: 翻译指定消息到目标语言。
     *
     * v1.0.30 gap4.9: prompt 构建策略与 [TranslateViewModel.buildTranslationPrompt] 统一,
     * 支持可选 style 参数(默认"通用"),并对结果统一调用 stripThinkTags 处理。
     *
     * @param targetLanguage 目标语言中文名(如"中文"/"English"/"日本語"),默认"中文"兼容旧调用
     * @param style 翻译风格(默认"通用",与 TranslateViewModel.TRANSLATION_STYLES 对齐)
     * 翻译结果作为新的 ASSISTANT 消息追加("翻译(目标语言):\n\n...")。
     */
    fun translateMessage(messageId: Uuid, targetLanguage: String = "中文", style: String = "通用") {
        if (_state.value.isStreaming || _state.value.isTranslating) return
        val target = _messages.value.firstOrNull { it.id == messageId }
            ?: return
        if (target.content.isBlank()) return

        _state.update {
            it.copy(
                isTranslating = true,
                translatingMessageId = messageId,
                errors = emptyList(),
            )
        }
        translateJob = viewModelScope.launch(AppDispatchers.io) {
            try {
                // v1.0.30 gap4.9: 统一使用 TranslateViewModel.buildTranslationPrompt,与独立翻译页保持一致
                val prompt = io.zer0.muse.ui.translate.TranslateViewModel.buildTranslationPrompt(
                    text = target.content,
                    targetLanguage = targetLanguage,
                    sourceLanguage = io.zer0.muse.ui.translate.TranslateViewModel.SOURCE_AUTO,
                    style = style,
                )
                val messages = listOf(UIMessage(role = MessageRole.USER, content = prompt))
                val sessionId = _state.value.currentSessionId ?: return@launch

                // v1.0.4 (P2): 改为流式翻译 — 先插入占位 ASSISTANT 消息,逐 delta 更新内容,
                // 让用户看到"翻译(X):\n\n…"占位后立即开始流式输出,长文本不再"卡 5-10 秒后整体出现"。
                // (animateItem() 已提供 fade-in,无需额外动画)
                val placeholder = UIMessage(
                    role = MessageRole.ASSISTANT,
                    content = appContext.getString(R.string.err_chat_translate_prefix, targetLanguage),
                )
                _messages.value = _messages.value + placeholder

                val sb = StringBuilder()
                val prefix = appContext.getString(R.string.err_chat_translate_prefix, targetLanguage)
                try {
                    chatService.streamChat(messages = messages).collect { ev ->
                        if (ev is ChatStreamEvent.ContentDelta) {
                            sb.append(ev.delta)
                            // 增量更新最后一条消息(占位)的 content,前缀保持 "翻译(X):\n\n"
                            val updated = placeholder.copy(content = prefix + sb.toString())
                            _messages.value = _messages.value.map { m ->
                                if (m.id == placeholder.id) updated else m
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 取消时保留已收集的部分结果
                    throw e
                }

                val translated = io.zer0.muse.transformer.stripThinkTags(sb.toString()).trim()
                if (translated.isEmpty()) {
                    // 移除占位消息,改为错误提示
                    _messages.value = _messages.value.filter { m -> m.id != placeholder.id }
                    _state.update {
                        it.copy(
                            errors = listOf(ChatError(type = ChatErrorType.UNKNOWN, message = appContext.getString(R.string.err_chat_translate_empty))),
                            isTranslating = false,
                            translatingMessageId = null,
                        )
                    }
                    return@launch
                }
                // 最终化占位消息(确保 content 是清洗后的版本)
                val finalMsg = placeholder.copy(content = "$prefix$translated")
                _messages.value = _messages.value.map { m ->
                    if (m.id == placeholder.id) finalMsg else m
                }
                _state.update {
                    it.copy(
                        isTranslating = false,
                        translatingMessageId = null,
                    )
                }
                sessionRepository.appendMessage(sessionId, finalMsg)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // v1.80 (H-CVM1): 协程取消必须重抛,避免破坏 stop()/switchSession() 语义
                throw e
            } catch (t: Exception) {
                // v1.80 (L-CVM3): catch Throwable 改为 catch Exception,避免捕获 OOM/StackOverflow 等 Error
                Logger.e("ChatVM", "translate failed", t)
                _state.update {
                    it.copy(
                        errors = listOf(ChatError(type = ChatErrorType.UNKNOWN, message = appContext.getString(R.string.err_chat_translate_failed, t.message ?: ""))),
                        isTranslating = false,
                        translatingMessageId = null,
                    )
                }
            }
        }
    }

    /**
     * P5-H: 调用工具(简化版 MCP)。UI 暂未直接触发,留给 LLM 决策的扩展点。
     * 当前实现:返回工具列表 + 执行结果字符串。
     */
    suspend fun callTool(name: String, args: Map<String, String>): String {
        // v1.0.28 Phase 3: 改 resultOf 避免 toolRegistry.execute (suspend) 被吞 CancellationException
        // v1.0.53: execute 返回 ToolOutcome,取 content 保持 String 语义
        return resultOf { toolRegistry.execute(name, args) }.let { r ->
            when (r) {
                is io.zer0.common.Result.Success -> r.data.content
                is io.zer0.common.Result.Error -> {
                    Logger.e("ChatVM", "tool $name failed", r.throwable)
                    "工具执行失败: ${r.message}"
                }
            }
        }
    }

    /**
     * 应用用户编辑（P0 对话树）：把修改后的文本保存为新用户变体，
     * 保留旧提问/旧助手回复，新建用户版本并启动新回复流。
     */
    fun applyUserEdit(messageId: String, newContent: String) {
        if (_state.value.isStreaming) return
        val sessionId = _state.value.currentSessionId ?: _state.value.agentSessionId ?: return
        val tree = _conversationTree.value
        val target = tree.userNodes.asSequence()
            .flatMap { it.variants.asSequence() }
            .map { it.message }
            .firstOrNull { it.id.toString() == messageId && it.role == MessageRole.USER }
            ?: return
        val edit = tree.editUserMessage(target.id, newContent) ?: return
        _conversationTree.value = edit.tree
        _messages.value = edit.tree.displayMessages
        _state.update {
            it.copy(
                input = "",
                hasDraft = false,
                errors = emptyList(),
                isStreaming = true,
                isWaitingFirstToken = true,
            )
        }
        sessionMemoryCache.remove(sessionId)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                edit.newUserMessage?.let { sessionRepository.upsertMessage(sessionId, it) }
                edit.newAssistantPlaceholder?.let { sessionRepository.upsertMessage(sessionId, it) }
                edit.newUserMessage?.variantGroupId?.let { groupId ->
                    edit.newUserMessage?.variantCount?.let { count ->
                        runCatching { sessionRepository.updateVariantCount(groupId, count) }
                    }
                }
            }.onFailure { e -> Logger.e("ChatVM", "applyUserEdit persist failed", e) }
        }
        edit.newAssistantPlaceholder?.let { placeholder ->
            viewModelScope.launch {
                launchStream(placeholder.id, sessionId, isNewBranch = true)
            }
        }
    }

    fun editAssistantMessage(messageId: Uuid, newContent: String) {
        if (_state.value.isStreaming) return
        val sessionId = _state.value.currentSessionId ?: return
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId && it.role == MessageRole.ASSISTANT }
        if (index == -1) return
        val updated = messages[index].copy(content = newContent, reasoning = null)
        val newMessages = messages.toMutableList().apply { set(index, updated) }
        _messages.value = newMessages
        _state.update { it.copy(errors = emptyList()) }
        viewModelScope.launch {
            sessionRepository.updateMessageContent(sessionId, messageId, newContent)
        }
    }

    /**
     * B7-04: 流式打断后继续生成。
     *
     * 复用最后一条带 [已中断] 标记的 assistant 消息,去掉标记后以
     * resumeFromText 方式从断点续写,不新增消息。
     */
    fun continueGeneration() {
        if (_state.value.isStreaming) return
        val sessionId = if (_state.value.isAgentMode) {
            _state.value.agentSessionId ?: return
        } else {
            _state.value.currentSessionId ?: return
        }
        val messages = _messages.value
        val last = messages.lastOrNull() ?: return
        if (!canContinueGeneration(_state.value.isStreaming, last)) return
        val content = resumeFromInterrupted(last.content)
        val resumed = last.copy(content = content)
        _messages.value = _messages.value.map { if (it.id == last.id) resumed else it }
        rebuildConversationTree()
        _state.update {
            it.copy(
                isStreaming = true,
                isWaitingFirstToken = true,
                errors = emptyList(),
            )
        }
        launchStream(assistantId = last.id, sessionId = sessionId, continueFrom = resumed)
    }
    /**
     * 重生成最后一条 assistant 回复: 保留旧回复作为分支,创建新分支并重新请求。
     * 仅当最后一条是 assistant 且非流式时可用。
     */
    /**
     * 重生成当前用户变体下的最后一条 assistant 回复（P0 对话树）。
     * 保留旧回复为助手变体,在当前用户变体下新建助手变体并重新请求。
     */
    fun regenerateLastAssistant() {
        val sessionId = if (_state.value.isAgentMode) {
            _state.value.agentSessionId ?: return
        } else {
            _state.value.currentSessionId ?: return
        }
        val tree = _conversationTree.value
        if (!canRegenerate(
                isStreaming = _state.value.isStreaming,
                hasSession = true,
                hasSelectedUserVariant = tree.selectedUserNode != null && tree.selectedUserVariant != null,
            )
        ) return
        val update = tree.retryLastAssistant()
        val newMsg = update.newMessage ?: return
        _conversationTree.value = update.tree
        _messages.value = update.tree.displayMessages
        _state.update {
            it.copy(
                isStreaming = true,
                isWaitingFirstToken = true,
                errors = emptyList(),
            )
        }
        sessionMemoryCache.remove(sessionId)
        _pendingVariantInfo = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { sessionRepository.upsertMessage(sessionId, newMsg) }
                .onFailure { e -> Logger.e("ChatVM", "regenerate upsertMessage failed", e) }
            update.changedGroupId?.let { groupId ->
                runCatching { sessionRepository.updateVariantCount(groupId, newMsg.variantCount) }
            }
        }
        viewModelScope.launch {
            launchStream(newMsg.id, sessionId, isNewBranch = true)
        }
    }


    /**
     * 切换消息分支:用户通过 BranchSelector 左右箭头切换同一位置的多版本 assistant 回复。
     */
    /**
     * P0 对话树: 从当前 _messages 重建树,并同步显示列表。
     * 流式期间 _messages 是扁平事实源;发送/重试/编辑/切会话/流结束等稳定点调用。
     */
    private fun rebuildConversationTree(previousOverride: ConversationTree? = null) {
        // 只复用属于当前会话的树；切会话/Agent 时从消息列表重新建树，避免串会话。
        val sessionId = _state.value.currentSessionId ?: _state.value.agentSessionId
        val currentTree = if (sessionId != null && _conversationTreeSessionId == sessionId) {
            _conversationTree.value
        } else {
            ConversationTree()
        }
        // 流式期间 _messages 是扁平事实源;旧树 flat 可能落后于新追加的消息,
        // 若只用 flat 映射会把新用户消息/助手占位丢掉,导致新一轮生成看不到历史。
        // 旧树 flat 保留全部重试/编辑分支,current 保留最新内容与新追加消息,二者按 id 合并。
        val messages = mergeRebuildMessages(currentTree, _messages.value)
        if (messages.isEmpty()) {
            _conversationTree.value = ConversationTree()
            _conversationTreeSessionId = sessionId
            return
        }
        val previous = previousOverride ?: currentTree
        val tree = ConversationTree.build(messages, previous)
        _conversationTree.value = tree
        _conversationTreeSessionId = sessionId
        _messages.value = tree.displayMessages
        if (sessionId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                treeSnapshotStore?.save(sessionId, tree)
                healBranchCounts(sessionId, messages, tree)
            }
        }
    }

    /** v1.0.63: 把归一化后的分支索引/计数回写数据库,修复历史坏数据。 */
    private suspend fun healBranchCounts(
        sessionId: String,
        original: List<UIMessage>,
        tree: ConversationTree,
    ) {
        val originalById = original.associateBy { it.id.toString() }
        val normalizedAll = buildList {
            tree.userNodes.forEach { user ->
                user.variants.forEach { add(it.message) }
                user.variants.forEach { variant ->
                    variant.assistantNodes.forEach { assistant -> assistant.variants.forEach { add(it) } }
                }
            }
        }
        val changed = normalizedAll.filter { msg ->
            val old = originalById[msg.id.toString()]
            old != null && (old.variantIndex != msg.variantIndex || old.variantCount != msg.variantCount)
        }
        if (changed.isEmpty()) return
        changed.forEach { sessionRepository.upsertMessage(sessionId, it) }
    }

    /**
     * 切换用户提问变体（P0 对话树）。
     */
    fun selectUserVariant(userGroupId: String, variantIndex: Int) {
        val tree = _conversationTree.value
        val node = tree.userNodes.firstOrNull { user ->
            (user.currentVariant?.message?.variantGroupId ?: user.groupId) == userGroupId
        } ?: return
        val updated = tree.selectUserVariant(node.userId, variantIndex)
        _conversationTree.value = updated
        _messages.value = updated.displayMessages
    }

    /**
     * 切换助手回复变体（P0 对话树）：作用域仅限当前用户变体下的指定助手组。
     */
    fun selectAssistantVariant(userGroupId: String, assistantGroupId: String, index: Int) {
        val updated = _conversationTree.value.selectAssistantVariant(userGroupId, assistantGroupId, index)
        _conversationTree.value = updated
        _messages.value = updated.displayMessages
    }


    /**
     * 审批工具调用:用户批准待审批的工具调用。
     */
    fun approveToolCall(toolCallId: String) {
        val pending = _state.value.pendingToolApprovals.firstOrNull { it.toolCallId == toolCallId } ?: return
        // 移除待审批项
        _state.update {
            it.copy(pendingToolApprovals = it.pendingToolApprovals.filter { it.toolCallId != toolCallId })
        }
        // v1.x: 把用户在审批卡片中选择的参考图(data URI)作为 argOverrides 注入工具执行参数。
        // 仅对支持 reference_image 的工具生效(目前为 generate_image);
        // ToolOrchestrator 会把 overrides 合并进 LLM 原始 arguments JSON。
        val argOverrides = if (!pending.referenceImageOverride.isNullOrBlank() &&
            pending.toolName in REFERENCE_IMAGE_TOOL_NAMES
        ) {
            mapOf("reference_image" to pending.referenceImageOverride)
        } else {
            emptyMap()
        }
        // 通知等待中的审批回调(通过 CompletableDeferred 实现)
        toolApprovalResults[toolCallId]?.complete(ToolApprovalState.Approved(argOverrides))
    }

    /**
     * v1.x: 设置待审批工具调用的参考图覆盖值。
     *
     * 由 ToolApprovalCard 中的"选择图片"按钮触发:用户从相册选择图片后,
     * UI 把图片转为 data URI 传到这里存入 [PendingToolApproval.referenceImageOverride],
     * 批准时由 [approveToolCall] 注入 argOverrides。
     *
     * @param dataUri 形如 "data:image/jpeg;base64,...";传 null 清除已选图片
     */
    fun setToolApprovalReferenceImage(toolCallId: String, dataUri: String?) {
        _state.update { current ->
            current.copy(
                pendingToolApprovals = current.pendingToolApprovals.map { approval ->
                    if (approval.toolCallId == toolCallId) {
                        approval.copy(referenceImageOverride = dataUri)
                    } else {
                        approval
                    }
                }
            )
        }
    }

    /**
     * 更新待审批工具调用的"始终允许"勾选状态。
     */
    fun setToolApprovalAlwaysAllow(toolCallId: String, alwaysAllow: Boolean) {
        _state.update { current ->
            current.copy(
                pendingToolApprovals = current.pendingToolApprovals.map { approval ->
                    if (approval.toolCallId == toolCallId) {
                        approval.copy(alwaysAllow = alwaysAllow)
                    } else {
                        approval
                    }
                }
            )
        }
    }

    /**
     * v1.0.16: 更新待审批工具调用的"本次开启期间批准全部"勾选状态。
     */
    fun setToolApprovalAppRunAllowAll(toolCallId: String, allowAll: Boolean) {
        _state.update { current ->
            current.copy(
                pendingToolApprovals = current.pendingToolApprovals.map { approval ->
                    if (approval.toolCallId == toolCallId) {
                        approval.copy(appRunAllowAll = allowAll)
                    } else {
                        approval
                    }
                }
            )
        }
    }

    /**
     * 拒绝工具调用:用户拒绝待审批的工具调用。
     */
    fun denyToolCall(toolCallId: String, reason: String) {
        val pending = _state.value.pendingToolApprovals.firstOrNull { it.toolCallId == toolCallId } ?: return
        _state.update {
            it.copy(pendingToolApprovals = it.pendingToolApprovals.filter { it.toolCallId != toolCallId })
        }
        toolApprovalResults[toolCallId]?.complete(ToolApprovalState.Denied(reason))
    }

    /**
     * P3: 设置当前会话的工具权限模式。
     */
    fun setSessionPermissionMode(mode: SessionPermissionMode) {
        val sessionId = if (_state.value.isAgentMode) _state.value.agentSessionId else _state.value.currentSessionId
        sessionId ?: return
        _state.update { it.copy(sessionPermissionMode = mode) }
        viewModelScope.launch {
            sessionPermissionStore.setMode(sessionId, mode)
        }
    }

    /**
     * v1.x: 获取当前用于工具审批的会话 id(Agent 模式取 agentSessionId,否则取 currentSessionId)。
     *
     * 用于会话级临时允许缓存的键,与 [setSessionPermissionMode] 的会话判定逻辑保持一致。
     */
    private fun currentSessionIdForApproval(): String? {
        return if (_state.value.isAgentMode) _state.value.agentSessionId else _state.value.currentSessionId
    }

    /**
     * v1.x: 持久化单工具策略(由审批卡片"始终允许"按钮触发)。
     *
     * 与本次批准解耦:按钮点击时先持久化策略,再走 onApprove 处理本次调用。
     */
    fun persistToolPolicy(toolCallId: String, policy: ToolApprovalPolicy) {
        val pending = _state.value.pendingToolApprovals.firstOrNull { it.toolCallId == toolCallId } ?: return
        viewModelScope.launch {
            runCatching { toolConfigStore!!.setPolicy(pending.toolName, policy) }
                .onFailure { Logger.w("ChatVM", "persistToolPolicy(${pending.toolName}) 失败: ${it.message}") }
        }
    }

    /**
     * v1.x: 把工具加入当前会话的临时允许集合(本会话不再问)。
     *
     * 由 ToolApprovalCard 中的"本会话允许"按钮触发:
     *  1. 把工具名加入 [SessionPermissionStore] 的会话级缓存(仅内存,不持久化)
     *  2. 后续本会话内该工具调用直接 Auto 执行,不再弹审批卡片
     *  3. 切换会话/新建会话时由 [switchSession] / [createNewSession] 清理旧会话缓存
     *
     * 注意:本方法仅更新缓存,不处理当前待审批项 —— 当前调用仍由 [approveToolCall]
     * 通过 onApprove 回调处理(与"始终允许"按钮的模式一致:onPersistPolicy + onApprove)。
     *
     * @param toolCallId 待审批工具调用 id
     */
    fun allowToolForSession(toolCallId: String) {
        val pending = _state.value.pendingToolApprovals.firstOrNull { it.toolCallId == toolCallId } ?: return
        val sessionId = currentSessionIdForApproval() ?: return
        sessionPermissionStore.allowToolForSession(sessionId, pending.toolName)
    }

    // 工具审批回调结果存储(toolCallId → Deferred result)
    private val toolApprovalResults = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<ToolApprovalState>>()

    /**
     * 取消所有待审批的工具调用(stop/switchSession 时调用,防止幽灵审批卡片 + 协程挂起)。
     */
    private fun cancelAllPendingApprovals() {
        _state.update { it.copy(pendingToolApprovals = emptyList()) }
        for ((_, deferred) in toolApprovalResults) {
            deferred.complete(ToolApprovalState.Denied("Generation stopped"))
        }
        toolApprovalResults.clear()
    }

    /**
     * 请求用户审批工具调用,挂起直到用户做出决定。
     *
     * P3: 综合三要素决定审批状态:
     *  - 单工具持久化策略([ToolConfigStore])
     *  - 当前会话权限模式([SessionPermissionMode])
     *  - 工具自身风险等级([ToolRiskLevel])
     * 最终由 [ToolPermissionResolver] 统一解析。
     */
    override suspend fun requestToolApproval(toolName: String, toolCallId: String, argsPreview: String, args: Map<String, Any?>): ToolApprovalState {
        // v1.0.16: 本次开启期间已批准全部工具,直接 Auto 执行,不再弹审批卡片
        if (_state.value.appRunAllowAllTools) {
            return ToolApprovalState.Auto
        }
        // v1.x: 本会话临时允许的工具直接 Auto(会话级缓存,会话切换/结束时自动失效)
        currentSessionIdForApproval()?.let { sid ->
            if (sessionPermissionStore.isAllowedThisSession(sid, toolName)) {
                return ToolApprovalState.Auto
            }
        }
        val perToolPolicy = toolConfigStore!!.getPolicy(toolName)
        val mode = _state.value.sessionPermissionMode
        val risk = toolRegistry.getToolRiskLevel(toolName)
        // v1.x: 审批决策调试日志 — 排查"完全放权不生效/始终允许无效"类问题
        Logger.d(
            "ToolApproval",
            "resolve | tool=$toolName | mode=$mode | risk=$risk | policy=$perToolPolicy" +
                " | allowAllRun=${_state.value.appRunAllowAllTools}",
        )
        // v1.0.53: 传完整 args,参数化策略(open_url/execute_javascript)生效
        val resolved = ToolPermissionResolver.resolve(toolName, risk, mode, perToolPolicy, args)
        Logger.d(
            "ToolApproval",
            "resolved | tool=$toolName | state=$resolved",
        )
        // 状态机闭环:显式列出所有终态分支,确保 ToolApprovalState.Answered 有处理路径
        when (resolved) {
            is ToolApprovalState.Pending -> { /* 待审批,继续走下方用户审批流程 */ }
            is ToolApprovalState.Answered -> {
                // 用户已提供自定义答案(替代工具执行),直接返回该答案
                return resolved
            }
            is ToolApprovalState.Approved, is ToolApprovalState.Auto,
            is ToolApprovalState.Denied -> return resolved
        }

        // 需要用户审批:添加到待审批列表并等待结果
        val deferred = kotlinx.coroutines.CompletableDeferred<ToolApprovalState>()
        toolApprovalResults[toolCallId] = deferred
        _state.update {
            it.copy(
                pendingToolApprovals = it.pendingToolApprovals + PendingToolApproval(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    argumentsPreview = argsPreview,
                )
            )
        }
        return try {
            deferred.await()
        } finally {
            toolApprovalResults.remove(toolCallId)
        }
    }

    /**
     * v0.29 P0-3: 导出当前会话为 Markdown 文本(用于分享/导出)。
     *
     * v1.105: 委托至 [ChatExportCoordinator.exportSessionAsMarkdown]。
     */
    suspend fun exportSessionAsMarkdown(): String {
        return exportCoordinator.exportSessionAsMarkdown()
    }

    /** 功能4: 导出当前会话为 JSON。 */
    suspend fun exportSessionAsJson(): String {
        return exportCoordinator.exportSessionAsJson()
    }

    /** 功能4: 导出当前会话为纯文本。 */
    suspend fun exportSessionAsPlainText(): String {
        return exportCoordinator.exportSessionAsPlainText()
    }

    /** 功能4: 统一导出入口。 */
    suspend fun exportSession(format: io.zer0.muse.ui.chat.ExportFormat): Pair<String, String> {
        return exportCoordinator.exportSession(format)
    }

    /**
     * 导出当前会话为单文件 HTML(内联 CSS + base64 图片 + highlight.js CDN)。
     *
     * 委托至 [ChatExportCoordinator.exportSessionAsHtml]。
     */
    suspend fun exportSessionAsHtml(): String {
        return exportCoordinator.exportSessionAsHtml()
    }

    /**
     * 导出当前会话为 PDF 文件(Android 原生 PdfDocument,A4 分页)。
     *
     * 委托至 [ChatExportCoordinator.exportSessionAsPdf]。
     * 返回的文件位于 cacheDir/export/,通过 FileProvider 暴露给分享 Intent。
     *
     * @param context Android Context(用于读取 cacheDir)
     */
    suspend fun exportSessionAsPdf(context: android.content.Context): java.io.File {
        return exportCoordinator.exportSessionAsPdf(context)
    }

    /**
     * 启动流式请求。history 取当前 messages 去掉占位 assistant。
     * memory 注入 + ticker 通知在此统一处理,供 send / regenerate 复用。
     *
     * Phase 7: 接入 function calling tool-call 循环 —
     *  1. 发送 messages + tools 给 LLM
     *  2. 收集 ContentDelta / ReasoningDelta / ToolCallDelta
     *  3. 流结束时若累积了 toolCalls,执行工具 → 回填 TOOL 消息 → 再次请求(最多 25 轮防死循环,v1.52 由 5 提升)
     *  4. 无 toolCalls 则正常结束
     *
     * Phase 8.2: 接入 Assistant 配置 —
     *  - 注入 systemPrompt 作为 SYSTEM 消息(放最前)
     *  - 注入 presetMessages(预设对话,放 SYSTEM 之后、用户消息之前)
     *  - 按 [AssistantEntity.contextMessageSize] 截断历史(取最近 N 条)
     *  - 用 Assistant.temperature / maxTokens / reasoningLevel 透传给 ChatService
     *  - 按 Assistant.memoryEnabled / enableTimeReminder 控制 Transformer 管道开关
     *  - 通过 TemplateTransformer 处理 Assistant.messageTemplate 的 {{var}} 变量
     */
    private fun launchStream(
        assistantId: Uuid,
        sessionId: String,
        isNewBranch: Boolean = false,
        continueFrom: UIMessage? = null,
    ) {
        // v1.94: 每次启动流式生成前清空工具调用历史(InputBar 动态胶囊计数归零)
        _state.update { it.copy(toolCallHistory = emptyList()) }
        // R-UI-02: 生成会话单独持久化,避免与用户查看焦点互相覆盖。
        viewModelScope.launch {
            resultOf { settings.saveGeneratingSessionId(sessionId) }
                .onError { msg, _ -> Logger.w("ChatVM", "saveGeneratingSessionId 失败: $msg") }
        }
        // v1.0.29: 不再在前台启动前台服务通知(用户反馈"正在生成"通知极度无用)。
        // 改为仅在应用切到后台时启动(由 MuseApp ON_STOP → onAppBackground 触发),
        // 切回前台时自动停止(由 MuseApp ON_START → onAppForeground 触发)。
        val generationJob = chatGenerationManager.launchGeneration(
            sessionId = sessionId,
            assistantId = assistantId.toString(),
            sessionTitle = _state.value.sessions.firstOrNull { it.id == sessionId }?.title ?: appContext.getString(R.string.chat_new_session),
        ) {
            // v1.97: builder/reasoningBuilder/currentAssistantId 提到 try 块外,让 catch 块能访问
            // (切页后 catch 块用 builder 内容 + currentAssistantId 构造部分回复落盘)
            // PII Guard:piiMatches 与 unmaskPii 辅助函数提到 try 块外,让 catch 块也能在
            // 落盘部分回复时还原占位符,避免 [PHONE_001] 等占位符被持久化到数据库。
            val state = StreamRunState(sessionId = sessionId, assistantId = assistantId, isNewBranch = isNewBranch)
            // B7-04: 继续生成时预置已产出内容
            continueFrom?.let { state.builder.append(it.content) }
            try {
                streamCoordinator.prepareHistory(state)
                buildSystemPromptForStream(state)
                streamCoordinator.applyTransformers(state)
                streamCoordinator.resolveToolsAndModel(state)
                streamCoordinator.applyPiiGuard(state)
                streamCoordinator.prepareVisionContext(state)
                val success = runToolLoop(state)
                if (success) {
                    finalizeResponse(state)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // v1.80 (M-CVM2): 用户停止生成(或切会话)触发取消时,也要持久化已接收的部分回复,
                // 加 [已中断] 标记后落盘。原实现直接 throw ce 跳过了下面的持久化逻辑,
                // 导致停止生成时部分回复不落盘。提取到 persistInterruptedAssistant(用 NonCancellable 包裹)。
                // v1.97: 切页后 _messages.value 可能已切换到新会话,用 builder 构造部分回复,
                // 避免从 _messages.value 找到错误会话的消息。
                // PII Guard:部分回复也要还原占位符,避免 [PHONE_001] 等占位符落入数据库。
                val partialFromBuilder = if (state.builder.isNotEmpty()) {
                    UIMessage(
                        id = state.currentAssistantId,
                        role = MessageRole.ASSISTANT,
                        content = state.unmaskPii(state.builder.toString()),
                        reasoning = state.unmaskPii(state.reasoningBuilder.toString()).ifBlank { null },
                    )
                } else null
                persistInterruptedAssistant(sessionId, partialFromBuilder)
                // 协程取消必须重新抛出,避免破坏 stop() / switchSession() 等的状态
                throw ce
            } catch (t: Exception) {
                // v1.80 (L-CVM3): catch Throwable 改为 catch Exception,避免捕获 OOM/StackOverflow 等 Error
                Logger.e("ChatVM", "stream failed", t)
                // v0.51: 流式被打断时保留已接收的部分回复(加 [已中断] 标记并落盘)
                // v1.97: 同上,用 builder 构造部分回复,避免切页后从 _messages.value 找错。
                // PII Guard:部分回复也要还原占位符。
                val partialFromBuilder = if (state.builder.isNotEmpty()) {
                    UIMessage(
                        id = state.currentAssistantId,
                        role = MessageRole.ASSISTANT,
                        content = state.unmaskPii(state.builder.toString()),
                        reasoning = state.unmaskPii(state.reasoningBuilder.toString()).ifBlank { null },
                    )
                } else null
                persistInterruptedAssistant(sessionId, partialFromBuilder)
                // 任务 3: 统一错误提示 —— 分类错误类型并生成友好中文文案
                val type = classifyErrorType(t.message ?: "", t)
                val msg = classifyNetworkError(t)
                addError(type, msg, isRecoverable = type != ChatErrorType.API_KEY)
                _state.update { it.copy(isStreaming = false, isWaitingFirstToken = false) }
                // 通知:错误时取消进度通知
                runCatching {
                    notificationManager.updateLiveProgress("", 0, false)
                }.onFailure { Logger.w("ChatVM", "取消进度通知失败: ${it.message}") }
            } finally {
                // v1.43: 生成结束(正常/异常/取消)都停止前台服务
                runCatching { ChatGenerationService.stop(appContext) }
                // v1.0.21: 生成结束后清除内存缓存,避免切回时命中过期快照(缺少最终回复)
                //   后台生成期间 DB 已写入最新消息,切回时应从 DB 加载而非用旧缓存
                sessionMemoryCache.remove(sessionId)
                // B0-08: 里程碑检查(消息数/相伴天数触发,DAO 内去重)
                milestoneChecker?.checkAndTrigger(sessionId, currentAssistantId())
            }
        }
        // v1.x: 把生成任务交给 ConversationSessionManager 跟踪,
        // 用于会话级引用计数 + idle 清理。job 完成时 manager 内部会自动清理引用并触发 idle 检查。
        sessionManager.setGenerationJob(sessionId, generationJob)
    }

    // v1.0.27 Phase 4-A.1: StreamRunState 抽到 chat/StreamRunState.kt (internal class)
    // 由 ChatStreamCoordinator 与本类共享,catch 块仍可访问 state.builder / state.unmaskPii

    // ===== Phase A: 历史准备 =====
    // v1.0.27 Phase 4-A.2: prepareHistory 抽到 ChatStreamCoordinator

    // ===== Phase B: system prompt 组装 + prefix 消息(含 RAG 注入)=====
    private suspend fun buildSystemPromptForStream(state: StreamRunState) {
        with(state) {
            // v0.30-a: 用 SystemPromptAssembler 组装系统提示(6 步工作流第 1 步)
            // 9 个 section: 人格/时间/用户画像/Pinned/记忆/工具清单/Workspace/决策树/MOOD 格式
            // 注意:Assembler 内部已吸收 TimeReminderTransformer + MemoryInjectionTransformer 的职责,
            // 所以管道里要把这两个 Transformer 关闭(由 context.extra 控制)。
            // v1.78 (#34): memoryEnabled 此处供 Assembler 决定是否注入长期记忆 section;
            // 下方 context.extra("memory_enabled" = false) 用于禁用管道里的 MemoryInjectionTransformer,
            // 二者消费方不同、不冲突 — Assembler 是唯一注入入口,Transformer 默认关闭。
            val memoryEnabled = assistant?.memoryEnabled ?: true
            val timeReminderEnabled = assistant?.enableTimeReminder ?: true
            val effectiveMemoryEnabled = memoryEnabled && settings.isMemoryEnabled()
            // v1.0.72: 本会话不参考记忆标志
            val effSid = if (_state.value.isAgentMode) _state.value.agentSessionId else _state.value.currentSessionId
            val sessionIgnoreMem = _state.value.sessions
                .firstOrNull { it.id == effSid }?.ignoreMemory ?: false
            // 复用静态 system prompt 快照,只追加动态"当前时间"。
            val currentKey = computeStaticSnapshotKey(assistant, effectiveMemoryEnabled)
            val staticSnapshot = if (currentKey == cachedStaticSnapshotKey && cachedStaticSystemPrompt.isNotBlank()) {
                cachedStaticSystemPrompt
            } else {
                val rebuilt = resultOf {
                    systemPromptAssembler.buildStaticSnapshot(
                        assistant = assistant,
                        memoryEnabled = effectiveMemoryEnabled,
                        ignoreMemory = sessionIgnoreMem,
                    )
                }.getOrNull() ?: ""
                cachedStaticSystemPrompt = rebuilt
                cachedStaticSnapshotKey = currentKey
                rebuilt
            }
            val dynamicSection = if (timeReminderEnabled) systemPromptAssembler.buildDynamicSection() else ""
            val combinedSystemPrompt = buildString {
                if (staticSnapshot.isNotBlank()) append(staticSnapshot)
                if (dynamicSection.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n---\n\n")
                    append(dynamicSection)
                }
            }
            systemMessages = if (combinedSystemPrompt.isBlank()) emptyList() else listOf(
                UIMessage(role = MessageRole.SYSTEM, content = combinedSystemPrompt)
            )
            // v0.45: 缓存 system prompt 文本,供流式过程中 updateContextTokenCount 复用(避免每 50 字符重建)
            cachedSystemPrompt = combinedSystemPrompt
            // v0.45: 发送前更新一次 token 占用(含刚加入的 user 消息)
            updateContextTokenCount()

            // v1.116 (C1-4): 发送前上下文长度硬检查 — token 占用超过预警比例时,
            // 激进截断历史(减半 contextSize 重新取最近 N 条),避免上下文溢出导致 API 报错或回复质量下降。
            // 注意:此检查基于 system + messages 估算,不含 RAG/webSearch 等动态 prefix(尚未构造),
            // 因此阈值设为 0.9 而非 1.0,为后续 prefix 预留 10% 余量。
            run {
                val maxTokens = _state.value.contextMaxTokens
                val currentTokens = _state.value.contextTokenCount
                if (maxTokens > 0 && currentTokens > 0) {
                    val ratio = currentTokens.toFloat() / maxTokens
                    if (ratio >= PRESEND_TOKEN_WARNING_RATIO && rawHistory.size > 5) {
                        val newSize = (contextSize / 2).coerceAtLeast(2)
                        if (newSize < contextSize) {
                            Logger.w(
                                "ChatVM",
                                "发送前上下文预警: token=$currentTokens/$maxTokens (${(ratio * 100).toInt()}%), " +
                                    "历史截断 $contextSize → $newSize 条",
                            )
                            contextSize = newSize
                            // v1.x: 同样使用工具依赖感知截断
                            truncatedHistory = rawHistory.limitContextWithContext(contextSize)
                        }
                    }
                }
            }

            prefixMessages = buildList<UIMessage> {
                addAll(systemMessages)
                // presetMessages(预设对话)
                assistant?.let { assistantRepository.parsePresetMessages(it) }?.forEach {
                    add(it)
                }
                // v1.54: RAG 自动注入 — 用最后一条 user 消息检索知识库 top-k 片段,
                // 相似度 > 阈值时静默注入 system context(与 webSearch 同构)。
                // v1.133 改造:
                //   - @mention 定向检索:从 user 消息中提取 @docName,解析为 docIds 作为 scopeDocIds
                //   - 引用列表:用 buildInjectionContextWithCitations 返回 RagInjection,
                //     citations 存到 pendingRagCitations,流式结束后附加到 assistant 消息
                //   - token 预算控制:RagConfig.tokenBudget 在 RagService 内部累加截断
                // 失败不阻断主流程(runCatching + addError 降级)。
                val ragConfig = resultOf { settings.getRagConfig() }.getOrNull() ?: io.zer0.muse.rag.RagConfig()
                // v1.133: 应用 per-assistant RAG 配置覆盖(助手未指定 override 时退回全局)
                val effectiveRagConfig = assistant?.let {
                    runCatching { assistantRepository.mergeRagConfigOverride(it, ragConfig) }
                        .onFailure { e -> Logger.w("ChatViewModel", "mergeRagConfigOverride 失败: ${e.message}") }
                        .getOrDefault(ragConfig)
                } ?: ragConfig
                if (effectiveRagConfig.enabled) {
                    val lastUser = rawHistory.lastOrNull { it.role == MessageRole.USER }
                    val ragQuery = lastUser?.content?.takeIf { it.isNotBlank() }
                    if (ragQuery != null) {
                        // v1.133: @mention 解析 → scopeDocIds(空列表则走全库检索)
                        val scopeDocIds = resultOf { ragService.resolveMentionToDocIds(ragQuery) }
                            .onError { msg, t -> Logger.w("ChatViewModel", "@mention 解析失败: $msg", t) }
                            .getOrNull()?.takeIf { it.isNotEmpty() }
                        val injection = resultOf {
                            ragService.buildInjectionContextWithCitations(ragQuery, effectiveRagConfig, scopeDocIds)
                        }.onError { msg, t ->
                            addError(ChatErrorType.NETWORK, appContext.getString(R.string.err_chat_rag_failed, msg))
                        }.getOrNull()
                        if (injection != null) {
                            if (injection.text.isNotBlank()) {
                                add(UIMessage(role = MessageRole.SYSTEM, content = injection.text))
                            }
                            if (injection.citations.isNotEmpty()) {
                                pendingRagCitations = injection.citations
                            }
                        }
                    }
                }
            }
        }
    }

    // ===== Phase C+D: Transformer 管道 =====
    // v1.0.27 Phase 4-A.2: applyTransformers 抽到 ChatStreamCoordinator

    // ===== Phase E: 工具定义 + 模型解析 =====
    // v1.0.27 Phase 4-A.2: resolveToolsAndModel 抽到 ChatStreamCoordinator

    // ===== Phase F: PII Guard 遮蔽 =====
    // v1.0.27 Phase 4-A.2: applyPiiGuard 抽到 ChatStreamCoordinator

    // ===== Phase G: 视觉辅助 =====
    // v1.0.27 Phase 4-A.2: prepareVisionContext 抽到 ChatStreamCoordinator

    // ===== Phase H: 工具调用循环(含流式 streamRound)=====
    // 返回 true 表示成功完成(可继续 finalizeResponse),false 表示已处理错误并应跳过 finalize。
    private suspend fun runToolLoop(state: StreamRunState): Boolean {
        val experiments = state.experiments
        val sessionId = state.sessionId
        val sessionTitle = state.sessionTitle
        val streamStartedAt = state.streamStartedAt
        // B5-01: 生成检查点 — 记录本轮用户消息与生成起始时间,供进程被杀后恢复
        val checkpointUserMessageId = state.conversationHistory.lastOrNull { it.role == MessageRole.USER }?.id?.toString() ?: ""
        val checkpointCreatedAt = streamStartedAt
        val pendingRagCitations = state.pendingRagCitations
        val assistant = state.assistant
        val effectiveModel = state.effectiveModel
        val effectiveProviderConfig = state.effectiveProviderConfig
        val tools = state.tools
        val effectiveTemperature = state.effectiveTemperature
        val reasoningLevel = state.reasoningLevel
        val conversationHistory = state.conversationHistory
        val unmaskPii: (String) -> String = state::unmaskPii

        // v1.42: 流式过程中 UI/通知/token 更新采用字符+时间双阈值节流,降低重组频率。
        // 这些变量仅在 streamRound 内使用,跨轮共享、重试时重置(与原实现一致)。
        var firstTokenTime = 0L  // v2.3: 首 token 到达时间
        var lastLoggedCharCount = 0
        var lastUiUpdateChars = 0
        var lastUiUpdateAt = streamStartedAt
        var lastReasoningUiUpdateChars = 0
        var lastNotifChars = 0
        var lastNotifAt = streamStartedAt
        var lastTokenUpdateChars = 0
        var lastTokenUpdateAt = streamStartedAt
        // v1.43: 流式中周期性落盘,避免切页/后台后丢失进度。
        var lastPersistChars = 0
        var lastPersistAt = streamStartedAt
        // v1.0.4: 自适应切片状态 — 仅对 ContentDelta(文本流)生效,不影响 Reasoning/ToolCall。
        // pendingBuilder 累积未输出到 UI 的 delta,50ms 节流触发时按 computeAdaptiveSlice() 取切片。
        val pendingBuilder = StringBuilder()
        val chunkIntervals = ArrayDeque<Long>(STREAM_SLIDE_WINDOW)
        var lastChunkAt = 0L

        /**
         * v1.0.4: 根据流式速率(滑动平均)计算本次 50ms 节流应输出的字符数。
         *
         * - 间隔越大(慢速流,如 Reasoning)→ rate 越小 → 切片越小(细粒度,最小 2)
         * - 间隔越小(快速流,如纯文本)→ rate 越大 → 切片越大(批量,最大 240)
         * - 无样本时返回基准值 STREAM_SLICE_BASE
         */
        fun computeAdaptiveSlice(): Int {
            if (chunkIntervals.isEmpty()) return STREAM_SLICE_BASE
            val avgInterval = chunkIntervals.average().toLong()
            // rate = 50ms / avgInterval:间隔 50ms → rate=1;间隔 500ms → rate=0.1;间隔 5ms → rate=10
            val rate = (50.0 / maxOf(1L, avgInterval)).coerceIn(0.1, 10.0)
            return (STREAM_SLICE_BASE * rate).toInt().coerceIn(STREAM_SLICE_MIN, STREAM_SLICE_MAX)
        }

        // Phase 2: 工具调用循环下沉到 ToolOrchestrator
        // v1.135: 记录当前助手消息 id,供媒体生成类工具更新消息 UI。
        toolAssistantId = state.currentAssistantId
        val baseHistorySize = conversationHistory.size
        // v1.x: 三钩子接入 — 流式视觉转换(applyVisualTransform)接入说明:
        // 当前流式 UI 更新走 updateAssistant(id, content, ...) 路径,
        // 直接调用 transformerPipeline.applyVisualTransform 需要把 builder 内容
        // 包成 UIMessage,跑钩子后再拆出 content/reasoning 喂回 updateAssistant。
        // 接入示例(待后续重构 streamRound 时启用):
        // ```
        // val ctx = state.transformContext ?: return@streamRound ...
        // val visualMsg = UIMessage(id=params.currentAssistantId, role=ASSISTANT, content=unmaskPii(builder.toString()))
        // val visualized = transformerPipeline.applyVisualTransform(listOf(visualMsg), ctx).first()
        // updateAssistant(visualized.id, visualized.content, visualized.reasoning, isStreaming=true)
        // ```
        // 暂不接入:streamRound 内有自适应切片/节流逻辑,直接套 visualTransform 会破坏
        // 节流策略(每次都跑全管道)。待把 visualTransform 改为"只在内容含 <think> 时触发"再做。
        // TODO(streaming-visual-transform): 接入 applyVisualTransform,启用 ThinkTag 流式实时剥离
        val toolLoopHost = object : ToolLoopHost {
            override suspend fun streamRound(params: StreamRoundParams): StreamRoundResult {
                val round = params.round
                // B5-03: 多轮 thinking 签名/加密内容累积,最终写入 assistant 消息
                var thinkingSignature: String? = null
                var thinkingEncryptedContent: String? = null
                // B3-03: 断线续传去重 — 跳过与已显示内容重复的前缀 delta,避免用户看到重复文本
                var duplicateRemaining: String? = if (params.preservePartialContent) params.builder.toString() else null
                // v1.0.17: preservePartialContent=true 时跳过 clear,保留 StreamInterrupted 已收的部分内容
                if (!params.preservePartialContent) {
                    params.builder.clear()
                    params.reasoningBuilder.clear()
                } else {
                    Logger.i("ChatVM", "streamRound retry with preservePartialContent, keep ${params.builder.length} chars")
                }

                // B5-01: 每轮开始写入生成检查点,确保流式产出有持久化兜底
                runCatching {
                    sessionRepository.upsertGenerationCheckpoint(
                        sessionId = sessionId,
                        userMessageId = checkpointUserMessageId,
                        assistantMessageId = params.currentAssistantId.toString(),
                        content = unmaskPii(params.builder.toString()),
                        createdAt = checkpointCreatedAt,
                    )
                }.onFailure { Logger.w("ChatVM", "generation checkpoint 写入失败: ${it.message}") }
                val flow = chatService.streamChat(
                    messages = params.history,
                    model = effectiveModel,
                    providerConfig = effectiveProviderConfig,
                    tools = tools,
                    temperature = effectiveTemperature,
                    maxTokens = assistant?.maxTokens,
                    reasoningLevel = reasoningLevel,
                    resumeFromText = params.builder.toString()
                        .takeIf { params.preservePartialContent && it.isNotBlank() },
                )
                val imageAccumulator = mutableListOf<String>()
                val toolCallAccumulator = mutableMapOf<Int, Triple<String?, String?, StringBuilder>>()
                var streamError: String? = null
                // v1.0.15: StreamInterrupted 标志 — 已收部分内容后网络中断,
                //   等待 NetworkMonitor 网络恢复事件后重试(非固定 delay),避免盲重试立即失败
                var streamInterrupted = false
                // v1.0.17: StreamInterrupted 的原始 throwable,用于判断是否网络错误(IOException)
                var streamInterruptedThrowable: Throwable? = null
                val streamToUi = _state.value.chatPreferences.streamResponse

                flow.collect { event ->
                    when (event) {
                        is ChatStreamEvent.ContentDelta -> {
                            // B3-03: 续传去重 — 若当前 delta 是已显示内容的重复前缀,直接跳过
                            val duplicate = duplicateRemaining
                            if (duplicate != null) {
                                if (duplicate.startsWith(event.delta)) {
                                    duplicateRemaining = duplicate.removePrefix(event.delta).takeIf { it.isNotEmpty() }
                                    return@collect
                                }
                                duplicateRemaining = null
                            }
                            // v1.0.3: 首 token 立即刷新 UI,消除"loading → 大量文字"的视觉断层
                            val isFirstToken = firstTokenTime == 0L
                            if (isFirstToken) {
                                firstTokenTime = System.currentTimeMillis()
                                // 立即清除"等待首 token"状态,ShimmerBubble 消失,StreamingCursor 接管
                                _state.update { it.copy(isWaitingFirstToken = false) }
                            }
                            val now = System.currentTimeMillis()
                            if (streamToUi) {
                                // v1.0.4: 自适应切片路径 — delta 先累积到 pendingBuilder,
                                // 50ms 节流触发时按 computeAdaptiveSlice() 取前 N 字符输出,实现平滑流入。
                                if (lastChunkAt != 0L) {
                                    chunkIntervals.addLast(now - lastChunkAt)
                                    if (chunkIntervals.size > STREAM_SLIDE_WINDOW) chunkIntervals.removeFirst()
                                }
                                lastChunkAt = now
                                pendingBuilder.append(event.delta)
                                if (isFirstToken) {
                                    // 首 token 立即输出全部 pending 内容,不等 50ms,消除 loading→大量文字 的断层
                                    params.builder.append(pendingBuilder)
                                    pendingBuilder.clear()
                                    updateAssistantWithVisualTransform(state, params.currentAssistantId, unmaskPii(params.builder.toString()), isStreaming = true)
                                    lastUiUpdateChars = params.builder.length
                                    lastUiUpdateAt = now
                                } else if (now - lastUiUpdateAt >= STREAM_THROTTLE_MS && pendingBuilder.isNotEmpty()) {
                                    // 50ms 固定节流 + 自适应切片(慢速流切片小,快速流切片大)
                                    val slice = computeAdaptiveSlice()
                                    val sliceLen = minOf(slice, pendingBuilder.length)
                                    params.builder.append(pendingBuilder.substring(0, sliceLen))
                                    pendingBuilder.delete(0, sliceLen)
                                    updateAssistantWithVisualTransform(state, params.currentAssistantId, unmaskPii(params.builder.toString()), isStreaming = true)
                                    lastUiUpdateChars = params.builder.length
                                    lastUiUpdateAt = now
                                }
                            } else {
                                // 非流式 UI:直接累积到 builder(保持原行为,通知/持久化仍按 builder.length 节流)
                                params.builder.append(event.delta)
                            }
                            if (params.builder.length - lastNotifChars >= 100 || now - lastNotifAt >= 500) {
                                lastNotifChars = params.builder.length
                                lastNotifAt = now
                                runCatching {
                                    notificationManager.updateLiveProgress(sessionTitle, params.builder.length, true)
                                }.onFailure { Logger.w("ChatVM", "更新进度通知失败: ${it.message}") }
                            }
                            if (experiments.debugMode && params.builder.length - lastLoggedCharCount >= 100) {
                                lastLoggedCharCount = params.builder.length
                                val elapsedMs = System.currentTimeMillis() - streamStartedAt
                                Logger.d(
                                    "ChatVM-Debug",
                                    "streaming | sessionId=$sessionId | round=$round | chars=${params.builder.length} | elapsed=${elapsedMs}ms",
                                )
                            }
                            if (params.builder.length - lastTokenUpdateChars >= 200 || now - lastTokenUpdateAt >= 1000) {
                                lastTokenUpdateChars = params.builder.length
                                lastTokenUpdateAt = now
                                updateContextTokenCount()
                            }
                            if (params.builder.length - lastPersistChars >= 300 || now - lastPersistAt >= 2000) {
                                lastPersistChars = params.builder.length
                                lastPersistAt = now
                                chatGenerationManager.touch()
                                val persistMsg = _messages.value
                                    .firstOrNull { it.id == params.currentAssistantId }
                                    ?.copy(
                                        content = unmaskPii(params.builder.toString()),
                                        reasoning = unmaskPii(params.reasoningBuilder.toString()).ifBlank { null },
                                        thinkingSignature = thinkingSignature,
                                        thinkingEncryptedContent = thinkingEncryptedContent,
                                    )
                                    ?: UIMessage(
                                        id = params.currentAssistantId,
                                        role = MessageRole.ASSISTANT,
                                        content = unmaskPii(params.builder.toString()),
                                        reasoning = unmaskPii(params.reasoningBuilder.toString()).ifBlank { null },
                                        thinkingSignature = thinkingSignature,
                                        thinkingEncryptedContent = thinkingEncryptedContent,
                                    )
                                persistCurrentAssistant(sessionId, params.currentAssistantId, persistMsg)
                                runCatching {
                                    sessionRepository.upsertGenerationCheckpoint(
                                        sessionId = sessionId,
                                        userMessageId = checkpointUserMessageId,
                                        assistantMessageId = params.currentAssistantId.toString(),
                                        content = unmaskPii(params.builder.toString()),
                                        createdAt = checkpointCreatedAt,
                                    )
                                }.onFailure { Logger.w("ChatVM", "generation checkpoint 更新失败: ${it.message}") }
                            }
                        }
                        is ChatStreamEvent.ReasoningDelta -> {
                            // v1.0.3: 首 token 立即刷新 UI(ReasoningDelta 也算首 token)
                            val isFirstToken = firstTokenTime == 0L
                            if (isFirstToken) {
                                firstTokenTime = System.currentTimeMillis()
                                _state.update { it.copy(isWaitingFirstToken = false) }
                            }
                            params.reasoningBuilder.append(event.delta)
                            if (!event.signature.isNullOrBlank()) thinkingSignature = event.signature
                            if (!event.encryptedContent.isNullOrBlank()) thinkingEncryptedContent = event.encryptedContent
                            val now = System.currentTimeMillis()
                            val timeSinceUi = now - lastUiUpdateAt
                            // v1.0.3: 首 token 立即刷新;后续按 12 字符或 50ms 节流
                            // reasoning-only 流(content 为 0)必须按 reasoning 长度节流,
                            // 否则首 token 后 UI 永远不更新,用户只看到第一个字符。
                            val reasoningCharsSinceUi = params.reasoningBuilder.length - lastReasoningUiUpdateChars
                            if (streamToUi && (isFirstToken || reasoningCharsSinceUi >= STREAM_UI_CHAR_THRESHOLD || (timeSinceUi >= STREAM_UI_TIME_THRESHOLD_MS && reasoningCharsSinceUi > 0))) {
                                updateAssistant(
                                    params.currentAssistantId,
                                    unmaskPii(params.builder.toString()),
                                    unmaskPii(params.reasoningBuilder.toString()),
                                    isStreaming = true,
                                )
                                lastUiUpdateChars = params.builder.length
                                lastUiUpdateAt = now
                                lastReasoningUiUpdateChars = params.reasoningBuilder.length
                            }
                        }
                        is ChatStreamEvent.ImageDelta -> {
                            imageAccumulator.add(event.imageBase64)
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdateAt >= STREAM_UI_TIME_THRESHOLD_MS) {
                                updateAssistant(
                                    params.currentAssistantId,
                                    unmaskPii(params.builder.toString()),
                                    unmaskPii(params.reasoningBuilder.toString()),
                                    imageAccumulator.toList(),
                                )
                                lastUiUpdateAt = now
                            }
                        }
                        is ChatStreamEvent.ToolCallDelta -> {
                            val acc = toolCallAccumulator.getOrPut(event.index) {
                                Triple(null, null, StringBuilder())
                            }
                            val newId = event.id ?: acc.first
                            val newName = event.name ?: acc.second
                            event.argumentsDelta?.let { acc.third.append(it) }
                            toolCallAccumulator[event.index] = Triple(newId, newName, acc.third)
                            if (experiments.debugMode && event.name != null) {
                                Logger.d(
                                    "ChatVM-Debug",
                                    "toolCallDelta | sessionId=$sessionId | round=$round | index=${event.index} | tool=${event.name}",
                                )
                            }
                        }
                        is ChatStreamEvent.Done -> {
                            // v1.0.30: 某些模型（如 GLM-4-9B）全流程只发空名 tool_call，
                            // 无 ContentDelta/ReasoningDelta，isWaitingFirstToken 全程未清。
                            // 在 Done 事件强制清除"等待首 token"状态。
                            if (_state.value.isWaitingFirstToken) {
                                _state.update { it.copy(isWaitingFirstToken = false) }
                            }
                            // v1.0.30: 某些模型把所有输出塞进 reasoningContent
                            // content 字段为空 → params.builder 零长度 → UI 只显示思考无正文。
                            // 兜底：reasoningBuilder 有内容但 builder 为空时，把思考复制为正文。
                            // v1.0.54: 工具轮(content 空 + 有 toolCalls)不复制 — 那是正常的工具调用轮,
                            //   复制后思考文本会作为正文显示(send_sticker 选贴纸的推理被展示,极其出戏)。
                            if (params.builder.isEmpty() && params.reasoningBuilder.isNotEmpty() &&
                                toolCallAccumulator.isEmpty()
                            ) {
                                params.builder.append(params.reasoningBuilder.toString())
                            }
                            if (experiments.debugMode) {
                                val elapsedMs = System.currentTimeMillis() - streamStartedAt
                                Logger.d(
                                    "ChatVM-Debug",
                                    "stream Done | sessionId=$sessionId | round=$round | chars=${params.builder.length} | elapsed=${elapsedMs}ms",
                                )
                            }
                        }
                        is ChatStreamEvent.Error -> {
                            streamError = event.message
                            Logger.e("ChatVM", "stream error", event.throwable)
                            if (experiments.debugMode) {
                                Logger.d(
                                    "ChatVM-Debug",
                                    "stream Error | sessionId=$sessionId | round=$round | msg=${event.message}",
                                )
                            }
                        }
                        is ChatStreamEvent.FallbackNotice -> { MuseToast.show(event.message) }
                        is ChatStreamEvent.StreamInterrupted -> {
                            // v1.0.15: 已收部分内容后连接中断,保留内容并等待网络恢复后重试(非固定 delay)
                            streamError = event.message
                            streamInterrupted = true
                            streamInterruptedThrowable = event.throwable
                            Logger.w("ChatVM", "stream interrupted (partial content kept)", event.throwable)
                            if (experiments.debugMode) {
                                Logger.d(
                                    "ChatVM-Debug",
                                    "stream Interrupted | sessionId=$sessionId | round=$round | msg=${event.message}",
                                )
                            }
                        }
                    }
                }

                // v1.0.4: 流结束 flush pendingBuilder 剩余内容(覆盖 Done/Error/StreamInterrupted)。
                // 自适应切片下 params.builder 可能滞后于 pendingBuilder,这里把未输出部分一次性写入,
                // 确保最终 updateAssistant / persist 拿到完整内容。
                if (pendingBuilder.isNotEmpty()) {
                    params.builder.append(pendingBuilder)
                    pendingBuilder.clear()
                    if (streamToUi) {
                        updateAssistant(params.currentAssistantId, unmaskPii(params.builder.toString()), isStreaming = true)
                        lastUiUpdateChars = params.builder.length
                        lastUiUpdateAt = System.currentTimeMillis()
                    }
                }

                if (streamError != null) {
                    val retryType = classifyErrorType(streamError)
                    // v1.0.1 (P4): 用 params.retryCount 替代外层 streamRetryCount,每轮独立
                    // v1.0.17: StreamInterrupted 智能续传 — 已收部分内容 + 网络错误时,
                    //   等待网络恢复(最多 30s)后用 preservePartialContent=true 重试,
                    //   保留已显示内容,UI 仅追加新内容(不闪回到首字)。
                    //   超时未恢复网络则降级为手动重试(保留部分内容 + isRecoverable)。
                    val isNetworkError = streamInterruptedThrowable is java.io.IOException ||
                        (streamInterrupted && retryType == ChatErrorType.NETWORK)
                    if (streamInterrupted &&
                        isNetworkError &&
                        params.builder.isNotEmpty() &&
                        params.retryCount < MAX_STREAM_RETRIES
                    ) {
                        val newRetryCount = params.retryCount + 1
                        Logger.w(
                            "ChatVM",
                            "StreamInterrupted 网络中断,等待网络恢复(最多 ${NETWORK_RECOVERY_TIMEOUT_MS}ms)" +
                                "(第 $newRetryCount/$MAX_STREAM_RETRIES 次,round=${params.round}): $streamError",
                        )
                        val recovered = waitForNetworkRecovery(NETWORK_RECOVERY_TIMEOUT_MS)
                        if (recovered) {
                            Logger.i("ChatVM", "网络已恢复,preservePartialContent=true 重试")
                            // 重置自适应切片状态(上一轮的速率样本不适用于续传)
                            pendingBuilder.clear()
                            chunkIntervals.clear()
                            lastChunkAt = 0L
                            // 重置 token 计数与首 token 时间,让续传重新计时
                            // (但不重置 builder — preservePartialContent=true 保留已显示内容)
                            lastNotifChars = params.builder.length
                            lastNotifAt = System.currentTimeMillis()
                            lastTokenUpdateChars = params.builder.length
                            lastTokenUpdateAt = System.currentTimeMillis()
                            lastPersistChars = params.builder.length
                            lastPersistAt = System.currentTimeMillis()
                            // B3-03: UI 层续传去重已生效(duplicateRemaining 跳过重复前缀)。
                            // Provider 层 resumeFromText 已接入:已显示内容作为末尾 assistant 消息注入,模型从中断处续写。
                            return streamRound(params.copy(
                                retryCount = newRetryCount,
                                preservePartialContent = true,
                            ))
                        } else {
                            Logger.w("ChatVM", "网络未恢复,降级为手动重试")
                        }
                    }
                    if (!streamInterrupted &&
                        (retryType == ChatErrorType.NETWORK || retryType == ChatErrorType.RATE_LIMIT) &&
                        params.retryCount < MAX_STREAM_RETRIES
                    ) {
                        val newRetryCount = params.retryCount + 1
                        // v1.0.16: 退避 3s/10s/30s,覆盖典型切后台时长(5-15s)
                        val delayMs = when (newRetryCount) {
                            1 -> 3_000L
                            2 -> 10_000L
                            else -> 30_000L
                        }
                        Logger.w(
                            "ChatVM",
                            "stream 错误($retryType),${delayMs}ms 后重试 " +
                                "(第 $newRetryCount/$MAX_STREAM_RETRIES 次,round=${params.round}): $streamError",
                        )
                        kotlinx.coroutines.delay(delayMs)
                        lastUiUpdateChars = 0
                        lastUiUpdateAt = streamStartedAt
                        lastNotifChars = 0
                        lastNotifAt = streamStartedAt
                        lastTokenUpdateChars = 0
                        lastTokenUpdateAt = streamStartedAt
                        lastPersistChars = 0
                        lastPersistAt = streamStartedAt
                        firstTokenTime = 0L
                        // v1.0.4: 重置自适应切片状态,避免上一轮的速率样本污染新一轮
                        pendingBuilder.clear()
                        chunkIntervals.clear()
                        lastChunkAt = 0L
                        // v1.0.3: retry 时重新进入"等待首 token"阶段,
                        // 因为 builder 已被 streamRound 开头的 clear() 清空,
                        // UI 会重新显示 ShimmerBubble 直到首个 token 到达
                        _state.update { it.copy(isWaitingFirstToken = true) }
                        return streamRound(params.copy(retryCount = newRetryCount))
                    }
                }

                streamError?.let {
                    val type = classifyErrorType(it)
                    val displayMsg = ErrorMessages.resolve(appContext, it)
                    // v1.0.15: StreamInterrupted 时已保留部分内容,允许用户手动重试
                    val recoverable = streamInterrupted || type != ChatErrorType.API_KEY
                    addError(type, displayMsg, isRecoverable = recoverable)
                    updateAssistant(
                        params.currentAssistantId,
                        unmaskPii(params.builder.toString()),
                        unmaskPii(params.reasoningBuilder.toString()),
                        imageAccumulator.toList(),
                        isStreaming = false,
                    )
                    val partialAssistant = _messages.value.firstOrNull { it.id == params.currentAssistantId }
                    if (partialAssistant != null) {
                        try {
                            sessionRepository.upsertMessage(sessionId, partialAssistant)
                        } catch (e: Exception) {
                            Logger.e("ChatVM", "streamError upsertMessage failed", e)
                            addError(ChatErrorType.UNKNOWN, appContext.getString(R.string.err_chat_reply_save_failed, e.message ?: appContext.getString(R.string.err_chat_unknown)))
                        }
                    }
                    _state.update { it.copy(isStreaming = false, isWaitingFirstToken = false, toolProgressMessage = null) }
                    return StreamRoundResult.Error(type, displayMsg, params.builder.toString(), params.reasoningBuilder.toString())
                }

                // v1.0.54: 先判断本轮是否为工具轮,再推 UI —
                //   工具轮(有 toolCalls)的思考过程不显示(出戏),最终回复轮正常显示。
                val hasToolCalls = toolCallAccumulator.isNotEmpty()
                updateAssistant(
                    params.currentAssistantId,
                    unmaskPii(params.builder.toString()),
                    unmaskPii(params.reasoningBuilder.toString()).ifBlank { null },
                    imageAccumulator.toList(),
                    isStreaming = false,
                )
                if (!streamToUi) {
                    _messages.value = _messages.value
                }

                val finalizedAssistant = _messages.value.firstOrNull { it.id == params.currentAssistantId }
                val assistantMessage = if (hasToolCalls) {
                    UIMessage(
                        id = params.currentAssistantId,
                        role = MessageRole.ASSISTANT,
                        content = finalizedAssistant?.content ?: unmaskPii(params.builder.toString()),
                        // 保留 reasoning:用户需要看到思考过程,不能只显示首字
                        reasoning = finalizedAssistant?.reasoning ?: unmaskPii(params.reasoningBuilder.toString()).ifBlank { null },
                        mood = finalizedAssistant?.mood,
                        reflection = finalizedAssistant?.reflection,
                        thinkingSignature = finalizedAssistant?.thinkingSignature ?: thinkingSignature,
                        thinkingEncryptedContent = finalizedAssistant?.thinkingEncryptedContent ?: thinkingEncryptedContent,
                        imageBase64List = finalizedAssistant?.imageBase64List ?: emptyList(),
                        toolCalls = toolCallAccumulator.toSortedMap().map { (idx, triple) ->
                            ToolCall(
                                id = triple.first ?: "call_${System.currentTimeMillis()}_${idx}",
                                name = triple.second ?: "",
                                arguments = triple.third.toString(),
                            )
                        },
                    )
                } else {
                    val finalAssistant = finalizedAssistant ?: UIMessage(
                        id = params.currentAssistantId,
                        role = MessageRole.ASSISTANT,
                        content = unmaskPii(params.builder.toString()),
                        reasoning = unmaskPii(params.reasoningBuilder.toString()).ifBlank { null },
                        thinkingSignature = thinkingSignature,
                        thinkingEncryptedContent = thinkingEncryptedContent,
                        imageBase64List = imageAccumulator.toList(),
                    )
                    val withCitations = if (pendingRagCitations.isNotEmpty()) {
                        finalAssistant.copy(ragCitations = pendingRagCitations)
                    } else {
                        finalAssistant
                    }
                    withCitations
                }

                return StreamRoundResult.Success(
                    assistantMessage = assistantMessage,
                    hasToolCalls = hasToolCalls,
                    contentLength = params.builder.length,
                    firstTokenTime = firstTokenTime,
                )
            }

            override suspend fun requestToolApproval(toolName: String, toolCallId: String, argsPreview: String, args: Map<String, Any?>): ToolApprovalState {
                return this@ChatViewModel.requestToolApproval(toolName, toolCallId, argsPreview, args)
            }

            override fun onToolLoopError(type: ChatErrorType, message: String, recoverable: Boolean) {
                addError(type, message, recoverable)
            }

            // v1.x: 单个工具开始/结束回调(用于调试日志)
            //  默认空实现已存在于接口,这里覆盖做 debug 日志,便于追踪工具执行耗时与状态。
            override fun onToolStart(toolCallId: String, toolName: String) {
                if (experiments.debugMode) {
                    Logger.d("ChatVM", "onToolStart | tool=$toolName | id=$toolCallId | sessionId=$sessionId")
                }
            }

            override fun onToolFinish(toolCallId: String, toolName: String, success: Boolean, durationMs: Long) {
                if (experiments.debugMode) {
                    Logger.d(
                        "ChatVM",
                        "onToolFinish | tool=$toolName | success=$success | duration=${durationMs}ms | sessionId=$sessionId",
                    )
                }
            }
        }

        val toolLoopResult = toolOrchestrator.runLoop(
            params = ToolLoopParams(
                sessionId = sessionId,
                initialAssistantId = state.currentAssistantId,
                baseHistorySize = baseHistorySize,
                maxRounds = MAX_TOOL_ROUNDS,
                tools = tools,
                skillMap = state.skillMap,
                model = effectiveModel,
                providerConfig = effectiveProviderConfig,
                temperature = effectiveTemperature,
                maxTokens = assistant?.maxTokens,
                reasoningLevel = reasoningLevel,
                webSearchEnabled = _state.value.webSearchEnabled,
                experiments = experiments,
                assistant = assistant,
                initialBuilderContent = state.builder.toString(),
                initialReasoningContent = state.reasoningBuilder.toString(),
            ),
            conversationHistory = conversationHistory,
            host = toolLoopHost,
            accessor = this,
            taskCardCoordinator = taskCardCoordinator,
        )

        state.round = toolLoopResult.round
        state.totalCharCount = toolLoopResult.totalCharCount
        state.totalToolCallCount = toolLoopResult.totalToolCallCount
        state.firstTokenTime = toolLoopResult.firstTokenTime
        state.currentAssistantId = toolLoopResult.finalAssistantId
        toolAssistantId = null

        if (!toolLoopResult.success) {
            val err = toolLoopResult.error
            addError(err?.type ?: ChatErrorType.UNKNOWN, err?.message ?: appContext.getString(R.string.err_chat_unknown), isRecoverable = err?.type != ChatErrorType.API_KEY)
            _state.update { it.copy(isStreaming = false, isWaitingFirstToken = false, toolProgressMessage = null) }
            return false
        }

        // Phase 8.5 修复 S16: 工具调用达到 maxToolRounds 上限且未产生最终回复时,
        // 累积的 pendingRagCitations 需要附加到当前 assistant 消息。
        if (toolLoopResult.finalAssistantMessage == null && pendingRagCitations.isNotEmpty()) {
            val lastAssistant = _messages.value.firstOrNull { it.id == state.currentAssistantId }
            if (lastAssistant != null && lastAssistant.ragCitations.isEmpty()) {
                val withCitations = lastAssistant.copy(ragCitations = pendingRagCitations)
                _messages.value = _messages.value.map { msg ->
                    if (msg.id == withCitations.id) withCitations else msg
                }
                try {
                    sessionRepository.upsertMessage(sessionId, withCitations)
                } catch (e: Exception) {
                    Logger.e("ChatVM", "upsertMessage(citations) failed", e)
                    addError(ChatErrorType.UNKNOWN, appContext.getString(R.string.err_chat_ref_save_failed, e.message ?: appContext.getString(R.string.err_chat_unknown)))
                }
            }
        }

        // 将 finalAssistantMessage(含 citations + artifacts)写入 UI / DB / branch
        toolLoopResult.finalAssistantMessage?.let { finalAssistant ->
            val withCitations = when {
                toolLoopResult.citationUrls.isNotEmpty() && pendingRagCitations.isNotEmpty() ->
                    finalAssistant.copy(citationUrls = toolLoopResult.citationUrls, ragCitations = pendingRagCitations)
                toolLoopResult.citationUrls.isNotEmpty() ->
                    finalAssistant.copy(citationUrls = toolLoopResult.citationUrls)
                pendingRagCitations.isNotEmpty() ->
                    finalAssistant.copy(ragCitations = pendingRagCitations)
                else -> finalAssistant
            }
            val (replacedContent, artifacts) = ArtifactExtractor.extractArtifacts(
                sessionId = sessionId,
                messageId = state.currentAssistantId.toString(),
                content = withCitations.content,
            )
            val withArtifacts = if (artifacts.isNotEmpty()) {
                artifacts.forEach { artifactRepository.upsert(it) }
                withCitations.copy(
                    content = replacedContent,
                    artifactIds = artifacts.map { it.id },
                )
            } else {
                withCitations
            }
            _messages.value = _messages.value.map { msg ->
                if (msg.id == withArtifacts.id) withArtifacts else msg
            }
            conversationHistory.add(withArtifacts)
            try {
                sessionRepository.upsertMessage(sessionId, withArtifacts)
            } catch (e: Exception) {
                Logger.e("ChatVM", "upsertMessage failed", e)
                addError(ChatErrorType.UNKNOWN, appContext.getString(R.string.err_chat_reply_save_failed, e.message ?: appContext.getString(R.string.err_chat_unknown)))
            }
        rebuildConversationTree()
        }

        return true
    }

    // ===== Phase I: 收尾(状态清理 / debugInfo / 通知 / memory ticker)=====
    private suspend fun finalizeResponse(state: StreamRunState) {
        val experiments = state.experiments
        val sessionId = state.sessionId
        val sessionTitle = state.sessionTitle
        val streamStartedAt = state.streamStartedAt

        // v1.80 (M-CVM5): 原子更新,避免读-改-写竞态
        _state.update { it.copy(isStreaming = false, isWaitingFirstToken = false, toolProgressMessage = null) }

        // v1.x: 三钩子接入 — 生成完成后调用 applyOnGenerationFinish。
        // 跑一遍 onGenerationFinish 钩子,如果最终 assistant 消息被改变则写回 _messages.value + DB。
        // 仅处理最后一条 assistant 消息(本轮生成的),避免误改历史消息。
        // 容错:钩子内部失败由 TransformerPipeline 兜底跳过,不阻塞 finalizeResponse。
        resultOf {
            val ctx = state.transformContext ?: return@resultOf
            val currentAssistantId = state.currentAssistantId
            val finalAssistant = _messages.value.firstOrNull { it.id == currentAssistantId } ?: return@resultOf
            // 单条消息跑 onGenerationFinish(列表形式,与接口签名一致)
            val transformed = transformerPipeline.applyOnGenerationFinish(listOf(finalAssistant), ctx)
            val newAssistant = transformed.firstOrNull()
            if (newAssistant != null && newAssistant != finalAssistant) {
                _messages.value = _messages.value.map { if (it.id == currentAssistantId) newAssistant else it }
                resultOf { sessionRepository.upsertMessage(sessionId, newAssistant) }
                    .onError { msg, _ -> Logger.w("ChatVM", "onGenerationFinish upsertMessage failed: $msg") }
                // v1.0.30: 变体信息写入 DB（regenerate 时 _pendingVariantInfo 非空）
                _pendingVariantInfo?.let { vi ->
                    _pendingVariantInfo = null
                    viewModelScope.launch {
                        resultOf {
                            val entity = sessionRepository.getMessageById(newAssistant.id.toString())
                            if (entity != null) {
                                sessionRepository.upsertMessageEntity(
                                    entity.copy(
                                        variantGroupId = vi.groupId,
                                        variantIndex = vi.index,
                                        variantCount = vi.count,
                                    )
                                )
                            }
                        }.onError { msg, _ -> Logger.w("ChatVM", "applyVariant failed: $msg") }
                    }
                }
            }
        }.onError { msg, _ -> Logger.w("ChatVM", "applyOnGenerationFinish failed: $msg") }

        // v0.45: 流式结束后刷新上下文 token 占用(完整回复已写入 messages)
        refreshContextInfo()

        // v1.42: 上下文溢出保护 — token 占用超过 80% 时在后台自动压缩,
        // 从流式启动关键路径移到响应结束后,避免阻塞首字返回。
        resultOf { triggerAutoCompress(sessionId) }

        // v2.3: debugMode 下填充 debugInfo(含 TTFT/token 速率等性能指标)
        if (experiments.debugMode) {
            val elapsedMs = System.currentTimeMillis() - streamStartedAt
            val ttftMs = if (state.firstTokenTime > 0L) state.firstTokenTime - streamStartedAt else -1L
            val elapsedSec = (elapsedMs / 1000f).coerceAtLeast(0.001f)
            val tokenRate = state.totalCharCount / elapsedSec
            val selectedModel = resultOf { settings.getSelectedModel() }.getOrNull()
            val modelName = selectedModel?.name ?: selectedModel?.id ?: "未知"
            val debugInfo = buildString {
                append("模型: $modelName")
                append(" | 耗时: ${elapsedMs}ms")
                if (ttftMs >= 0) append(" | TTFT: ${ttftMs}ms")
                append(" | 速率: ${"%.1f".format(tokenRate)} tok/s")
                append(" | 字符: ${state.totalCharCount}")
                append(" | 工具调用: ${state.totalToolCallCount}")
                append(" | 轮次: ${state.round}")
            }
            _state.update { it.copy(debugInfo = debugInfo) }
            Logger.d(
                "ChatVM-Debug",
                "launchStream done | sessionId=$sessionId | $debugInfo",
            )
        }

        // 通知:流式完成 — 取消进度通知,发"回复完成"通知
        // v1.117: 改用 resultOf 避免吞 CancellationException(notificationPolicyFlow.first 是 suspend)
        resultOf {
            notificationManager.updateLiveProgress(sessionTitle, 0, false)
            val finalText = _messages.value
                .firstOrNull { it.id == state.currentAssistantId }?.content.orEmpty()
            val preview = finalText.ifBlank { appContext.getString(R.string.err_chat_reply_generated) }
            // v0.32: 接入通知策略(never / when_unfocused / always)
            val policy = settings.notificationPolicyFlow.first()
            notificationManager.notifyChatCompletedWithPolicy(policy, sessionTitle, preview)
        }.onError { msg, t ->
            Logger.w("ChatVM", "流式完成通知失败: $msg", t)
        }

        // 通知 memory ticker: 一轮对话结束(后台跑 rollingSummary + daily check)
        // v1.78 (#35): runCatching 包裹 — notifyTurn 失败不应影响已完成的流式回复
        val conversationMessages = _messages.value
        val selectedModel = resultOf { settings.getSelectedModel() }.getOrNull()
        runCatching {
            memoryTicker.notifyTurn(
                sessionId,
                conversationMessages,
                selectedModel,
                assistantId = _state.value.currentAssistant?.id ?: "",
            )
        }.onFailure { Logger.w("ChatVM", "notifyTurn failed: ${it.message}") }
        // B5-01: 生成正常结束,清理检查点
        resultOf { sessionRepository.deleteGenerationCheckpoint(state.currentAssistantId.toString()) }
            .onError { msg, _ -> Logger.w("ChatVM", "generation checkpoint 清理失败: $msg") }
        // R-UI-02: 本轮生成结束后清除生成焦点(仅当仍指向本会话)。
        if (resultOf { settings.getGeneratingSessionId() }.getOrNull() == sessionId) {
            resultOf { settings.saveGeneratingSessionId(null) }
                .onError { msg, _ -> Logger.w("ChatVM", "saveGeneratingSessionId 清理失败: $msg") }
        }
    }

    /**
     * v1.97: 切页/切会话/切 Tab 时脱离流式 UI,不停止后台生成。
     *
     * 与 [stop] 的区别:
     * - stop():用户主动停止,取消 SSE 连接 + 生成协程
     * - detachStreaming():切页触发,生成闭包继续在 appScope 运行
     *   - updateAssistant 因 index==-1 静默跳过(不更新错误会话的 messages)
     *   - persistCurrentAssistant 用 builder 内容直接落盘(不依赖 _messages.value)
     *   - 通知仍正常更新(notificationManager 不依赖 _state)
     *   - 切回原会话时从 DB 加载最新内容(含中间落盘)+ 恢复 isStreaming
     */
    private fun detachStreaming() = streamCoordinator.detachStreaming()

    /** 用户点"停止"。 */
    fun stop() {
        // v1.43: 通过应用级生成管理器取消,确保切页/后台时也能停止后台生成任务
        // v1.113: 只停止单聊的生成,不影响群聊
        val sid = _state.value.currentSessionId ?: _state.value.agentSessionId
        chatGenerationManager.stop(sid)
        // R-UI-02: 用户停止后清除该会话的生成焦点。
        viewModelScope.launch {
            if (resultOf { settings.getGeneratingSessionId() }.getOrNull() == sid) {
                resultOf { settings.saveGeneratingSessionId(null) }
                    .onError { msg, _ -> Logger.w("ChatVM", "saveGeneratingSessionId 清理失败: $msg") }
            }
        }
        runCatching { ChatGenerationService.stop(appContext) }
        imageJob?.cancel()
        imageJob = null
        translateJob?.cancel()
        translateJob = null
        _state.update {
            it.copy(
                isStreaming = false,
                // v1.0.3: stop() 也要清除等待首 token 状态
                isWaitingFirstToken = false,
                isGeneratingImage = false,
                isTranslating = false,
                translatingMessageId = null,
                pendingToolApprovals = emptyList(),
                // v1.0.4: 同时清掉工具恢复进度文本,避免残留
                toolProgressMessage = null,
            )
        }
        // 取消所有待审批的工具调用(防止 stop 后幽灵审批卡片 + requestToolApproval 协程挂起)
        cancelAllPendingApprovals()
        // 通知:用户停止时取消进度通知
        runCatching {
            notificationManager.updateLiveProgress("", 0, false)
        }.onFailure { Logger.w("ChatVM", "取消进度通知失败: ${it.message}") }
        // v1.79 (H-CV4): 移除 stop() 中的持久化逻辑。
        // 原:stop() 读取 state 持久化 assistant 消息,与流式 catch(CancellationException) 块的
        // persistCurrentAssistant / upsertMessage 竞态(两个协程同时 upsert 同一条消息)。
        // 流式 catch 块已持久化带 [已中断] 标记的部分回复(行 2701-2715),无需重复持久化。
        // 断点续传:stop() 不清理 PendingToolCallStore — 未完成的工具调用保留在持久化文件中,
        // 下次进入此会话时由 Banner 提示用户选择"恢复执行"或"丢弃"。
    }

    /**
     * 断点续传:恢复指定会话的未完成工具调用。
     *
     * 触发场景:用户在上次对话中手动停止流式 / App 崩溃 / 进程被杀,
     * 导致 LLM 已决策的 tool_calls 未执行完毕。下次进入此会话时由 Banner 提示,
     * 用户点"恢复执行"按钮触发本函数。
     *
     * 执行流程:
     *  1. 从 [PendingToolCallStore] 取该会话的全部 pending(按 createdAt 升序)
     *  2. 依次执行(skill 走 SkillExecutor,本地工具走 ToolRegistry)
     *     - 复用 [TOOL_TIMEOUT_MS] 超时 + [MAX_TOOL_RESULT_CHARS] 体积限制
     *  3. 每个工具结果构造为 TOOL 消息(保留原始 [PendingToolCallStore.PendingToolCall.toolCallId],
     *     让 LLM 能对应上),持久化到 DB 并追加到 [_messages.value]
     *  4. 全部执行完成后,从 PendingToolCallStore 清理本会话的 pending 记录
     *  5. 追加一个空的 ASSISTANT 占位消息,调 [launchStream] 让 LLM 基于工具结果继续回复
     *
     * 失败容忍:单个工具执行失败不影响其他工具,失败结果仍构造为 TOOL 消息回填给 LLM,
     * 让 LLM 自行决定是否重试或换方案(与正常流式工具调用一致)。
     *
     * @param chatId 会话 id(应等于 [_state.value.currentSessionId])
     */
    fun resumePendingToolCalls(chatId: String) {
        // 防止与正在进行的流式生成冲突
        if (_state.value.isStreaming) {
            addError(ChatErrorType.UNKNOWN, appContext.getString(R.string.err_chat_resume_busy))
            return
        }
        viewModelScope.launch {
            val pendings = resultOf { PendingToolCallStore.getForChat(chatId) }
                .onError { msg, t ->
                    Logger.e("ChatVM", "resumePendingToolCalls getForChat 失败: $msg", t)
                    addError(ChatErrorType.UNKNOWN, appContext.getString(R.string.err_chat_resume_read_failed, t?.message ?: msg))
                }.getOrNull() ?: emptyList()
            if (pendings.isEmpty()) {
                _state.update { it.copy(pendingToolCallCount = 0) }
                return@launch
            }
            // 加载启用的 skill 列表,构建 id → SkillEntity 映射(与 launchStream 内的逻辑一致)
            // v1.0.47 P3: 会话级 skill 覆盖 — 优先用 session.skillIdsJson(非"[]"且非空),
            // 否则回退到 assistant.skillIdsJson(默认行为不变)
            val sessionSkillIdsJson = _state.value.sessions
                .firstOrNull { it.id == chatId }?.skillIdsJson
            val effectiveSkillIdsJson = if (!sessionSkillIdsJson.isNullOrEmpty() && sessionSkillIdsJson != "[]") {
                sessionSkillIdsJson
            } else {
                _state.value.currentAssistant?.skillIdsJson
            }
            val enabledSkillIds = effectiveSkillIdsJson?.let { json ->
                runCatching { idListJson.decodeFromString<List<String>>(json) }.getOrNull()
            }
            val skillMap = resultOf { skillRepository.listEnabledByIds(enabledSkillIds) }
                .getOrNull()?.associateBy { it.id } ?: emptyMap()

            // v1.0.4 (P0): 进入"等待首 token"阶段 + 设置工具恢复进度文本,
            // 让 ShimmerBubble 在工具执行期间显示"正在执行 web_search (1/3)…"
            // (原来此阶段 isStreaming=false,ShimmerBubble 不显示,用户看到空白)
            _state.update {
                it.copy(
                    isStreaming = true,
                    isWaitingFirstToken = true,
                    toolProgressMessage = appContext.getString(R.string.tool_resume_starting),
                    errors = emptyList(),
                )
            }

            // 逐个执行 pending 工具,构造 TOOL 消息
            val now = System.currentTimeMillis()
            for ((stepIndex, pending) in pendings.withIndex()) {
                // 每步更新进度文本(skill 内部的 onProgress 会进一步覆盖为"正在搜索..."等具体文案)
                _state.update {
                    it.copy(
                        toolProgressMessage = appContext.getString(
                            R.string.tool_resume_step,
                            pending.toolName,
                            stepIndex + 1,
                            pendings.size,
                        ),
                    )
                }
                val toolResult = resultOf {
                    withTimeoutOrNull(TOOL_TIMEOUT_MS) {
                        val skill = skillMap[pending.toolName]
                        if (skill != null) {
                            // v1.0.4 (P0): 传 onProgress 回调,SkillExecutor 在调用 web_search 等
                            // 耗时工具前会回调"正在搜索..."等本地化文本,覆盖默认的"正在执行 xxx"
                            skillExecutor.execute(
                                skill = skill,
                                argumentsJson = pending.arguments,
                                onProgress = { msg ->
                                    _state.update { state -> state.copy(toolProgressMessage = msg) }
                                },
                            )
                        } else {
                            withContext(Dispatchers.IO) {
                                toolRegistry.executeFromJson(pending.toolName, pending.arguments)
                            }
                        }
                    }
                }.getOrNull() ?: appContext.getString(R.string.err_chat_tool_timeout, pending.toolName, (TOOL_TIMEOUT_MS / 1000).toInt())
                // 体积限制(与 launchStream 内的 C1-1 一致)
                val finalResult = if (toolResult.length > MAX_TOOL_RESULT_CHARS) {
                    toolResult.take(MAX_TOOL_RESULT_CHARS) +
                        "\n\n" + appContext.getString(R.string.err_chat_tool_result_truncated)
                } else {
                    toolResult
                }
                // 构造 TOOL 消息:保留原始 toolCallId,让 LLM 能对应上之前发出的 tool_calls
                val toolMsg = UIMessage(
                    role = MessageRole.TOOL,
                    content = finalResult,
                    toolCallId = pending.toolCallId,
                )
                // 追加到 _messages.value(launchStream 会从 messages.dropLast(1) 取历史)
                _messages.value = _messages.value + toolMsg
                // 持久化到 DB(供下次启动时 LLM 仍能看到工具结果)
                resultOf { sessionRepository.upsertMessage(chatId, toolMsg) }
                    .onError { msg, t ->
                        Logger.e("ChatVM", "resumePendingToolCalls upsertMessage 失败: $msg", t)
                        addError(ChatErrorType.UNKNOWN, appContext.getString(R.string.err_chat_tool_result_save_failed, t?.message ?: msg))
                    }
                // 同步记录到 toolCallHistory(InputBar 动态胶囊展示)
                val isSuccess = isToolResultSuccess(finalResult)
                _state.update {
                    it.copy(
                        toolCallHistory = it.toolCallHistory + ToolCallRecord(
                            toolName = pending.toolName,
                            arguments = pending.arguments,
                            result = finalResult,
                            isSuccess = isSuccess,
                            timestamp = now,
                        ),
                    )
                }
                // 从 pending store 移除(已执行完成)
                resultOf { PendingToolCallStore.remove(pending.toolCallId) }
                    .onError { msg, t -> Logger.w("ChatVM", "resumePendingToolCalls remove 失败: $msg", t) }
            }

            // 清空 pending 计数(Banner 隐藏)+ 清空工具恢复进度文本
            // (ShimmerBubble 将回退到默认"思考中",直到 launchStream 首 token 到达)
            _state.update {
                it.copy(
                    pendingToolCallCount = 0,
                    toolProgressMessage = null,
                )
            }

            // 追加空 ASSISTANT 占位消息,触发 launchStream 让 LLM 基于工具结果继续回复
            val assistantMsg = UIMessage(role = MessageRole.ASSISTANT, content = "")
            _messages.value = _messages.value + assistantMsg
            _state.update {
                it.copy(
                    isStreaming = true,
                    // v1.0.3: 断点续传也进入"等待首 token"阶段
                    isWaitingFirstToken = true,
                    errors = emptyList(),
                )
            }
            launchStream(assistantMsg.id, chatId)
        }
    }

    /**
     * 断点续传:丢弃指定会话的全部未完成工具调用。
     *
     * 用户在 Banner 上点"丢弃"按钮时触发。清理 PendingToolCallStore 中该会话的记录,
     * 不执行任何工具,Banner 隐藏。已持久化的 ASSISTANT tool_calls 消息保留在 DB 中
     * (作为对话历史的一部分),LLM 下次回复时可能看到自己曾发出 tool_calls 但无对应
     * TOOL 响应 — 这是用户主动丢弃的预期行为,LLM 通常会自行换方案继续。
     *
     * @param chatId 会话 id(应等于 [_state.value.currentSessionId])
     */
    fun discardPendingToolCalls(chatId: String) {
        viewModelScope.launch {
            resultOf { PendingToolCallStore.clearForChat(chatId) }
                .onError { msg, t -> Logger.w("ChatVM", "discardPendingToolCalls 失败: $msg", t) }
            _state.update { it.copy(pendingToolCallCount = 0) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        release()
    }

    /**
     * 释放 TTS/ASR 等资源。
     *
     * v1.92: ChatViewModel 改为 single{} 后 onCleared 永不调用,
     * 由 MuseApp 的 ProcessLifecycleOwner ON_STOP 观察者显式调用。
     */
    fun release() {
        // 语音对话模式:取消循环协程并释放 ASR/TTS 资源(避免后台继续录音/朗读)
        stopVoiceConversation()
        // Phase 8.7: ViewModel 销毁时停止 TTS(避免页面退出后继续朗读)
        stopTts()
        // v1.53: 清除 TTS 状态回调,避免单例 TtsManager 持有已销毁 ViewModel 的回调导致内存泄漏
        ttsManager.onStateChange = null
        notifySessionEndForCurrent()
        // v1.91: 释放流式 ASR Controller(AudioRecord / WebSocket / 协程 scope)
        // v1.105: 委托至 ChatAudioCoordinator
        audioCoordinator.disposeAsr()
        // v1.x: 释放当前会话的引用计数(与 switchSession / createNewSession 中的 acquire 配对)
        currentSessionIdForApproval()?.let { sessionManager.release(it) }
    }

    /**
     * v1.0.29: 应用切到后台时调用。
     *
     * 如果当前有正在进行的流式生成,启动前台服务通知(让用户知道后台生成进度,
     * 同时保持进程前台优先级防止被系统回收)。前台时不显示通知(避免"正在生成"打扰)。
     */
    fun onAppBackground() {
        val active = chatGenerationManager.activeGeneration.value
        if (active != null && active.isStreaming) {
            Logger.i("ChatViewModel", "应用切后台,启动前台服务保活(会话: ${active.sessionTitle})")
            runCatching { ChatGenerationService.start(appContext) }
                .onFailure { Logger.w("ChatViewModel", "前台服务启动失败,切后台可能被回收", it) }
        }
    }

    /**
     * v1.0.29: 应用切回前台时调用。
     *
     * 停止前台服务通知(不再需要保活通知打扰用户)。生成任务继续在应用级协程中运行。
     */
    fun onAppForeground() {
        runCatching { ChatGenerationService.stop(appContext) }
        runCatching { notificationManager.updateLiveProgress("", 0, false) }
            .onFailure { Logger.w("ChatVM", "取消进度通知失败: ${it.message}") }
    }

    /**
     * 通知 ticker 当前 session 结束(fire-and-forget)。
     *
     * Phase 8.5 修复: 原实现用 `runBlocking { settings.getSelectedModel() }` 在主线程阻塞,
     * onCleared / createNewSession / switchSession 调用时会 ANR。
     * 改为:model 传 null(MemoryTicker 内部 launchTracked 异步执行,能处理 null model
     * 的降级场景),完全去掉 runBlocking。
     */
    private fun notifySessionEndForCurrent() {
        // v1.0.52 P2-3: 会话结束/切换前触发 AI 记忆自动保存(非阻塞,后台执行)
        triggerMemoryAutoSaveIfNeeded()
        streamCoordinator.notifySessionEndForCurrent()
    }

    /**
     * v1.0.52 P2-3: 触发 AI 记忆自动保存(会话结束/切换时)。
     *
     * 把当前会话的对话历史交给 [MemoryAutoSaveScheduler],由其在后台 scope 中
     * 调 LLM 提取实体/关系/合并/分类,非阻塞,不影响 UI。
     *
     * 仅在 [memoryAutoSaveScheduler] 非空时触发;
     * 历史为空或过短(<2 条)时跳过。
     */
    private fun triggerMemoryAutoSaveIfNeeded() {
        val scheduler = memoryAutoSaveScheduler ?: return
        val sessionId = _state.value.currentSessionId ?: return
        // v1.0.54: 30 秒内同会话去重 — 切 Tab/切会话/重启上下文会反复触发
        //   notifySessionEndForCurrent,每次排队一个 autoSave;网络慢时堆积十几次,
        //   用户感知"回复后一直不停下"(后台持续调用 completeText)。
        val now = System.currentTimeMillis()
        if (sessionId == lastAutoSaveSessionId && now - lastAutoSaveAt < 30_000L) return
        lastAutoSaveSessionId = sessionId
        lastAutoSaveAt = now
        val history = _messages.value
        if (history.size < 2) return
        val assistantId = _state.value.currentAssistant?.id ?: "default"
        val spaceId = "default" // v1.0.52: 当前 Space 由 SettingsRepository 持有,此处简化用 default
        val scope = if (assistantId == "default") "main" else assistantId
        val locale = "zh-CN"
        // model 传 null,MemoryLlmClient 实现侧用 Provider 配置的默认模型
        scheduler.scheduleAutoSave(
            sessionId = sessionId,
            history = history,
            assistantId = assistantId,
            spaceId = spaceId,
            scope = scope,
            model = null,
            locale = locale,
        )
    }

    /**
     * v1.80 (M-CVM2): 标记当前 assistant 消息为 [已中断] 并把部分回复落盘。
     *
     * 在流式被取消(用户点停止/切会话)或异常中断时调用,确保已接收的内容不随 ViewModel 销毁丢失。
     * 持久化用 [NonCancellable] 包裹,保证协程被取消时仍能完成 DB 写入
     * (否则 suspend 调用在已取消协程中会立即抛 CancellationException,落盘失败)。
     */
    private suspend fun persistInterruptedAssistant(sessionId: String, partialMsg: UIMessage? = null) =
        streamCoordinator.persistInterruptedAssistant(sessionId, partialMsg)

    /**
     * v1.43: 周期性落盘 — 把当前 assistant 消息的流中进度持久化到数据库,
     * 让切页/后台后的新 ViewModel 能从 DB 恢复最新内容。
     * 不提取产物(产物只在流式结束后提取),也不附加 citation(流式中 pending)。
     *
     * v1.97 (P1-2): 周期性落盘用 skipFts=true 跳过 FTS 重建(toNgram 对长文本开销大)。
     * 流式结束后最终落盘(直接 upsertMessage)会同步 FTS;中断走 persistInterruptedAssistant 也同步。
     * 若 app 崩溃导致 FTS 漂移,下次启动 ensureFtsIndexConsistent 会自动 rebuild。
     */
    private fun persistCurrentAssistant(sessionId: String, assistantId: Uuid, msg: UIMessage? = null) =
        streamCoordinator.persistCurrentAssistant(sessionId, assistantId, msg, ::addError)

    private fun updateAssistant(
        id: Uuid,
        content: String,
        reasoning: String? = null,
        imageBase64List: List<String>? = null,
        imageUrls: List<String>? = null,
        videoFileUri: String? = null,
        isStreaming: Boolean = false,
    ) = streamCoordinator.updateAssistant(id, content, reasoning, imageBase64List, imageUrls, videoFileUri, isStreaming)

    /**
     * B3-02: 流式 ThinkTag 剥离。
     *
     * 只有内容包含 <think> 时才跑视觉管道,避免破坏 50ms 自适应切片节流;
     * 其余情况走原始 updateAssistant 快速路径。
     */
    private suspend fun updateAssistantWithVisualTransform(
        state: StreamRunState,
        assistantId: Uuid,
        content: String,
        reasoning: String? = null,
        isStreaming: Boolean = false,
    ) {
        val ctx = state.transformContext
        val raw = UIMessage(id = assistantId, role = MessageRole.ASSISTANT, content = content, reasoning = reasoning)
        val visual = if (ctx != null && content.contains("<think>", ignoreCase = true)) {
            transformerPipeline.applyVisualTransform(listOf(raw), ctx).firstOrNull() ?: raw
        } else raw
        updateAssistant(visual.id, visual.content, visual.reasoning, isStreaming = isStreaming)
    }

    // ── v1.135: 媒体/富内容工具执行函数(注册到 ToolRegistry) ─────────────────

    /** 合并新内容到当前助手消息,避免覆盖 LLM 在工具调用前已输出的说明文本。 */
    private fun mergeAssistantContent(assistantId: Uuid, newContent: String): String {
        val existing = _messages.value.firstOrNull { it.id == assistantId }?.content?.trim() ?: ""
        return if (existing.isNotBlank() && !existing.contains(newContent)) {
            "$existing\n\n$newContent"
        } else {
            newContent
        }
    }

    /**
     * v1.135: 工具调用入口 —— 根据用户描述生成图片。
     *
     * v1.0.18: 通过 ImageProviderRegistry 选择 provider(支持 OpenAI / Agnes 等);
     * 工具参数 [reference_image] 用于图生图;模型选择优先级:
     *  args.model → imageGenConfig.modelId → provider 模型列表中 outputModalities 含 image 的模型。
     *
     * 使用用户在设置中配置的绘图供应商/模型;未显式配置则回退到当前激活 Provider。
     * 生成成功后把图片 URL 写入当前助手消息的 [imageUrls],UI 立即展示。
     */
    private suspend fun execGenerateImage(args: Map<String, String>): String {
        val prompt = args["prompt"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: prompt"
        val size = args["size"]?.takeIf { it.isNotBlank() }
            ?: _state.value.imageGenParams.size
        val quality = args["quality"]?.takeIf { it.isNotBlank() }
            ?: _state.value.imageGenParams.quality
        val style = args["style"]?.takeIf { it.isNotBlank() }
            ?: _state.value.imageGenParams.style
        val n = args["n"]?.toIntOrNull()?.coerceAtLeast(1)
            ?: _state.value.imageGenParams.n
        // v1.0.18: 参考图(图生图),支持 URL / base64 / data URI
        val referenceImage = args["reference_image"]?.takeIf { it.isNotBlank() }
            ?: _state.value.imageGenParams.referenceImageUri
        val assistantId = toolAssistantId
            ?: return "错误: 无法确定当前助手消息,请重新发送请求"

        updateAssistant(assistantId, content = appContext.getString(R.string.err_chat_img_generating), isStreaming = true)
        return try {
            val imageGenConfig = settings.imageGenConfigFlow.first()
            val providerConfig = imageGenConfig.providerId.takeIf { it.isNotBlank() }
                ?.let { settings.getProviderById(it) }
                ?: settings.get()
                ?: return "未配置图片生成供应商,请先添加支持绘图的 Provider(如 OpenAI / Agnes)"
            if (providerConfig.apiKey.isBlank() && !providerConfig.allowMissingApiKey) {
                return "图片生成供应商的 API Key 为空"
            }
            // 模型选择:args.model → imageGenConfig.modelId → provider 模型列表筛选(outputModalities 含 image)
            // 留空时由 ImageService.resolveModelId 兜底(provider 默认值)
            val model = args["model"]?.takeIf { it.isNotBlank() }
                ?: imageGenConfig.modelId.takeIf { it.isNotBlank() }
                ?: providerConfig.models.firstOrNull { it.supportsImageOutput() }?.id
                ?: _state.value.imageGenParams.model
            val params = io.zer0.ai.image.ImageGenParams(
                model = model,
                size = size,
                quality = quality,
                style = style,
                responseFormat = _state.value.imageGenParams.responseFormat,
                n = n,
                referenceImageUri = referenceImage,
            )
            val urls = imageService.generate(prompt, params, providerConfig)
            if (urls.isEmpty()) {
                updateAssistant(assistantId, content = appContext.getString(R.string.err_chat_img_gen_failed_no_result))
                return "图片生成失败: 未返回结果"
            }
            updateAssistant(
                assistantId,
                content = mergeAssistantContent(assistantId, appContext.getString(R.string.err_chat_img_generated)),
                imageUrls = urls,
                isStreaming = false,
            )
            "图片生成成功: ${urls.joinToString(", ")}"
        } catch (e: kotlinx.coroutines.CancellationException) {
            updateAssistant(assistantId, content = appContext.getString(R.string.err_chat_img_cancelled))
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: appContext.getString(R.string.err_chat_unknown)
            updateAssistant(assistantId, content = appContext.getString(R.string.err_chat_img_gen_failed, msg))
            "图片生成失败: $msg"
        }
    }

    /**
     * v1.136: 工具调用入口 —— 根据用户描述生成短视频。
     *
     * 动态选择已配置且支持视频输出的供应商/模型:
     *  - 若 args 显式指定 provider_id,优先使用该供应商;
     *  - 若 args 显式指定 model,优先使用包含该模型的供应商;
     *  - 否则自动选择第一个支持视频输出的模型。
     *
     * 生成成功后把视频 URL 写入当前助手消息的 [videoFileUri],
     * MessageBubble 会渲染为可点击播放的视频卡片。
     */
    private suspend fun execGenerateVideo(args: Map<String, String>): String {
        val prompt = args["prompt"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: prompt"
        val duration = args["duration"]?.toIntOrNull()?.let { if (it == 5 || it == 10) it else 5 } ?: 5
        val resolution = args["resolution"]?.takeIf { it.isNotBlank() } ?: "720p"
        val requestedModelId = args["model"]?.takeIf { it.isNotBlank() }
        val requestedProviderId = args["provider_id"]?.takeIf { it.isNotBlank() }
        // v1.137: 参考图列表(可选,用于图生视频/多图生视频)
        // 支持逗号分隔的字符串(多图)或单个 data URI / URL
        val referenceImages: List<String> = args["reference_images"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val assistantId = toolAssistantId
            ?: return "错误: 无法确定当前助手消息,请重新发送请求"

        // v1.136: 自动选择支持视频输出的供应商/模型
        val providers = settings.getAllProviders().filter { it.enabled && it.apiKey.isNotBlank() }
        // 读取用户在"设置→视频生成"配置的默认供应商/模型(LLM 未显式指定时优先使用)
        val videoGenConfig = settings.videoGenConfigFlow.first()
        val (providerConfig, videoModel) = when {
            requestedProviderId != null -> {
                val config = providers.firstOrNull { it.id == requestedProviderId }
                    ?: return "未找到供应商: $requestedProviderId"
                val model = requestedModelId?.let { id ->
                    config.models.firstOrNull { it.id == id && it.supportsVideoOutput() }
                } ?: config.models.firstOrNull { it.supportsVideoOutput() }
                    ?: return "供应商 ${config.displayName} 没有支持视频输出的模型"
                config to model
            }
            requestedModelId != null -> {
                val config = providers.firstOrNull { p ->
                    p.models.any { it.id == requestedModelId && it.supportsVideoOutput() }
                } ?: return "未找到支持模型 $requestedModelId 的供应商"
                val model = config.models.first { it.id == requestedModelId && it.supportsVideoOutput() }
                config to model
            }
            // 优先使用用户配置的视频供应商(VideoGenConfig.providerId)
            videoGenConfig.providerId.isNotBlank() -> {
                val config = providers.firstOrNull { it.id == videoGenConfig.providerId }
                    ?: return "未找到视频生成供应商: ${videoGenConfig.providerId}"
                val model = videoGenConfig.modelId.takeIf { it.isNotBlank() }?.let { id ->
                    config.models.firstOrNull { it.id == id && it.supportsVideoOutput() }
                } ?: config.models.firstOrNull { it.supportsVideoOutput() }
                    ?: return "供应商 ${config.displayName} 没有支持视频输出的模型"
                config to model
            }
            else -> {
                val config = providers.firstOrNull { p -> p.models.any { it.supportsVideoOutput() } }
                    ?: return "未配置支持视频生成的供应商。请在「设置→模型与服务」中为某个模型开启「视频输出」能力。"
                val model = config.models.first { it.supportsVideoOutput() }
                config to model
            }
        }

        updateAssistant(assistantId, content = appContext.getString(R.string.err_chat_video_generating), isStreaming = true)
        // v1.0.4 (P1): 同时设置 isGeneratingVideo=true,让 ChatScreen 显示视频生成占位卡片
        _state.update { it.copy(isGeneratingVideo = true) }
        val startedAt = System.currentTimeMillis()
        // v1.135: 每 5 秒刷新一次进度提示,让用户感知长任务仍在进行。
        val progressJob = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
            while (isActive) {
                kotlinx.coroutines.delay(5_000)
                val elapsed = (System.currentTimeMillis() - startedAt) / 1000
                updateAssistant(
                    assistantId,
                    content = appContext.getString(R.string.err_chat_video_progress, elapsed),
                    isStreaming = true,
                )
            }
        }
        return try {
            val request = io.zer0.ai.video.VideoGenRequest(
                prompt = prompt,
                model = videoModel.id,
                duration = duration,
                resolution = resolution,
                referenceImages = referenceImages,
            )
            // v1.137: 通过 VideoProviderRegistry 按 specId/host 路由,
            // 不再按 providerId 硬匹配(修复 preset_kling ≠ kling 的路由 bug)
            val result = videoGenerationService.generateVideo(providerConfig, request)
            val videoUrl = result.getOrThrow()
            updateAssistant(
                assistantId,
                content = mergeAssistantContent(assistantId, appContext.getString(R.string.err_chat_video_generated)),
                videoFileUri = videoUrl,
                isStreaming = false,
            )
            "视频生成成功: $videoUrl"
        } catch (e: kotlinx.coroutines.CancellationException) {
            updateAssistant(assistantId, content = appContext.getString(R.string.err_chat_video_cancelled))
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: appContext.getString(R.string.err_chat_unknown)
            updateAssistant(assistantId, content = appContext.getString(R.string.err_chat_video_gen_failed, msg))
            "视频生成失败: $msg"
        } finally {
            progressJob.cancel()
            // v1.0.4 (P1): 无论成功/失败/取消都清除视频生成标志
            _state.update { it.copy(isGeneratingVideo = false) }
        }
    }

    /**
     * v1.135: 工具调用入口 —— 生成二维码。
     *
     * 把任意文本转为二维码图片(base64 data URI),并写入当前助手消息的 [imageUrls] 展示。
     */
    private suspend fun execGenerateQrCode(args: Map<String, String>): String {
        val content = args["content"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: content"
        val size = args["size"]?.toIntOrNull()?.coerceIn(128, 1024) ?: 400
        val assistantId = toolAssistantId
            ?: return "错误: 无法确定当前助手消息,请重新发送请求"

        return try {
            val bitmap = io.zer0.muse.ui.qrcode.QrCodeGenerator.generateQrBitmap(content, size)
                ?: return "二维码生成失败"
            val bytes = java.io.ByteArrayOutputStream().apply {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this)
            }.toByteArray()
            bitmap.recycle()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val dataUri = "data:image/png;base64,$base64"
            updateAssistant(
                assistantId,
                content = mergeAssistantContent(assistantId, appContext.getString(R.string.err_chat_qr_generated)),
                imageUrls = listOf(dataUri),
            )
            "二维码生成成功"
        } catch (e: Exception) {
            "二维码生成失败: ${e.message ?: "未知错误"}"
        }
    }

    // ── Phase 10.1: 任务卡完整调度(v1.134 P1-5 转发到 taskCardCoordinator) ────

    /** 判断工具执行结果是否成功(转发到 taskCardCoordinator)。 */
    private fun isToolResultSuccess(result: String): Boolean =
        taskCardCoordinator.isToolResultSuccess(result)

    /** 更新任务卡阶段(转发到 taskCardCoordinator)。 */
    private fun updateTaskCardPhase(taskCardId: String, phase: io.zer0.muse.ui.taskcard.TaskCardPhase) =
        taskCardCoordinator.updateTaskCardPhase(taskCardId, phase)

    /** 精准更新单个 TaskStep(转发到 taskCardCoordinator)。 */
    private fun updateTaskCardStep(
        taskCardId: String,
        stepIndex: Int,
        transform: (io.zer0.muse.ui.taskcard.TaskStep) -> io.zer0.muse.ui.taskcard.TaskStep,
    ) = taskCardCoordinator.updateTaskCardStep(taskCardId, stepIndex, transform)

    /** 切换任务卡展开 / 折叠状态(转发到 taskCardCoordinator)。 */
    fun toggleTaskCardExpand(taskCardId: String) =
        taskCardCoordinator.toggleTaskCardExpand(taskCardId)

    /**
     * 重试任务卡中失败的步骤(转发到 taskCardCoordinator)。
     *
     * - stepId = "ALL_FAILED":重试全部失败步骤
     * - stepId = 具体 step id:重试单个失败步骤
     *
     * 注意:重试仅更新 UI 状态(FAILED → RUNNING → SUCCESS/FAILED),
     * 不重新请求 LLM(工具参数已在步骤中保留)。
     * 若需要让 LLM 基于新结果继续,用户应手动重生成。
     */
    fun retryFailedStep(taskCardId: String, stepId: String) =
        taskCardCoordinator.retryFailedStep(taskCardId, stepId)

    // ── Phase 8.2: Assistant 多人格管理 ─────────────────────────────────────

    /**
     * v1.0.54: Agent Tab 切换助手 = 切换该助手的 Agent 对话房间。
     *
     * 设计约束(吸取 8-01 污染教训):
     * - 仅 Agent 模式(isAgentMode=true)有效;任务 Tab 请用 [setSessionAssistant]
     * - 只操作 agentSessionId,绝不碰 currentSessionId(任务会话完全隔离)
     * - 不走 switchSession(绕开其缓存 put/get 与 currentSessionId 更新),
     *   直接 loadMessagesPaged 查 DB,天然避免缓存键污染/空缓存自命中
     * - 切换前停止全部后台生成(旧会话的 Agent Loop 不得继续跑)
     */
    fun switchAgentAssistant(assistantId: String) {
        if (!_state.value.isAgentMode) return
        val targetId = assistantId.ifBlank { "default" }
        // 停止所有后台生成 + 脱离 UI 流式 + 停 TTS/ASR,与 switchSession 一致
        chatGenerationManager.stop()
        runCatching { io.zer0.muse.schedule.ChatGenerationService.stop(appContext) }
        if (_state.value.isStreaming) detachStreaming()
        stopTts()
        disposeAsr()
        notifySessionEndForCurrent()
        viewModelScope.launch {
            // 取 2 条并排除当前会话:命中历史房间则恢复,否则新建
            val recent = sessionRepository.getRecentAgentByAssistant(targetId, 2)
            val current = _state.value.agentSessionId
            val target = recent.firstOrNull { it.id != current }
            val sessionId = target?.id ?: sessionRepository.createAgentSession(assistantId = targetId)
            if (target != null) {
                io.zer0.common.Logger.i("ChatVM", "switchAgentAssistant: 命中历史房间 ${target.id}(assistant=$targetId)")
            } else {
                io.zer0.common.Logger.i("ChatVM", "switchAgentAssistant: 新建房间 $sessionId(assistant=$targetId)")
            }
            // v1.0.54: 同步"默认 Agent 助手"偏好 — 退出再进入 Agent Tab 时保持该助手
            //   (setAgentMode(true) 按此偏好恢复房间),设置页入口看到的值也一致
            runCatching {
                val cfg = settings.proactiveMessageConfigFlow.first()
                if (cfg.agentId != targetId) {
                    settings.saveProactiveMessageConfig(cfg.copy(agentId = targetId))
                }
            }
            // 切换会话引用计数(与 setAgentMode 一致)
            _state.value.agentSessionId?.let { sessionManager.release(it) }
            sessionManager.acquire(sessionId)
            // 预加载消息(直接查 DB,不读缓存),一次性更新状态
            val (messages, hasMore) = loadMessagesPaged(sessionId)
            val permissionMode = sessionPermissionStore.getMode(
                sessionId,
                settings.defaultSessionPermissionModeFlow.first(),
            )
            val assistant = assistantRepository.getById(targetId)
                ?: assistantRepository.getById("default")
            _messages.value = messages
            _state.update {
                it.copy(
                    agentSessionId = sessionId,
                    currentAssistant = assistant,
                    errors = emptyList(),
                    hasMoreHistory = hasMore,
                    isLoadingMore = false,
                    lastHistoryLoadCount = 0,
                    isStreaming = false,
                    // v1.0.72 fix: 切换助手清空引用(防跨会话污染)
                    replyingTo = null,
                    replyQuoteOverride = null,
                    taskCards = emptyMap(),
                    toolCallHistory = emptyList(),
                    agentPlans = emptyMap(),
                    visionAssistedMessageIds = emptySet(),
                    visionProgress = null,
                    sessionPermissionMode = permissionMode,
                    listFirstVisibleItemIndex = messages.lastIndex.coerceAtLeast(0),
                    listFirstVisibleItemScrollOffset = 0,
                )
            }
            refreshContextInfo()
        }
    }

    /**
     * 切换当前会话绑定的 Assistant。
     * - 持久化到 sessions.assistantId
     * - 更新 currentAssistant 状态(影响下一次 launchStream)
     * - 不重新发消息(避免误触发);用户可手动重生成
     */
    fun setSessionAssistant(assistantId: String) {
        val sessionId = _state.value.currentSessionId ?: return
        viewModelScope.launch {
            sessionRepository.setSessionAssistant(sessionId, assistantId)
            val assistant = assistantRepository.getById(assistantId)
                ?: assistantRepository.getById("default")
            _state.update { it.copy(currentAssistant = assistant) }
        }
    }

    /** 新增 Assistant。返回新 id。 */
    fun createAssistant(name: String, callback: (String) -> Unit = {}) {
        viewModelScope.launch {
            val id = "assistant-" + System.currentTimeMillis()
            val now = System.currentTimeMillis()
            assistantRepository.upsert(
                AssistantEntity(
                    id = id,
                    name = name.ifBlank { "新助手" },
                    avatarEmoji = "",
                    // v1.0.51: 新建助手预填默认 prompt(从 assets 加载,带 locale 回落)
                    systemPrompt = assistantRepository.loadDefaultPrompt(),
                    createdAt = now,
                    updatedAt = now,
                )
            )
            callback(id)
        }
    }

    /** 更新 Assistant 字段(任意 subset)。 */
    fun saveAssistant(assistant: AssistantEntity) {
        val now = System.currentTimeMillis()
        val saved = assistant.copy(updatedAt = now)
        viewModelScope.launch {
            resultOf { assistantRepository.upsert(saved) }
                .onSuccess {
                    // Phase 8.5 修复: 用含新 updatedAt 的 saved 刷新 currentAssistant,避免 UI 拿到旧对象
                    if (_state.value.currentAssistant?.id == assistant.id) {
                        _state.update { it.copy(currentAssistant = saved) }
                    }
                }
                .onError { msg, t ->
                    Logger.e("ChatVM", "saveAssistant failed", t)
                    reportError(appContext.getString(R.string.err_chat_assistant_save_failed, msg))
                }
        }
    }

    /** 删除 Assistant(不允许删 default,不允许删当前绑定的)。 */
    fun deleteAssistant(id: String) {
        if (id == "default") return
        if (_state.value.currentAssistant?.id == id) return
        viewModelScope.launch {
            resultOf { assistantRepository.delete(id) }
                .onError { msg, t ->
                    Logger.e("ChatVM", "deleteAssistant failed", t)
                    reportError(appContext.getString(R.string.err_chat_assistant_delete_failed, msg))
                }
        }
    }

    /**
     * Phase 8.3: 切换消息收藏状态。
     * - 同步 DB(乐观先改 UI 列表,DB 失败回滚)
     * - 同时更新 messages(当前会话)与 favoriteMessages(跨会话列表)两个 state 字段
     *
     * Phase 8.5 修复: 原实现只在 `messages`(当前会话)里找 target,跨会话取消收藏
     * (FavoritesScreen 点掉非当前会话的收藏)会因 target==null 直接 return,完全失效。
     * 改为:先查 currentSession messages,找不到再查 favoriteMessages(跨会话收藏列表)。
     */
    fun toggleFavorite(messageId: Uuid) = miscCoordinator.toggleFavorite(messageId)

    /** 功能1: 设置消息表情回应(null = 取消)。 */
    fun setReaction(messageId: Uuid, reaction: String?) {
        val messageIdStr = messageId.toString()
        viewModelScope.launch {
            sessionRepository.setMessageReaction(messageIdStr, reaction?.takeIf { it.isNotEmpty() })
            // 乐观更新 UI
            _messages.value = _messages.value.map { msg ->
                if (msg.id == messageId) msg.copy(reaction = reaction?.takeIf { it.isNotEmpty() }) else msg
            }
        }
    }

    /**
     * v1.104 U7: 设置收藏分组标签(null = 移到未分组)。
     *
     * 乐观更新 favoriteMessages(立即改 tag,UI 立即响应);
     * favoriteTags 列表由 DAO Flow 自动重新发射,无需手动维护。
     * DB 写失败时回滚 favoriteMessages 中的 tag 字段。
     */
    fun setMessageFavoriteTag(messageId: Uuid, tag: String?) = miscCoordinator.setMessageFavoriteTag(messageId, tag)

    /**
     * v1.104 U7: 设置当前收藏夹的分组筛选条件。
     *
     *  - null = 显示全部
     *  - 非空字符串 = 仅显示该 tag
     *  - [FAVORITE_TAG_UNGROUPED] = 仅显示未分组
     *
     * FavoritesScreen 顶部 FilterChip 点击时调用。
     */
    fun setFavoriteTagFilter(tag: String?) = miscCoordinator.setFavoriteTagFilter(tag)

    /**
     * v2.0: 设置预设分类筛选(全部/灵感/代码/学习/自定义)。
     * 设为 null 表示"全部",回退到 favoriteTagFilter。
     */
    fun setFavoriteGroup(group: String?) = miscCoordinator.setFavoriteGroup(group)

    /**
     * v1.48: 删除单条消息(长按菜单"删除消息")。
     *
     * 乐观更新 UI,失败时回滚并提示。
     */
    fun deleteMessage(messageId: Uuid) = miscCoordinator.deleteMessage(messageId)

    // ── B7-01: 消息多选批量操作 ──────────────────────────────────────────

    fun setSelectionMode(enabled: Boolean) {
        _state.update {
            it.copy(
                selectionMode = enabled,
                selectedMessageIds = if (enabled) it.selectedMessageIds else emptySet(),
            )
        }
    }

    fun toggleMessageSelection(messageId: Uuid) {
        _state.update {
            val id = messageId.toString()
            it.copy(
                selectedMessageIds = if (id in it.selectedMessageIds) {
                    it.selectedMessageIds - id
                } else {
                    it.selectedMessageIds + id
                },
            )
        }
    }

    fun selectAllMessages(ids: Collection<String>) {
        _state.update { it.copy(selectedMessageIds = ids.toSet()) }
    }

    /** B7-03: 滚动到底部时标记当前会话已读。 */
    fun markSessionRead() {
        val sessionId = _state.value.currentSessionId ?: _state.value.agentSessionId ?: return
        // 新会话消息可能尚未加载（_messages 为空），此时也必须按 messageCount 标记已读，
        // 否则 lastReadCount 一直为 0，messageCount - lastReadCount > 0，新会话永远显示“未读 1”。
        viewModelScope.launch {
            val session = _state.value.sessions.find { it.id == sessionId }
            val last = _messages.value.lastOrNull()
            val readCount = session?.messageCount?.coerceAtLeast(_messages.value.size) ?: _messages.value.size
            sessionRepository.updateLastReadMessage(sessionId, last?.id?.toString() ?: "", readCount)
        }
    }

    fun deleteSelectedMessages() {
        val ids = _state.value.selectedMessageIds
        if (ids.isEmpty()) return
        ids.forEach { id ->
            runCatching { Uuid.parse(id) }.getOrNull()?.let { deleteMessage(it) }
        }
        _state.update { it.copy(selectionMode = false, selectedMessageIds = emptySet()) }
    }

    // ── Phase 9.1 (M13): 文件夹分组 CRUD ──────────────────────────────────

    /** 新建文件夹。 */
    fun createFolder(name: String) = miscCoordinator.createFolder(name, ::reportError)

    /** 重命名文件夹。 */
    fun renameFolder(id: String, name: String) = miscCoordinator.renameFolder(id, name, ::reportError)

    /** 删除文件夹(关联会话移到未分组)。 */
    fun deleteFolder(id: String) = miscCoordinator.deleteFolder(id, ::reportError)

    /** 切换文件夹展开/折叠状态。 */
    fun toggleFolderExpanded(id: String, expanded: Boolean) = miscCoordinator.toggleFolderExpanded(id, expanded)

    /** 移动会话到文件夹(folderId=null = 移到未分组)。 */
    fun moveSessionToFolder(sessionId: String, folderId: String?) = miscCoordinator.moveSessionToFolder(sessionId, folderId, ::reportError)

    /** P0-1 修复: 切换会话置顶状态。 */
    fun togglePinned(sessionId: String) = miscCoordinator.togglePinned(sessionId, ::reportError)

    /** B7-05: 持久化置顶会话拖拽后的新顺序。 */
    fun reorderPinnedSessions(ids: List<String>) = miscCoordinator.reorderPinnedSessions(ids, ::reportError)

    /**
     * Phase 8.4: 切换联网搜索开关(InputBar 上的图标按钮)。
     * 仅改 UI 即时反馈 + 持久化到 settings(下次启动恢复)。
     */
    fun toggleWebSearch() {
        val cfg = _state.value.webSearchConfig
        val newCfg = cfg.copy(enabled = !cfg.enabled)
        _state.update {
            it.copy(
                webSearchEnabled = newCfg.enabled,
                webSearchConfig = newCfg,
            )
        }
        viewModelScope.launch {
            // v1.117: 改用 resultOf 避免吞 CancellationException(saveWebSearchConfig 是 suspend)
            resultOf { settings.saveWebSearchConfig(newCfg) }
        }
    }

    /** v0.39: 切换深度思考开关(仅运行时状态,不持久化,下次进入会话恢复助手默认)。 */
    fun toggleDeepThinking() {
        // v1.80 (M-CVM5): 原子更新,基于 lambda 内的 it 取反避免读-改-写竞态
        _state.update { it.copy(deepThinkingEnabled = !it.deepThinkingEnabled) }
    }

    /**
     * v1.0.47 P5-6: 循环深度思考级别(LOW → MEDIUM → HIGH → XHIGH → LOW)。
     *
     * 仅在 [ChatUiState.deepThinkingEnabled]=true 时由 UI 级别胶囊调用。
     * 不含 OFF/OFF 由 toggleDeepThinking 负责;此处只调档位。
     */
    fun cycleDeepThinkingLevel() {
        _state.update { state ->
            val next = when (state.deepThinkingLevel) {
                ReasoningLevel.LOW -> ReasoningLevel.MEDIUM
                ReasoningLevel.MEDIUM -> ReasoningLevel.HIGH
                ReasoningLevel.HIGH -> ReasoningLevel.XHIGH
                ReasoningLevel.XHIGH -> ReasoningLevel.LOW
                else -> ReasoningLevel.HIGH // 兜底:OFF/AUTO 回到默认 HIGH
            }
            state.copy(deepThinkingLevel = next)
        }
    }

    /** v1.43: 选中产物卡片,打开 ArtifactViewerDialog。 */
    fun selectArtifact(artifact: io.zer0.muse.data.artifact.ArtifactEntity) {
        // v1.80 (M-CVM5): 原子更新
        _state.update { it.copy(selectedArtifact = artifact) }
    }

    /** v1.43: 关闭产物卡片查看弹窗。 */
    fun dismissArtifactViewer() {
        // v1.80 (M-CVM5): 原子更新
        _state.update { it.copy(selectedArtifact = null) }
    }

    /** v1.43: 观察某条消息关联的产物卡片列表。 */
    fun observeArtifactsByMessage(messageId: String): kotlinx.coroutines.flow.Flow<List<io.zer0.muse.data.artifact.ArtifactEntity>> {
        return artifactRepository.observeByMessage(messageId)
    }

    /** v1.45: 缓存列表滚动位置,切页/后台后恢复。 */
    fun onListScrollPositionChanged(index: Int, offset: Int) {
        _state.update {
            it.copy(
                listFirstVisibleItemIndex = index,
                listFirstVisibleItemScrollOffset = offset,
            )
        }
    }

    /** v1.45: 切换指定消息 mood 块的展开/折叠状态。 */
    fun toggleMessageMoodExpanded(messageId: String) {
        _state.update { current ->
            val currentState = current.messageExpandedStates[messageId] ?: MessageExpandedState()
            val default = current.chatPreferences.moodExpandedByDefault
            val newExpanded = !(currentState.isMoodExpanded ?: default)
            current.copy(
                messageExpandedStates = current.messageExpandedStates +
                    (messageId to currentState.copy(isMoodExpanded = newExpanded)),
            )
        }
    }

    /** v1.45: 切换指定消息 reasoning 块的展开/折叠状态。 */
    fun toggleMessageReasoningExpanded(messageId: String) {
        _state.update { current ->
            val currentState = current.messageExpandedStates[messageId] ?: MessageExpandedState()
            val default = current.chatPreferences.reasoningExpandedByDefault
            val newExpanded = !(currentState.isReasoningExpanded ?: default)
            current.copy(
                messageExpandedStates = current.messageExpandedStates +
                    (messageId to currentState.copy(isReasoningExpanded = newExpanded)),
            )
        }
    }

    /** v1.64: 切换指定消息 reflection 块的展开/折叠状态。 */
    fun toggleMessageReflectionExpanded(messageId: String) {
        _state.update { current ->
            val currentState = current.messageExpandedStates[messageId] ?: MessageExpandedState()
            val default = current.chatPreferences.reflectionExpandedByDefault
            val newExpanded = !(currentState.isReflectionExpanded ?: default)
            current.copy(
                messageExpandedStates = current.messageExpandedStates +
                    (messageId to currentState.copy(isReflectionExpanded = newExpanded)),
            )
        }
    }

    /**
     * Phase 8.7: 切换消息朗读状态。
     * - 当前正在朗读这条消息 → 停止
     * - 当前正在朗读其他消息或未朗读 → 开始朗读这条
     *
     * @param messageId 消息 id
     * @param content 待朗读的文本(含 Markdown 会被 TtsManager 自动剥离)
     *
     * v1.105: 委托至 [ChatAudioCoordinator.toggleTts]。
     */
    fun toggleTts(messageId: Uuid, content: String) {
        // v1.0.4 (P2): TTS 未就绪时给即时反馈(原仅静默返回 false,用户感觉"点击没反应")
        // 仅当当前消息未在播放时检查(speakingMessageId == messageId 时是停止操作,无需就绪)
        if (_state.value.speakingMessageId != messageId && !ttsManager.isReady.value) {
            MuseToast.show(appContext.getString(R.string.tts_not_ready))
            return
        }
        audioCoordinator.toggleTts(messageId, content, ::reportError)
    }

    /** Phase 8.7: 停止当前朗读(切换会话/退出页面时调用)。v1.105: 委托至 [ChatAudioCoordinator]。 */
    fun stopTts() {
        audioCoordinator.stopTts()
    }

    // ── v1.91: 流式 ASR(v1.105 委托至 ChatAudioCoordinator)─────────────

    /** v1.91: 开始流式录音识别。v1.105: 委托至 [ChatAudioCoordinator]。 */
    fun startStreamingAsr() {
        audioCoordinator.startStreamingAsr()
    }

    /** v1.91: 停止流式录音,等待最后结果。v1.105: 委托至 [ChatAudioCoordinator]。 */
    fun stopStreamingAsr() {
        audioCoordinator.stopStreamingAsr()
    }

    /** v1.91: 取消流式录音(恢复原始输入框文本)。v1.105: 委托至 [ChatAudioCoordinator]。 */
    fun cancelStreamingAsr() {
        audioCoordinator.cancelStreamingAsr()
    }

    /** v1.91: 释放 ASR Controller(会话切换/ViewModel 销毁时)。v1.105: 委托至 [ChatAudioCoordinator]。 */
    fun disposeAsr() {
        audioCoordinator.disposeAsr()
    }

    /** Phase 9.3 (M2): 保存 ASR 配置。v1.105: 委托至 [ChatAudioCoordinator]。 */
    fun saveAsrConfig(config: io.zer0.muse.asr.AsrConfig) {
        audioCoordinator.saveAsrConfig(config)
    }

    /**
     * Phase 9.3 (M2): 判断当前是否应走 API 录音路径(而非系统 Intent)。
     * v1.105: 委托至 [ChatAudioCoordinator]。
     */
    fun shouldUseApiRecording(): Boolean {
        return audioCoordinator.shouldUseApiRecording()
    }

    // ── 语音对话模式(完整 ASR + AI + TTS 循环)──────────────────────────────

    /**
     * 进入语音对话模式:开始首轮 LISTENING 并启动状态机循环观察。
     *
     * 前置条件:
     *  - RECORD_AUDIO 权限已授予(UI 层负责检查/申请)
     *  - ASR API 已配置([shouldUseApiRecording] 返回 true),否则报错退出
     *
     * 状态流转:IDLE → LISTENING(启动 ASR)→ 等待用户说话 → 自动发送 → THINKING →
     * SPEAKING(TTS 朗读)→ LISTENING(连续对话)→ ...
     */
    fun startVoiceConversation() {
        if (_voiceConversationState.value != VoiceConversationState.IDLE) return
        if (!shouldUseApiRecording()) {
            addError(ChatErrorType.UNKNOWN, appContext.getString(R.string.err_chat_voice_no_asr))
            return
        }
        // 取消旧循环协程(可能保留 stale wasRecording/wasStreaming/wasSpeaking 标志),重启确保状态干净
        voiceConversationJob?.cancel()
        _voiceConversationState.value = VoiceConversationState.LISTENING
        startListeningForVoiceConversation()
        observeVoiceConversationLoop()
    }

    /**
     * 退出语音对话模式:停止 ASR/TTS,取消循环观察协程,状态归零。
     *
     * 调用时机:用户点击关闭按钮(X)、页面退出、ViewModel 销毁。
     */
    fun stopVoiceConversation() {
        voiceConversationJob?.cancel()
        voiceConversationJob = null
        audioCoordinator.stopVoiceConversationListening()
        ttsManager.stop()
        _voiceConversationState.value = VoiceConversationState.IDLE
        _voiceConversationTranscript.value = ""
        _voiceConversationAiReply.value = ""
    }

    /**
     * 中断当前语音对话状态(用户点击主按钮)。
     *
     * 行为:
     *  - LISTENING:停止 ASR,丢弃当前转写
     *  - THINKING:停止 ASR + 停止 AI 生成
     *  - SPEAKING:停止 TTS 朗读
     *  - IDLE:无操作
     *
     * 中断后状态回到 IDLE,等待用户再次点击主按钮开始新一轮对话。
     */
    fun interruptVoiceConversation() {
        val current = _voiceConversationState.value
        if (current == VoiceConversationState.IDLE) return
        audioCoordinator.stopVoiceConversationListening()
        ttsManager.stop()
        // THINKING 状态下 AI 仍在生成,需停止生成避免后续 isStreaming 回调误触发 TTS
        if (current == VoiceConversationState.THINKING && _state.value.isStreaming) {
            stop()
        }
        _voiceConversationState.value = VoiceConversationState.IDLE
        _voiceConversationTranscript.value = ""
        _voiceConversationAiReply.value = ""
    }

    /**
     * 启动一轮 ASR 录音,识别文本通过回调写入 [_voiceConversationTranscript]。
     *
     * 注意:不写入输入框字段(语音对话模式不走 InputBar 文本流),
     * 与 [io.zer0.muse.ui.chat.ChatAudioCoordinator.startStreamingAsr] 区分。
     */
    private fun startListeningForVoiceConversation() {
        _voiceConversationTranscript.value = ""
        audioCoordinator.startVoiceConversationListening { transcript ->
            _voiceConversationTranscript.value = transcript
        }
    }

    /**
     * 启动状态机循环观察协程:监听 ASR/流式/TTS 状态切换,自动驱动状态机循环。
     *
     * 三个观察点(单一 collect,内部分支处理):
     *  1. LISTENING 状态下 ASR isRecording 由 true → false:录音结束,取 transcript 自动发送 → THINKING
     *  2. THINKING 状态下 isStreaming 由 true → false:AI 回复完成,取最后一条 assistant 消息调 TTS → SPEAKING
     *  3. SPEAKING 状态下 isSpeaking 由 true → false:TTS 朗读完成,恢复录音 → LISTENING(连续对话)
     *
     * 协程在 [stopVoiceConversation] / [interruptVoiceConversation] 时被 cancel。
     */
    private fun observeVoiceConversationLoop() {
        voiceConversationJob = viewModelScope.launch {
            var wasRecording = false
            var wasStreaming = false
            var wasSpeaking = false
            _state.collect { state ->
                // 1. LISTENING → THINKING:ASR 录音结束,取 transcript 自动发送
                if (_voiceConversationState.value == VoiceConversationState.LISTENING) {
                    if (state.asrState.isRecording) {
                        wasRecording = true
                    } else if (wasRecording) {
                        wasRecording = false
                        val text = _voiceConversationTranscript.value.trim()
                        if (text.isNotEmpty()) {
                            _voiceConversationState.value = VoiceConversationState.THINKING
                            updateInput(text)
                            send()
                        } else {
                            // 未识别到内容,回 IDLE 等待用户再次点击
                            _voiceConversationState.value = VoiceConversationState.IDLE
                        }
                    }
                }
                // 2. THINKING → SPEAKING:AI 流式回复完成,自动朗读
                if (_voiceConversationState.value == VoiceConversationState.THINKING) {
                    if (state.isStreaming) {
                        wasStreaming = true
                    } else if (wasStreaming) {
                        wasStreaming = false
                        val lastAssistant = _messages.value.lastOrNull { it.role == MessageRole.ASSISTANT }
                        val content = lastAssistant?.content?.takeIf { it.isNotBlank() }
                        if (content != null) {
                            _voiceConversationAiReply.value = content
                            _voiceConversationState.value = VoiceConversationState.SPEAKING
                            // TTS 播放时 ASR 已停止(本循环不会在 SPEAKING 状态启动 ASR),避免回声
                            ttsManager.speak(content, lastAssistant.id.toString())
                        } else {
                            _voiceConversationState.value = VoiceConversationState.IDLE
                        }
                    }
                }
                // 3. SPEAKING → LISTENING:TTS 朗读完成,恢复录音(连续对话)
                if (_voiceConversationState.value == VoiceConversationState.SPEAKING) {
                    if (state.isSpeaking) {
                        wasSpeaking = true
                    } else if (wasSpeaking) {
                        wasSpeaking = false
                        _voiceConversationAiReply.value = ""
                        _voiceConversationState.value = VoiceConversationState.LISTENING
                        startListeningForVoiceConversation()
                    }
                }
            }
        }
    }

    /** 查询系统 TTS 可用声音列表(切换语音 Bottom Sheet 用)。 */
    fun getAvailableTtsVoices(): List<android.speech.tts.Voice> = ttsManager.getAvailableVoices()

    /** 当前生效的 TTS 声音名称(用于切换语音 Sheet 标记选中项)。 */
    fun currentTtsVoiceName(): String = _state.value.mediaConfig.ttsVoiceName

    /**
     * 切换 TTS 声音:立即应用到 TtsManager,并持久化到 Settings(下次启动仍生效)。
     *
     * @param voiceName 系统 TTS Voice 的 name(来自 [getAvailableTtsVoices])
     */
    fun setTtsVoice(voiceName: String) {
        val currentConfig = _state.value.mediaConfig
        val newConfig = currentConfig.copy(ttsVoiceName = voiceName)
        ttsManager.applyConfig(newConfig)
        viewModelScope.launch {
            resultOf { settings.saveMediaConfig(newConfig) }
        }
    }

    /**
     * Phase 8.4: 保存 Web 搜索配置(Settings 页编辑后调用)。
     * Phase 8.5 修复:同步调用 CompositeWebSearchService.updateConfig 使运行时切换立即生效。
     */
    fun saveWebSearchConfig(config: WebSearchConfig) {
        viewModelScope.launch {
            // v1.117: 改用 resultOf 避免吞 CancellationException(saveWebSearchConfig 是 suspend)
            resultOf { settings.saveWebSearchConfig(config) }
            // 同步刷新运行时 service,避免下次 search 仍用启动时旧 config
            (webSearchService as? io.zer0.muse.web.CompositeWebSearchService)?.updateConfig(config)
        }
    }

    // ── Phase 8.5: QuickMessages / Lorebook / PromptInjection ────────────

    /**
     * Phase 8.5: 切换当前模式(用于 PromptInjection)。
     * "default" 表示无注入;其他模式触发对应 PromptInjection 条目。
     */
    fun setMode(mode: String) {
        // v1.80 (M-CVM5): 原子更新
        _state.update { it.copy(currentMode = mode) }
    }

    /** Phase 8.5: 取当前可用的模式列表(预置 + 数据库中已有的)。 */
    fun getAvailableModes(): List<Pair<String, String>> = promptInjectionRepository.presetModes

    /**
     * Phase 8.5: 插入快捷消息到输入框。
     * 支持 {{input}} / {{clipboard}} / {{date}} 模板变量替换。
     * 若模板包含 {{input}},替换为当前输入框内容;否则追加到输入框末尾。
     */
    fun insertQuickMessage(quickMessage: QuickMessageEntity, clipboardText: String = "") {
        val currentInput = _state.value.input
        val rendered = quickMessageRepository.renderTemplate(
            template = quickMessage.content,
            currentInput = currentInput,
            clipboard = clipboardText,
        )
        // 若模板原文包含 {{input}},rendered 已替换;否则把渲染结果作为新输入
        val newInput = if (quickMessage.content.contains("{{input}}")) {
            rendered
        } else if (currentInput.isBlank()) {
            rendered
        } else {
            "$currentInput\n\n$rendered"
        }
        // v1.80 (M-CVM5): 原子更新
        _state.update { it.copy(input = newInput) }
    }

    /** v1.58: 插入 Prompt 模板内容到输入框(若输入框已有内容则追加)。 */
    fun insertPromptTemplate(template: io.zer0.muse.data.prompttemplate.PromptTemplate) {
        val currentInput = _state.value.input
        val newInput = if (currentInput.isBlank()) {
            template.content
        } else {
            "$currentInput\n\n${template.content}"
        }
        // v1.80 (M-CVM5): 原子更新
        _state.update { it.copy(input = newInput) }
    }

    /** Phase 8.5: 解析 Assistant.quickMessageIdsJson / lorebookIdsJson / modeInjectionIdsJson。 */
    private fun parseIdList(json: String): List<String> =
        runCatching { idListJson.decodeFromString<List<String>>(json) }
            .getOrDefault(emptyList())

    // ── Phase 8.5: Lorebook / PromptInjection / QuickMessage CRUD(管理页用) ──

    /**
     * v1.97 (P1-1): 懒加载全部 Lorebook 条目(管理页进入时调用)。
     * 替代原 init 中的常驻 Flow 收集器,减少聊天主流程的无谓 DB 观察开销。
     */
    fun refreshLorebooks() = miscCoordinator.refreshLorebooks()

    /** Lorebook: 新增或更新。 */
    fun saveLorebook(entity: LorebookEntity) = miscCoordinator.saveLorebook(entity)

    /** Lorebook: 删除。 */
    fun deleteLorebook(id: String) = miscCoordinator.deleteLorebook(id)

    /**
     * v1.97 (P1-1): 懒加载全部 PromptInjection 条目(管理页进入时调用)。
     */
    fun refreshPromptInjections() = miscCoordinator.refreshPromptInjections()

    /** PromptInjection: 新增或更新。 */
    fun savePromptInjection(entity: PromptInjectionEntity) = miscCoordinator.savePromptInjection(entity)

    /** PromptInjection: 删除。 */
    fun deletePromptInjection(id: String) = miscCoordinator.deletePromptInjection(id)

    /**
     * v1.97 (P1-1): 懒加载全部 QuickMessage 条目(管理页进入时调用)。
     */
    fun refreshAllQuickMessages() = miscCoordinator.refreshAllQuickMessages()

    /** QuickMessage: 新增或更新。 */
    fun saveQuickMessage(entity: QuickMessageEntity) = miscCoordinator.saveQuickMessage(entity)

    /** QuickMessage: 删除。 */
    fun deleteQuickMessage(id: String) = miscCoordinator.deleteQuickMessage(id)
}
