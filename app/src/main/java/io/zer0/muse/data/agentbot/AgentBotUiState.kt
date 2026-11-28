package io.zer0.muse.data.agentbot

import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/**
 * 中枢页面向用户显示的错误。
 *
 * ## 为什么是枚举而不是字符串
 *
 * 状态层不持有 Context，直接拼中文会让 7 语言下漏翻（硬编码中文门禁也会拦）。
 * 这里只表达"发生了什么"，文案由 Compose 侧用 stringResource 解析 ——
 * 同一个状态在任何语言下都有对应的话。
 */
enum class AgentBotError {
    /** 还没有可用的会话（宿主没绑定）。 */
    NO_SESSION,

    /**
     * 会话绑定存在但读不出来（数据库/磁盘读失败）。
     *
     * 与 [NO_SESSION] 严格分开：那个是"确实还没绑过"，可以新建；
     * 这个是"读不出来" —— 此刻新建会话会把之前的 canonical 会话从绑定里挤掉，
     * 用户的历史看起来就像"消失"了。必须中止本次发送并让用户重试。
     */
    BINDING_UNREADABLE,

    /** 消息落盘失败。内容已留在输入框。 */
    SAVE_FAILED,

    /**
     * 服务端限流（HTTP 429 / rate_limit_exceeded）。
     *
     * 与 [SAVE_FAILED] 严格分开：限流时消息其实已经落盘，只是这一轮没有回复。
     * 以前一律报「消息没能保存，请重试」，用户会反复重发同一句话，
     * 既解决不了限流，也把会话刷成一堆重复消息。
     */
    RATE_LIMITED,

    /** 模型侧返回了与落盘无关的失败（鉴权、服务端错误、网络等）。 */
    PROVIDER_FAILED,

    /** 这一步已经被结算过（双击 / 并发）。 */
    ALREADY_DECIDED,

    /** 审批已超时。**不是**用户拒绝。 */
    APPROVAL_EXPIRED,

    /** 审批参数或资源已变更，旧批准失效。 */
    APPROVAL_STALE,

    /**
     * 模型没有按约定调用 bot_say，回复没能发出。
     *
     * 这不是"模型答得不好"，是**这一轮没有产生任何对外内容**。
     * 必须如实告诉用户并给重试入口，不能拿模型正文兜底。
     */
    MISSING_BOT_SAY,

    /**
     * 当前 Provider 很可能不支持工具调用。
     *
     * 判据：两次调用都返回纯文本、都没有 tool_calls。
     *
     * 必须明确告知：不告知的话，用户只会看到主 Bot 聊得很正常但从不做事，
     * 而原因（换一个 Provider 就能好）完全无从得知。
     */
    PROVIDER_TOOL_BLIND,

    ATTACHMENT_FAILED,

    ATTACHMENT_LIMIT,

    HISTORY_LOAD_FAILED,
}

/**
 * 任务行上"需要用户做什么"的提示。
 *
 * 与 [AgentBotError] 同理由：状态层只给语义，文案在 UI 侧解析。
 */
enum class AgentBotPrompt {
    /**
     * 审批已通过，等用户确认是否重跑整件事。
     *
     * 与 [NEEDS_DECISION] 分开：那个是"执行出问题了，你看怎么办"，
     * 这个是"可以继续了，但要先告诉你重跑会重复已完成的步骤"。
     * 混成一句会让用户不知道该点"继续"还是该重试。
     */
    APPROVED_AWAITING_RETRY,

    NEEDS_DECISION,

    /** 结果无法确认，请核验。 */
    VERIFY_RESULT,
}

/**
 * Agent 中枢的分片 UI 状态。
 *
 * ## 为什么不用 ChatUiState
 *
 * 现有 ChatUiState 是普通 class（无 equals）、83 个字段、copy() 展开成 123 个参数，
 * MutableStateFlow 因此无法合并 —— 每 12 个字符的流式输出都会重建整个聊天页。
 * 中枢的核心承诺是"做事时还能继续聊天"，若沿用那套状态模型，短回合省下的时间
 * 会被整页重建全部吃掉。
 *
 * 这里按域拆成四个 data class，各自是值语义、各自一个 StateFlow：
 *  - [Composer]：用户正在打的字与待发附件
 *  - [Transcript]：已确认的对外消息（只含已 commit 的）
 *  - [TaskBoard]：在途任务与待审批
 *  - [Busy]：真实发布状态（typing / 发送中 / 失败）
 *
 * 四个域各自订阅，任务进度变化不会让输入框重组。
 */
class AgentBotUiState {

    data class ForegroundMcpApproval(
        val requestId: String,
        val serverId: String,
        val toolName: String,
        val purpose: String,
        val argumentsJson: String,
    )

    /** 输入区。用户敲字只动这里。 */
    data class Composer(
        val text: String = "",
        val pendingImages: List<String> = emptyList(),
        /** 这条输入属于哪个 Bot；切换 Bot 时草稿不能串。 */
        val botId: String = "",
        val quotedMessage: PublishedMessage? = null,
        val editingMessageId: String? = null,
    )

    /** 一条已发布的对外消息。只从 PublicationOutbox 的 COMMITTED 状态来。 */
    data class PublishedMessage(
        val messageId: String,
        val role: Role,
        val text: String,
        val createdAt: Long,
        val seq: Long = 0L,
        val imageUrls: List<String> = emptyList(),
        val imageBase64List: List<String> = emptyList(),
        /** 这条消息属于哪次 bot_say 批次；用于"部分发布恢复"。 */
        val publicationId: String = "",
        val reasoning: String = "",
        val reasoningDurationMs: Long? = null,
        val mood: String = "",
        val citationUrls: List<String> = emptyList(),
    )

    enum class Role { USER, BOT }

    /** 单条气泡上的角标反应（Emoji / 贴纸）。 */
    data class MessageReactionUi(
        val id: String,
        val emoji: String,
        val actorType: String,
        val kind: String = AnnotationKind.REACTION,
    )

    /** 任务单步执行时间线条目。 */
    data class TaskStepUi(
        val stepId: String,
        val stepIndex: Int,
        val toolName: String,
        val displayLabel: String,
        val status: String = StepExecutionStatus.RUNNING,
        val summary: String = "",
        val assigneeBotId: String = "",
    )

    /** 锚定在对话流里的结构化交互卡片。 */
    data class MessageCardUi(
        val cardId: String,
        val anchorMessageId: String,
        val taskId: String,
        val kind: String,
        val title: String,
        val subtitle: String,
        val options: List<AgentStreamCoordinator.ChoiceOption> = emptyList(),
        val status: String = BotCardStatus.ACTIVE,
        val selectedOptionId: String = "",
        val createdAt: Long = 0L,
        val payloadJson: String = "{}",
    )

    /** 正在流式输出的回复草稿；与 Composer/TaskBoard 隔离，避免流式打字引发整页重组。 */
    data class StreamingBubble(
        val active: Boolean = false,
        val text: String = "",
        val reasoning: String = "",
        val reasoningStartedAtMs: Long? = null,
        val reasoningDurationMs: Long? = null,
        val isThinkingActive: Boolean = false,
        val mood: String = "",
        val citationUrls: List<String> = emptyList(),
    )

    /** 任务看板上的一行。只放用户需要知道的部分，不放子 Agent 与工具流水。 */
    data class TaskRow(
        val taskId: String,
        val goal: String,
        val status: String,
        /** 一句话进展（来自审核或关键进度事件），空表示还没有可说的。 */
        val note: String = "",
        /** 需要用户决定时的提示；非空时这一行必须可见。 */
        val needsUser: AgentBotPrompt? = null,
        /**
         * 这一行允许哪些操作。
         *
         * ## 为什么不用 status 推断
         *
         * NEEDS_USER 有两个来源（审批通过 / 审核要决策），该给的操作完全不同：
         * 前者"可以重跑"，后者"要用户处理"。靠 status 猜只能猜对一半 ——
         * 猜错就会在"需要你处理"的行上放一个"继续重跑"按钮，用户点了才发现不对。
         *
         * 所以由构造方（ViewModel，它知道事件来源）显式给出。
         */
        val actions: Set<TaskAction> = emptySet(),
        val requestMessageId: String = "",
        val steps: List<TaskStepUi> = emptyList(),
        val pendingSteerCount: Int = 0,
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L,
    )

    /** 任务行上可用的操作。 */
    enum class TaskAction {
        /** 取消这个任务。 */
        CANCEL,

        /**
         * 用户确认重跑。
         *
         * 只在"审批已通过、任务停住等确认"时出现。重跑会重复已执行的步骤，
         * 所以必须由用户显式同意。
         */
        CONTINUE,

        /** 已失败的任务由用户明确重新派发。 */
        RETRY,

        /** 结果无法确认时打开核验入口。 */
        VERIFY,
    }

    /** 待审批的一行。 */
    data class ApprovalRow(
        val approvalId: String,
        val taskId: String,
        val toolName: String,
        val risk: String,
        /** 全量参数摘要；卡片与执行必须同源。 */
        val argumentsPreview: String,
        /**
         * 建卡时的参数哈希。
         *
         * 批准时必须原样回传 —— 服务端拿它和当前参数比对，不一致判 INVALIDATED。
         * 此前界面传的是空串，于是**每一次批准都被判失效**，
         * 任务永久停在 WAITING_APPROVAL，用户只看到"参数已变更"。
         */
        val argumentsHash: String,
        /**
         * 建卡时的资源版本。
         *
         * 与 [argumentsHash] 同理：资源在这期间变了，旧批准不该生效。
         */
        val resourceVersion: String,
        /** 过期时刻。卡片据此显示倒计时，过期不伪造用户拒绝。 */
        val expiresAt: Long,
        val resourceScope: String = "",
    )

    /** 暂存区里的一条候选（V04 的审计入口）。 */
    data class CandidateRow(
        val stagedId: String,
        /** 它想改的目标。 */
        val targetRef: String,
        /** 产出时对应的任务版本。 */
        val taskRevision: Int,
    )

    data class TaskDetails(
        val taskId: String,
        val goal: String,
        val acceptanceCriteria: String,
        val status: String,
        val summary: String = "",
        val findings: List<String> = emptyList(),
        val verifiedChecks: List<String> = emptyList(),
        val sideEffects: List<String> = emptyList(),
        val candidateRefs: List<String> = emptyList(),
    )

    data class CandidatePreview(
        val stagedId: String,
        val targetRef: String,
        val content: String,
        val verificationState: String,
        val taskRevision: Int,
        val truncated: Boolean,
    )

    data class TaskBoard(
        val tasks: List<TaskRow> = emptyList(),
        val approvals: List<ApprovalRow> = emptyList(),
    )

    /**
     * 真实发布状态。
     *
     * 后台任务独自执行时 [typing] 必须为 false —— 让主对话永久显示"正在输入"
     * 是最容易被用户当成卡死的假象。
     */
    data class Busy(
        val typing: Boolean = false,
        val sending: Boolean = false,
        /** 宿主侧发送错误；非空时界面给重试入口，不用私有文本兜底。 */
        val error: AgentBotError? = null,
    )

    // ── 四个独立流 ────────────────────────────────────────────────────

    private val _composer = MutableStateFlow(Composer())
    val composer: StateFlow<Composer> = _composer.asStateFlow()

    private val _transcript = MutableStateFlow<List<PublishedMessage>>(emptyList())
    val transcript: StateFlow<List<PublishedMessage>> = _transcript.asStateFlow()

    private val _taskBoard = MutableStateFlow(TaskBoard())
    val taskBoard: StateFlow<TaskBoard> = _taskBoard.asStateFlow()

    private val _busy = MutableStateFlow(Busy())
    val busy: StateFlow<Busy> = _busy.asStateFlow()

    private val _streaming = MutableStateFlow(StreamingBubble())
    val streaming: StateFlow<StreamingBubble> = _streaming.asStateFlow()

    private val _foregroundMcpApproval = MutableStateFlow<ForegroundMcpApproval?>(null)
    val foregroundMcpApproval: StateFlow<ForegroundMcpApproval?> = _foregroundMcpApproval.asStateFlow()

    // ── 写入口（全部是值语义，StateFlow 可合并）────────────────────────

    fun setComposerText(text: String) = _composer.update { it.copy(text = text) }

    fun addPendingImage(imageBase64: String): Boolean {
        val current = _composer.value
        if (imageBase64.isBlank() || imageBase64 in current.pendingImages ||
            current.pendingImages.size >= MAX_PENDING_IMAGES
        ) {
            return false
        }
        _composer.value = current.copy(pendingImages = current.pendingImages + imageBase64)
        return true
    }

    fun removePendingImages(images: List<String>) = _composer.update { state ->
        state.copy(pendingImages = state.pendingImages.filterNot { it in images })
    }

    fun replacePendingImages(images: List<String>) = _composer.update { state ->
        state.copy(pendingImages = images.distinct().take(MAX_PENDING_IMAGES))
    }

    fun setComposerBot(botId: String) = _composer.update {
        // 换 Bot 时草稿不串：上一个 Bot 的草稿属于上一个 Bot。
        if (it.botId == botId) {
            it
        } else {
            it.copy(
                botId = botId,
                text = "",
                pendingImages = emptyList(),
                quotedMessage = null,
                editingMessageId = null,
            )
        }
    }

    fun setQuotedMessage(message: PublishedMessage?) = _composer.update {
        it.copy(quotedMessage = message)
    }

    fun setEditingMessage(messageId: String?, text: String) = _composer.update {
        it.copy(editingMessageId = messageId, text = text)
    }

    fun appendPublished(message: PublishedMessage) = _transcript.update { current ->
        // 同 messageId 重复投递只留一条：恢复时可能重投已提交的 item。
        if (current.any { it.messageId == message.messageId }) current else current + message
    }

    fun replacePublished(messages: List<PublishedMessage>) = _transcript.update { messages }

    fun setTaskBoard(board: TaskBoard) = _taskBoard.update { board }
    fun setForegroundMcpApproval(request: ForegroundMcpApproval?) = _foregroundMcpApproval.update { request }

    fun setTyping(typing: Boolean) = _busy.update { it.copy(typing = typing) }

    fun setSending(sending: Boolean) = _busy.update { it.copy(sending = sending) }

    fun setError(error: AgentBotError?) = _busy.update { it.copy(error = error) }

    fun setStreaming(bubble: StreamingBubble) {
        _streaming.value = bubble
    }

    fun clearStreaming() {
        _streaming.value = StreamingBubble()
    }

    companion object {
        const val MAX_PENDING_IMAGES = 10
    }
}

/**
 * 当前页面的完整投影。
 *
 * ## 为什么分成三段任务
 *
 * 用户打开页面要回答三个问题：在做什么（[inProgress]）、等我做什么
 * （[needsAction]，包括需要核验的终态）、最近做完了什么（[recent]）。混成一个列表时，
 * 待办会被历史完成项挤到下面 —— 那正是用户最容易漏掉审批的形态。
 *
 * [assistantId] 与 [sessionId] 是这一份投影的作用域键：界面用它做列表 key
 * 前缀，切换 Bot 时旧内容不会与新内容共用 key。
 */
data class AgentBotProjection(
    val assistantId: String,
    val sessionId: String,
    val inProgress: List<AgentBotUiState.TaskRow> = emptyList(),
    val needsAction: List<AgentBotUiState.TaskRow> = emptyList(),
    val recent: List<AgentBotUiState.TaskRow> = emptyList(),
    val pendingApprovals: List<AgentBotUiState.ApprovalRow> = emptyList(),
    val candidates: List<AgentBotUiState.CandidateRow> = emptyList(),
    val transcript: List<AgentBotUiState.PublishedMessage> = emptyList(),
    val annotationsByMessage: Map<String, List<AgentBotUiState.MessageReactionUi>> = emptyMap(),
    val cards: List<AgentBotUiState.MessageCardUi> = emptyList(),
    val stepsByTask: Map<String, List<AgentBotUiState.TaskStepUi>> = emptyMap(),
) {

    /** 三段时间顺序：在做什么 → 等我做什么 → 最近做完的。 */
    val taskRows: List<AgentBotUiState.TaskRow> get() = inProgress + needsAction + recent

    /** 没有任何可显示的东西。用来区分"空"与"还在加载"。 */
    val isEmpty: Boolean
        get() = taskRows.isEmpty() &&
            pendingApprovals.isEmpty() &&
            candidates.isEmpty() &&
            transcript.isEmpty() &&
            cards.isEmpty()
}

/**
 * 页面数据四态。
 *
 * [Error] 必须带 [lastContent]：一次读取失败不能让已经显示的任务凭空消失，
 * 否则用户会以为任务被删了。[retryToken] 每次重试递增，界面据此判断
 * "这是新一轮失败"，而不是同一条错误重新渲染。
 */
sealed interface AgentBotScopeState {
    data object Loading : AgentBotScopeState

    data object Empty : AgentBotScopeState

    data class Content(val projection: AgentBotProjection) : AgentBotScopeState

    data class Error(
        val message: String,
        val retryToken: Long,
        val lastContent: AgentBotProjection? = null,
    ) : AgentBotScopeState
}

/**
 * 暂存候选读取失败。
 *
 * 用它把故障送进 [AgentBotScopeState.Error]，而不是让上游发一个空候选列表：
 * 空列表会被折叠成 [AgentBotScopeState.Empty]，用户看到的是"候选没了"，
 * 也没有任何重试入口 —— 而文件其实还在磁盘上。
 */
class StagedCandidatesUnavailableException(
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)

/**
 * 把投影流折叠成四态。
 *
 * 读失败**不降级成空列表**：空列表与"这个会话真的没有任务"无法区分，
 * 用户看到的是"刚才的任务没了"，也没有重试入口。这里保留最后一次成功内容，
 * 把异常作为 [AgentBotScopeState.Error] 显式交出去。
 *
 * @param retryToken 本次尝试的 token，由重试入口递增。
 * @param previous 重试开始时已知的上一次成功投影；跨重试也要保住内容。
 */
fun Flow<AgentBotProjection>.asScopeStates(retryToken: Long, previous: AgentBotProjection? = null): Flow<AgentBotScopeState> = flow {
    emit(AgentBotScopeState.Loading)
    var lastContent = previous
    try {
        collect { projection ->
            lastContent = projection
            emit(
                if (projection.isEmpty) {
                    AgentBotScopeState.Empty
                } else {
                    AgentBotScopeState.Content(projection)
                },
            )
        }
    } catch (cause: CancellationException) {
        // 作用域切换 / 页面销毁是正常取消，不能当成读取失败上报。
        throw cause
    } catch (cause: java.io.IOException) {
        Logger.e("AgentBotScope", "页面投影读取失败，保留上次内容: ${cause.message}", cause)
        emit(
            AgentBotScopeState.Error(
                message = cause.message ?: "scope_read_failed",
                retryToken = retryToken,
                lastContent = lastContent,
            ),
        )
    }
}
