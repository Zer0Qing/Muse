package io.zer0.muse.ui.agentbot

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.agentbot.AgentBotProjection
import io.zer0.muse.data.agentbot.AgentBotRepository
import io.zer0.muse.data.agentbot.AgentCapabilityPromptBuilder
import io.zer0.muse.data.agentbot.AgentModelRouter
import io.zer0.muse.data.agentbot.AgentRoutineEngine
import io.zer0.muse.data.agentbot.AgentSquadIdentityStore
import io.zer0.muse.data.agentbot.AgentStreamCoordinator
import io.zer0.muse.data.agentbot.AgentTeachRecorder
import io.zer0.muse.data.agentbot.AnnotationKind
import io.zer0.muse.data.agentbot.BotAnnotationService
import io.zer0.muse.data.agentbot.BotCardKind
import io.zer0.muse.data.agentbot.BotCardStatus
import io.zer0.muse.data.agentbot.BotMessageCardEntity
import io.zer0.muse.data.agentbot.BotRoutineEntity
import io.zer0.muse.data.agentbot.LiveMissionProgressBus
import io.zer0.muse.data.agentbot.RoutineTriggerType
import io.zer0.muse.data.agentbot.TaskCreationInput
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.zer0.muse.data.agentbot.AgentBotError
import io.zer0.muse.data.agentbot.AgentBotScope
import io.zer0.muse.data.agentbot.AgentBotScopeState
import io.zer0.muse.data.agentbot.ApprovalRequestEntity
import io.zer0.muse.data.agentbot.ApprovalStatus
import io.zer0.muse.data.agentbot.AgentBotPrompt
import io.zer0.muse.data.agentbot.AgentBotUiState
import io.zer0.muse.data.agentbot.AgentConversationFacade
import io.zer0.muse.data.agentbot.ApprovalService
import io.zer0.muse.data.agentbot.BotEventKind
import io.zer0.muse.data.agentbot.BotResultApplier
import io.zer0.muse.data.agentbot.BotInboxProcessor
import io.zer0.muse.data.agentbot.BotTaskEntity
import io.zer0.muse.data.agentbot.BotTaskScheduler
import io.zer0.muse.data.agentbot.BotTaskStatus
import io.zer0.muse.data.agentbot.BotTaskStatusMachine
import io.zer0.muse.data.agentbot.StagedArtifactStore
import io.zer0.muse.data.agentbot.StagedCandidatesUnavailableException
import io.zer0.muse.data.agentbot.TranscriptRestorer
import io.zer0.muse.data.agentbot.asScopeStates
import io.zer0.muse.data.agentbot.ConversationToolAllowanceStore
import io.zer0.muse.data.agentbot.ForegroundCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import io.zer0.muse.data.agentbot.PublicationService
import io.zer0.muse.data.agentbot.PublicationKind
import io.zer0.muse.data.agentbot.ReviewDecision
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.zer0.muse.ui.chat.ChatViewModel
import io.zer0.muse.ui.chat.readImageContentBase64

/**
 * Agent 中枢页的 ViewModel。
 *
 * ## 它不做什么
 *
 * 不持有生成状态、不调模型、不管流式。那些留在现有 ChatViewModel 里。
 * 这里只做"把用户输入接进来、把任务看板映到界面上、把审批操作转成结算"。
 *
 * ## 为什么状态是分片的
 *
 * [AgentBotUiState] 的四个域各自一个 StateFlow。任务进度变化不会让输入框重组 ——
 * 这是中枢"做事时还能聊天"的性能前提（见 BLK-8 的取舍记录）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentBotViewModel(
    private val repository: AgentBotRepository,
    private val facade: AgentConversationFacade,
    private val inbox: BotInboxProcessor,
    private val scheduler: BotTaskScheduler,
    private val publication: PublicationService,
    private val approvals: ApprovalService,
    private val allowances: ConversationToolAllowanceStore,
    private val coordinator: ForegroundCoordinator,
    private val sessions: io.zer0.muse.data.session.SessionRepository,
    private val resultApplier: BotResultApplier,
    private val restorer: TranscriptRestorer,
    private val results: io.zer0.muse.data.agentbot.AgentBotResultPublisher,
    private val streamCoordinator: AgentStreamCoordinator? = null,
    private val promptBuilder: AgentCapabilityPromptBuilder? = null,
    private val annotationService: BotAnnotationService? = null,
    private val progressBus: LiveMissionProgressBus? = null,
    private val modelRouter: AgentModelRouter? = null,
    private val configStore: io.zer0.ai.ProviderConfigStore? = null,
    private val routineEngine: AgentRoutineEngine? = null,
    private val squadIdentities: AgentSquadIdentityStore? = null,
    private val teachRecorder: AgentTeachRecorder? = null,
    private val assistantRepository: io.zer0.muse.data.assistant.AssistantRepository? = null,
    private val channelManager: io.zer0.muse.channel.ChannelManager? = null,
    private val artifactRepository: io.zer0.muse.data.artifact.ArtifactRepository? = null,
    private val settingsRepository: io.zer0.muse.data.SettingsRepository? = null,
    private val knowledgeDocDao: io.zer0.muse.data.knowledge.KnowledgeDocDao? = null,
) : ViewModel() {

    private val ui = AgentBotUiState()

    val composer: StateFlow<AgentBotUiState.Composer> = ui.composer
    val transcript: StateFlow<List<AgentBotUiState.PublishedMessage>> = ui.transcript
    val taskBoard: StateFlow<AgentBotUiState.TaskBoard> = ui.taskBoard
    val busy: StateFlow<AgentBotUiState.Busy> = ui.busy
    val streaming: StateFlow<AgentBotUiState.StreamingBubble> = ui.streaming

    val liveSteps: StateFlow<Map<String, List<LiveMissionProgressBus.LiveStep>>> =
        progressBus?.liveSteps ?: MutableStateFlow(emptyMap())
    val sandboxLogs: StateFlow<List<LiveMissionProgressBus.SandboxLogLine>> =
        progressBus?.sandboxLogs ?: MutableStateFlow(emptyList())
    val pendingSteerCounts: StateFlow<Map<String, Int>> =
        progressBus?.pendingSteerCounts ?: MutableStateFlow(emptyMap())
    val activeVirtualDisplay: StateFlow<LiveMissionProgressBus.ActiveVirtualDisplay?> =
        progressBus?.activeVirtualDisplay ?: MutableStateFlow(null)
    val modelRoutes: StateFlow<AgentModelRouter.Snapshot> =
        modelRouter?.snapshot ?: MutableStateFlow(AgentModelRouter.Snapshot())
    val squadIdentityBindings: StateFlow<Map<String, AgentSquadIdentityStore.IdentityBinding>> =
        squadIdentities?.bindings ?: MutableStateFlow(emptyMap())
    val teachState: StateFlow<AgentTeachRecorder.State> =
        teachRecorder?.state ?: MutableStateFlow(AgentTeachRecorder.State())
    val channelsFlow: StateFlow<List<io.zer0.muse.channel.ChannelConfig>> =
        channelManager?.channels ?: MutableStateFlow(emptyList())
    val assistantsFlow: Flow<List<io.zer0.muse.data.assistant.AssistantEntity>> =
        assistantRepository?.observeAll ?: flowOf(emptyList())
    val artifactsFlow: Flow<List<io.zer0.muse.data.artifact.ArtifactEntity>> =
        artifactRepository?.observeAll() ?: flowOf(emptyList())
    val chatPreferencesFlow: Flow<io.zer0.muse.data.ChatPreferences> =
        settingsRepository?.chatPreferencesFlow ?: flowOf(io.zer0.muse.data.ChatPreferences())
    val webSearchConfigFlow: Flow<io.zer0.muse.web.WebSearchConfig> =
        settingsRepository?.webSearchConfigFlow ?: flowOf(io.zer0.muse.web.WebSearchConfig())
    val knowledgeDocsFlow: Flow<List<io.zer0.muse.data.knowledge.KnowledgeDocEntity>> =
        knowledgeDocDao?.observeAllUser() ?: flowOf(emptyList())

    private val _deepThinkingEnabled = MutableStateFlow(false)
    val deepThinkingEnabled: StateFlow<Boolean> = _deepThinkingEnabled.asStateFlow()

    private val _deepThinkingLevel = MutableStateFlow(io.zer0.ai.core.ReasoningLevel.HIGH)
    val deepThinkingLevel: StateFlow<io.zer0.ai.core.ReasoningLevel> = _deepThinkingLevel.asStateFlow()

    private val _hasOlderTranscript = MutableStateFlow(false)
    val hasOlderTranscript: StateFlow<Boolean> = _hasOlderTranscript.asStateFlow()

    private val _loadingOlderTranscript = MutableStateFlow(false)
    val loadingOlderTranscript: StateFlow<Boolean> = _loadingOlderTranscript.asStateFlow()

    /** 页面数据四态。切作用域时回到 Loading；读取失败进 Error 且保留上次内容。 */
    private val _scopeState = MutableStateFlow<AgentBotScopeState>(AgentBotScopeState.Loading)
    val scopeState: StateFlow<AgentBotScopeState> = _scopeState.asStateFlow()

    private val _taskDetails = MutableStateFlow<AgentBotUiState.TaskDetails?>(null)
    val taskDetails: StateFlow<AgentBotUiState.TaskDetails?> = _taskDetails.asStateFlow()

    private val _candidatePreview = MutableStateFlow<AgentBotUiState.CandidatePreview?>(null)
    val candidatePreview: StateFlow<AgentBotUiState.CandidatePreview?> = _candidatePreview.asStateFlow()

    /** 解析后的 canonical 会话。宿主只给 assistantId 时，页面靠它恢复上次的会话。 */
    private val _boundSessionId = MutableStateFlow("")
    val boundSessionId: StateFlow<String> = _boundSessionId.asStateFlow()

    /** 当前作用域请求。null 表示页面还没 bind。 */
    private val scopeRequest = MutableStateFlow<ScopeRequest?>(null)

    /** 必须在 scopeRequest 声明之后初始化，避免 Kotlin 属性初始化顺序 NPE。 */
    val routinesFlow: Flow<List<BotRoutineEntity>> =
        scopeRequest.flatMapLatest { req ->
            val botId = req?.assistantId.orEmpty().ifBlank { DEFAULT_BOT_ID }
            routineEngine?.observeByBot(botId) ?: flowOf(emptyList())
        }

    /** 重试计数。每点一次重试 +1，flatMapLatest 据此重新收集一遍。 */
    private val scopeRetry = MutableStateFlow(0L)

    /**
     * 暂存区清单。它不是 Room 表，只能由显式刷新推进。
     *
     * 存的是**读取结果**而不是列表：读失败与"没有候选"必须能区分，
     * 否则一次 IO 故障会被页面显示成"候选没了"。
     */
    private val stagedCandidates =
        MutableStateFlow<StagedArtifactStore.StagedListResult>(
            StagedArtifactStore.StagedListResult.Success(emptyList()),
        )

    /** 上一次成功投影。Error 与重试都靠它保住"已经看到的东西"。 */
    private var lastProjection: AgentBotProjection? = null

    /** 当前前台回合的 Job。stopReply() 只取消它。 */
    private var foregroundTurnJob: Job? = null

    private var currentBotId: String = DEFAULT_BOT_ID
    private var currentSessionId: String = ""

    init {
        viewModelScope.launch {
            combine(scopeRequest, scopeRetry) { request, token -> request to token }
                .flatMapLatest { (request, token) ->
                    if (request == null) {
                        flowOf<AgentBotScopeState>(AgentBotScopeState.Loading)
                    } else {
                        resolveScopeStates(request, token)
                    }
                }
                .collect { state -> onScopeState(state) }
        }
    }

    /** 一次页面范围请求：assistant + 宿主传入的 canonical 会话（可能为空，待绑定回读）。 */
    private data class ScopeRequest(val assistantId: String, val sessionId: String)

    /**
     * 绑定当前 Bot 与它的 canonical 会话。
     *
     * 会话 id 由宿主传入，不从"当前在看哪个会话"推断 —— 那正是跨会话污染的来源。
     * 绑定会落库（[AgentBotRepository.ensureBinding]）：进程重启后重新进页面，
     * 宿主可能只拿得到 assistantId，这时从绑定里回读同一个 canonical 会话。
     */
    fun bind(botId: String, originSessionId: String) {
        val assistantId = botId.ifBlank { DEFAULT_BOT_ID }
        val previousBotId = currentBotId
        // 会话为空只说明宿主这次没有更好的信息，不代表用户换了作用域。
        val scopeChanged = assistantId != previousBotId ||
            (originSessionId.isNotBlank() && originSessionId != currentSessionId)
        currentBotId = assistantId
        currentSessionId = resolveBoundSession(assistantId, previousBotId, originSessionId)
        ui.setComposerBot(assistantId)
        refreshCandidates()
        refreshAllowances()
        if (scopeChanged) {
            // 切作用域先清空：Loading 期间继续显示上一个 Bot 的看板比短暂空白更误导。
            lastProjection = null
            _hasOlderTranscript.value = false
            clearProjectionViews()
        }
        // 对话流也必须从库里重建：只在内存里累加的话，重启/切会话后是空白。
        reloadTranscript()
        scopeRequest.value = ScopeRequest(assistantId, originSessionId)
    }

    /**
     * 决定这次 bind 之后页面跟哪个会话。
     *
     * 宿主重开页面时可能只给 assistantId（会话为空）：那不算换作用域，
     * 沿用它上次的会话。换了助手又没给会话时，旧会话属于上一个助手，
     * 不能带过去当兜底 —— 否则页面会显示另一个助手的对话。
     */
    private fun resolveBoundSession(
        assistantId: String,
        previousBotId: String,
        originSessionId: String,
    ): String = when {
        originSessionId.isNotBlank() -> originSessionId
        assistantId != previousBotId -> ""
        else -> currentSessionId
    }

    /**
     * 观察当前 assistant + canonical 会话的完整投影。
     *
     * 任务、审批、候选、transcript 全部按同一作用域过滤；任何一项换成全局查询，
     * 页面就会显示别的 Bot 的东西。读取异常不在这里吞掉 —— 交给
     * [asScopeStates] 变成 Error 并保留上一次成功内容。
     */
    fun observeAgentScope(assistantId: String, sessionId: String): Flow<AgentBotProjection> {
        if (assistantId.isBlank() || sessionId.isBlank()) {
            return flowOf(AgentBotProjection(assistantId = assistantId, sessionId = sessionId))
        }
        val baseFlow = combine(
            repository.observeScope(assistantId, sessionId),
            stagedCandidates,
            restorer.observe(sessionId),
        ) { scope, staged, transcript ->
            val entries = when (staged) {
                is StagedArtifactStore.StagedListResult.Success -> staged.entries
                // 读失败不能当成"没有候选"：这里直接抛给 asScopeStates，
                // 由它折叠成 Error 并保留上一次成功内容。
                is StagedArtifactStore.StagedListResult.Error ->
                    throw StagedCandidatesUnavailableException(staged.reason, staged.cause)
            }
            Triple(scope, entries, transcript)
        }
        return combine(baseFlow, liveSteps, pendingSteerCounts) { (scope, entries, transcript), _, _ ->
            project(scope, entries, transcript)
        }
    }

    /** 读取失败后的重试入口。token 递增让界面区分"新一轮失败"。 */
    fun retryScope() {
        // 候选是文件不是 Room 表：重试必须显式重读，否则一次读失败会原地卡住。
        refreshCandidates()
        scopeRetry.update { it + 1 }
    }

    /** 解析绑定 → 收集投影 → 折叠四态。解析失败同样是 Error，不是空列表。 */
    private fun resolveScopeStates(request: ScopeRequest, retryToken: Long): Flow<AgentBotScopeState> = flow {
        emit(AgentBotScopeState.Loading)
        val sessionId = if (request.sessionId.isBlank()) {
            repository.getBindingForAssistant(request.assistantId)?.canonicalAgentSessionId.orEmpty()
        } else {
            repository.ensureBinding(request.assistantId, request.sessionId).canonicalAgentSessionId
        }
        if (sessionId.isBlank()) {
            emit(AgentBotScopeState.Empty)
            return@flow
        }
        // 解析期间可能已经切走：旧作用域的会话不得覆盖新的 currentSessionId。
        currentCoroutineContext().ensureActive()
        currentSessionId = sessionId
        _boundSessionId.value = sessionId
        emitAll(observeAgentScope(request.assistantId, sessionId).asScopeStates(retryToken, lastProjection))
    }.catch { cause ->
        if (cause is CancellationException) throw cause
        Logger.e("AgentBotVM", "解析页面作用域失败: ${cause.message}", cause)
        emit(
            AgentBotScopeState.Error(
                message = cause.message ?: "scope_resolve_failed",
                retryToken = retryToken,
                lastContent = lastProjection,
            ),
        )
    }

    /**
     * 把作用域快照映射成页面投影。
     *
     * NEEDS_USER 的两种来源仍按"该任务有没有已结算批准且已无待审批"区分；
     * 审批行来自同一份作用域快照，不再为每个任务单独查库。
     */
    private fun project(
        scope: AgentBotScope,
        staged: List<StagedArtifactStore.StagedEntry>,
        transcript: List<AgentBotUiState.PublishedMessage>,
    ): AgentBotProjection {
        val approvedAwaitingRetry = approvedAwaitingRetryTaskIds(scope)
        val liveStepMap = progressBus?.liveSteps?.value.orEmpty()
        val steerCounts = progressBus?.pendingSteerCounts?.value.orEmpty()
        val persistedStepsByTask = groupPersistedStepsByTask(scope)
        val mergedStepsByTask = scope.tasks.associate { task ->
            val persisted = persistedStepsByTask[task.taskId].orEmpty()
            val live = liveStepMap[task.taskId].orEmpty().map { item ->
                AgentBotUiState.TaskStepUi(
                    stepId = item.stepId,
                    stepIndex = item.stepIndex,
                    toolName = item.toolName,
                    displayLabel = item.displayLabel,
                    status = item.status,
                    summary = item.summary,
                    assigneeBotId = item.assigneeBotId,
                )
            }
            val merged = (persisted.associateBy { it.stepId } + live.associateBy { it.stepId })
                .values
                .sortedBy { it.stepIndex }
            task.taskId to merged
        }
        val rows = scope.tasks.map { task ->
            task.toRow(
                approvedAwaitingRetry = task.taskId in approvedAwaitingRetry,
                steps = mergedStepsByTask[task.taskId].orEmpty(),
                pendingSteerCount = steerCounts[task.taskId] ?: 0,
            )
        }
        val annotationsByMessage = scope.annotations
            .filter { it.targetMessageId.isNotBlank() && it.assetRef.isNotBlank() }
            .groupBy { it.targetMessageId }
            .mapValues { (_, items) ->
                items.map { entity ->
                    AgentBotUiState.MessageReactionUi(
                        id = entity.id,
                        emoji = entity.assetRef,
                        actorType = entity.actorType,
                        kind = entity.kind,
                    )
                }
            }
        val cards = scope.cards.map { entity -> entity.toCardUi() }
        return AgentBotProjection(
            assistantId = scope.assistantId,
            sessionId = scope.sessionId,
            inProgress = rows.filter {
                it.status in BotTaskStatus.NON_TERMINAL && it.status !in ACTION_REQUIRED_STATUSES
            },
            needsAction = rows.filter { it.status in ACTION_REQUIRED_STATUSES },
            recent = rows.filter {
                it.status in BotTaskStatus.TERMINAL && it.status !in ACTION_REQUIRED_STATUSES
            },
            pendingApprovals = scope.pendingApprovals.map { approval ->
                approval.toApprovalRow(scope.tasks.firstOrNull { it.taskId == approval.taskId })
            },
            candidates = staged.filter { it.stagedId in scope.candidateIds }.map { it.toCandidateRow() },
            transcript = transcript.takeLast(TRANSCRIPT_DISPLAY_LIMIT),
            annotationsByMessage = annotationsByMessage,
            cards = cards,
            stepsByTask = mergedStepsByTask,
        )
    }

    private fun groupPersistedStepsByTask(scope: AgentBotScope): Map<String, List<AgentBotUiState.TaskStepUi>> {
        if (scope.steps.isEmpty()) return emptyMap()
        // 步骤按 runId 分组，若单任务有多轮 run 则按开始顺序合入展示。
        val taskIdByRunId = mutableMapOf<String, String>()
        scope.approvals.forEach { approval ->
            if (approval.runId.isNotBlank() && approval.taskId.isNotBlank()) {
                taskIdByRunId[approval.runId] = approval.taskId
            }
        }
        val grouped = mutableMapOf<String, MutableList<AgentBotUiState.TaskStepUi>>()
        scope.steps.forEach { step ->
            val taskId = taskIdByRunId[step.runId]
                ?: scope.tasks.firstOrNull { it.status in BotTaskStatus.NON_TERMINAL }?.taskId
                ?: scope.tasks.firstOrNull()?.taskId
                ?: return@forEach
            val list = grouped.getOrPut(taskId) { mutableListOf() }
            list += AgentBotUiState.TaskStepUi(
                stepId = step.stepId,
                stepIndex = step.stepIndex,
                toolName = step.toolName,
                displayLabel = step.displayLabel.ifBlank { step.toolName.replace('_', ' ') },
                status = step.status,
                summary = step.summary,
                assigneeBotId = step.assigneeBotId,
            )
        }
        return grouped
    }

    private fun BotMessageCardEntity.toCardUi(): AgentBotUiState.MessageCardUi {
        val options = if (cardKind == BotCardKind.CHOICE || cardKind == BotCardKind.CUE_BRIEFING) {
            runCatching {
                cardJson.decodeFromString<List<AgentStreamCoordinator.ChoiceOption>>(payloadJson)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return AgentBotUiState.MessageCardUi(
            cardId = cardId,
            anchorMessageId = anchorMessageId,
            taskId = taskId,
            kind = cardKind,
            title = title,
            subtitle = subtitle,
            options = options,
            status = status,
            selectedOptionId = selectedOptionId,
            createdAt = createdAt,
            payloadJson = payloadJson,
        )
    }

    private fun approvedAwaitingRetryTaskIds(scope: AgentBotScope): Set<String> =
        scope.tasks
            .filter { it.status == BotTaskStatus.NEEDS_USER }
            .filter { task ->
                val approvals = scope.approvals.filter { it.taskId == task.taskId }
                approvals.any {
                    it.status == ApprovalStatus.APPROVED && it.decisionReason.isBlank()
                } && approvals.none { it.status == ApprovalStatus.PENDING }
            }
            .mapTo(mutableSetOf()) { it.taskId }

    private fun onScopeState(state: AgentBotScopeState) {
        when (state) {
            is AgentBotScopeState.Content -> {
                // 过期投影连状态都不进：界面看到 Content 就一定显示这份内容，
                // 放进来会让"当前作用域"的判定与界面实际显示的内容对不上。
                if (!applyProjection(state.projection)) return
            }
            is AgentBotScopeState.Empty -> clearProjectionViews()
            // 读取失败保留上一次已显示的内容：这里不清理任何东西。
            is AgentBotScopeState.Error -> Unit
            AgentBotScopeState.Loading -> Unit
        }
        _scopeState.value = state
    }

    /**
     * 只有仍属于当前作用域的投影才能落到界面，慢一步的旧结果直接丢弃。
     *
     * @return 是否被接受。false 表示这是一份过期投影，调用方也不要把它记成最新状态。
     */
    private fun applyProjection(projection: AgentBotProjection): Boolean {
        // 守卫必须在写入之前：过期投影一旦进了 lastProjection，
        // 下一次读取失败就会把它当成"上次成功内容"回填到 Error.lastContent ——
        // 界面会把上一个 Bot 的内容当作当前内容展示。
        if (projection.assistantId != currentBotId || projection.sessionId != currentSessionId) {
            return false
        }
        lastProjection = projection
        _pendingCandidates.value = projection.candidates
        ui.setTaskBoard(
            AgentBotUiState.TaskBoard(
                tasks = projection.taskRows,
                approvals = projection.pendingApprovals,
            ),
        )
        // 合并而不是替换：前台回合刚落进内存的那句话还没经过发布通道落库。
        ui.replacePublished(restorer.merge(projection.transcript, ui.transcript.value))
        return true
    }

    private fun clearProjectionViews() {
        _pendingCandidates.value = emptyList()
        ui.setTaskBoard(AgentBotUiState.TaskBoard())
        ui.replacePublished(emptyList())
    }

    /** 会话解析结果。读失败与"确实没绑过"必须分开，否则新会话会把旧绑定挤掉。 */
    private sealed interface SessionResolution {
        data class Ready(val sessionId: String) : SessionResolution

        /** 绑定读不出来：本次发送必须中止，不新建会话、不改写绑定。 */
        data object BindingUnreadable : SessionResolution

        /** 没有绑定，且创建会话失败。 */
        data object CannotCreate : SessionResolution
    }

    /**
     * 确保有可用的 Agent 会话；没有就创建一个并绑定。
     *
     * 优先回读绑定：宿主在新进程里可能只拿得到 assistantId，直接新建会话
     * 会让用户看到一片空白的历史，而旧消息其实都还在原来的 canonical 会话里。
     *
     * **读取失败不等于没有绑定。** 把异常当成 null 会顺着往下新建会话并
     * `ensureBinding` 覆盖旧行 —— 用户的历史就此"消失"。所以这里返回三态，
     * 读失败直接中止，交给调用方提示。
     */
    private suspend fun ensureAgentSession(botId: String): SessionResolution {
        val currentSession = currentSessionId.takeIf { currentBotId == botId }.orEmpty()
        if (currentSession.isNotBlank()) return SessionResolution.Ready(currentSession)
        val bound = try {
            repository.getBindingForAssistant(botId)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            Logger.e("AgentBotVM", "读取会话绑定失败，本次发送中止: ${cause.message}", cause)
            return SessionResolution.BindingUnreadable
        }?.canonicalAgentSessionId.orEmpty()
        if (bound.isNotBlank()) {
            adoptCanonicalSession(botId, bound)
            return SessionResolution.Ready(bound)
        }
        val created = runCatching { sessions.createAgentSession(botId) }
            .onFailure { Logger.e("AgentBotVM", "自动创建 Agent 会话失败", it) }
            .getOrNull() ?: return SessionResolution.CannotCreate
        adoptCanonicalSession(botId, created)
        runCatching { repository.ensureBinding(botId, created) }
            .onFailure { Logger.w("AgentBotVM", "写入 canonical 会话绑定失败: ${it.message}") }
        Logger.i("AgentBotVM", "已自动创建 Agent 会话: $created")
        return SessionResolution.Ready(created)
    }

    private fun adoptCanonicalSession(botId: String, sessionId: String) {
        if (currentBotId != botId) return
        currentSessionId = sessionId
        _boundSessionId.value = sessionId
        // 首次发送前绑定可能是空的；拿到会话后让页面开始按它投影。
        if (scopeRequest.value?.sessionId != sessionId) {
            scopeRequest.value = ScopeRequest(botId, sessionId)
        }
    }
    fun onComposerTextChange(text: String) {
        ui.setComposerText(text)
    }

    fun setQuotedMessage(message: AgentBotUiState.PublishedMessage?) {
        ui.setQuotedMessage(message)
    }

    fun startEditingMessage(message: AgentBotUiState.PublishedMessage) {
        ui.setEditingMessage(message.messageId, message.text)
    }

    fun cancelEditingMessage() {
        ui.setEditingMessage(null, "")
    }

    fun toggleDeepThinking() {
        _deepThinkingEnabled.update { !it }
    }

    fun cycleDeepThinkingLevel() {
        _deepThinkingLevel.update { current ->
            when (current) {
                io.zer0.ai.core.ReasoningLevel.LOW -> io.zer0.ai.core.ReasoningLevel.MEDIUM
                io.zer0.ai.core.ReasoningLevel.MEDIUM -> io.zer0.ai.core.ReasoningLevel.HIGH
                io.zer0.ai.core.ReasoningLevel.HIGH -> io.zer0.ai.core.ReasoningLevel.XHIGH
                io.zer0.ai.core.ReasoningLevel.XHIGH -> io.zer0.ai.core.ReasoningLevel.LOW
                else -> io.zer0.ai.core.ReasoningLevel.HIGH
            }
        }
    }

    fun toggleWebSearch() {
        val repo = settingsRepository ?: return
        viewModelScope.launch {
            runCatching {
                val cfg = repo.webSearchConfigFlow.first()
                val enabling = !cfg.enabled || cfg.mode == io.zer0.muse.web.WebSearchMode.OFF
                val nextCfg = cfg.copy(
                    enabled = enabling,
                    mode = if (enabling) {
                        if (cfg.mode == io.zer0.muse.web.WebSearchMode.OFF) {
                            io.zer0.muse.web.WebSearchMode.AUTO
                        } else {
                            cfg.mode
                        }
                    } else {
                        io.zer0.muse.web.WebSearchMode.OFF
                    },
                )
                repo.saveWebSearchConfig(nextCfg)
            }.onFailure { Logger.w("AgentBotVM", "切换联网搜索失败: ${it.message}", it) }
        }
    }

    fun addPendingImage(uri: Uri, context: Context) {
        if (ui.composer.value.pendingImages.size >= AgentBotUiState.MAX_PENDING_IMAGES) {
            ui.setError(AgentBotError.ATTACHMENT_LIMIT)
            return
        }
        viewModelScope.launch {
            ui.setError(null)
            val image = readImageContentBase64(uri, context) { error ->
                Logger.w("AgentBotVM", "读取图片附件失败: ${error.message}", error)
            }
            if (image.isBlank()) {
                ui.setError(AgentBotError.ATTACHMENT_FAILED)
            } else if (!ui.addPendingImage(image)) {
                ui.setError(AgentBotError.ATTACHMENT_LIMIT)
            }
        }
    }

    fun removePendingImage(imageBase64: String) {
        ui.removePendingImages(listOf(imageBase64))
    }

    /**
     * 发送。
     *
     * 关键点：**永不因为"正在生成"而拒绝**。后台任务在跑的时候，
     * 用户必须还能说话 —— 那是这个功能存在的理由。
     */
    fun send() {
        val composer = ui.composer.value
        submit(composer.text, composer.pendingImages, composer)
    }

    private fun submit(
        text: String,
        imageBase64List: List<String>,
        submittedComposer: AgentBotUiState.Composer?,
    ) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank() && imageBase64List.isEmpty()) return
        val quotedPreview = submittedComposer?.quotedMessage?.text
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
            .take(120)
        val effectiveText = when {
            quotedPreview.isNotBlank() && trimmedText.isNotBlank() -> "> $quotedPreview\n\n$trimmedText"
            quotedPreview.isNotBlank() -> "> $quotedPreview"
            else -> trimmedText
        }
        val editingMessageId = submittedComposer?.editingMessageId
        val botId = currentBotId
        viewModelScope.launch {
            ui.setSending(true)
            ui.setError(null)
            // 无会话时自动创建 —— 沿用旧 Agent 聊天的既有行为（v1.28）。
            //
            // 真机验收抓到的缺陷：新装应用进 Agent Tab 打字发送，
            // 原来直接报「还没有可用的会话」。会话不是用户该操心的东西 ——
            // 第一次说话时应用就该替他把这个 Bot 的 canonical 会话建出来。
            val sessionId = when (val resolution = ensureAgentSession(botId)) {
                is SessionResolution.Ready -> resolution.sessionId
                // 绑定读不出来：中止本次发送。新建会话会覆盖绑定，
                // 用户原来的历史看起来就像"消失"了 —— 那比"这次没发出去"糟得多。
                SessionResolution.BindingUnreadable -> {
                    ui.setSending(false)
                    ui.setError(AgentBotError.BINDING_UNREADABLE)
                    return@launch
                }
                SessionResolution.CannotCreate -> {
                    ui.setSending(false)
                    ui.setError(AgentBotError.NO_SESSION)
                    return@launch
                }
            }
            if (!editingMessageId.isNullOrBlank()) {
                runCatching { sessions.deleteMessage(editingMessageId) }
                    .onFailure { Logger.w("AgentBotVM", "编辑重发删除原消息失败: ${it.message}", it) }
                if (currentBotId == botId && currentSessionId == sessionId) {
                    ui.replacePublished(ui.transcript.value.filterNot { it.messageId == editingMessageId })
                }
            }
            val accepted = facade.acceptUserInput(
                botId = botId,
                originSessionId = sessionId,
                text = effectiveText,
            )
            if (accepted == null) {
                // 落盘失败：内容留在输入框，给可操作错误，不静默吞掉。
                ui.setSending(false)
                ui.setError(AgentBotError.SAVE_FAILED)
                return@launch
            }
            // 用户消息本身也要落库并显示。
            //
            // 真机验收抓到的缺陷：acceptUserInput 只落事件、不落消息，
            // 于是对话流里只有 Bot 的话，用户看不到自己说过什么 ——
            // 重启后更是彻底没了。事件是给调度用的，消息才是给人看的，
            // 两者都要写。
            try {
                sessions.appendMessage(
                    sessionId,
                    io.zer0.ai.core.UIMessage(
                        id = kotlin.uuid.Uuid.parse(accepted.messageId),
                        role = io.zer0.ai.core.MessageRole.USER,
                        content = effectiveText,
                        imageBase64List = imageBase64List,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 事件已落盘，但没有持久消息时不应让模型回合继续。
                Logger.w("AgentBotVM", "用户消息落库失败（事件已接纳）: ${e.message}", e)
                ui.setSending(false)
                ui.setError(AgentBotError.SAVE_FAILED)
                return@launch
            }
            if (currentBotId == botId && currentSessionId == sessionId) {
                ui.appendPublished(
                    AgentBotUiState.PublishedMessage(
                        messageId = accepted.messageId,
                        role = AgentBotUiState.Role.USER,
                        text = effectiveText,
                        createdAt = System.currentTimeMillis(),
                        imageBase64List = imageBase64List,
                    ),
                )
                if (submittedComposer != null) {
                    if (ui.composer.value.text == submittedComposer.text) ui.setComposerText("")
                    if (ui.composer.value.quotedMessage == submittedComposer.quotedMessage) {
                        ui.setQuotedMessage(null)
                    }
                    if (ui.composer.value.editingMessageId == submittedComposer.editingMessageId) {
                        ui.setEditingMessage(null, if (ui.composer.value.text == submittedComposer.text) "" else ui.composer.value.text)
                    }
                    ui.removePendingImages(imageBase64List)
                }
            }
            // 关键：先放开输入再调模型。
            //
            // 旧实现在同一个协程里等模型返回，"发送中"会一直亮到模型答完 ——
            // 用户看到的仍然是"我在等你说话"，而不是"你可以继续"。那正是这个
            // 功能要消除的等待感。所以回合丢到独立 Job，输入立刻可用。
            ui.setSending(false)
            // 事件 id 即这次接纳的凭据；任务靠它回溯到触发它的那条消息。
            val identity = io.zer0.muse.data.agentbot.AgentTurnIdentity(
                assistantId = botId,
                sessionId = sessionId,
                requestId = accepted.messageId,
                requestRevision = accepted.requestRevision,
            )
            // 记下这一轮的 Job：stopReply() 只取消它，不碰后台任务。
            foregroundTurnJob = viewModelScope.launch {
                runForegroundTurn(identity, effectiveText, imageBase64List)
                runTurnOnce(botId, sessionId)
            }
        }
    }

    /**
     * 跑一个前台回合：让主 Bot 说一句，必要时派工。
     *
     * 失败时不弹错误 —— 用户的输入已经落盘（facade 保证），
     * 只是这一轮没回复。给错误横幅会让用户以为消息丢了。
     */
    private suspend fun runForegroundTurn(
        identity: io.zer0.muse.data.agentbot.AgentTurnIdentity,
        userText: String,
        imageBase64List: List<String>,
    ) {
        // 多轮上下文：取该会话最近若干条。不传空列表 ——
        // 主 Bot 只看到"这一句"时无法理解"那个""再来一次"这类指代。
        val history = runCatching {
            sessions.getRecentMessages(identity.sessionId, HISTORY_LIMIT)
                .filterNot { it.id.toString() == identity.requestId }
        }.onFailure {
            // 读不到历史不该让整轮失败：退化成单轮仍能回答，只是少了上下文。
            Logger.w("AgentBotVM", "读取历史失败，退化为单轮: ${it.message}")
        }.getOrDefault(emptyList())

        val streamingEngine = streamCoordinator
        if (streamingEngine != null) {
            runStreamingForegroundTurn(
                streamingEngine = streamingEngine,
                identity = identity,
                history = history,
                userText = userText,
                imageBase64List = imageBase64List,
            )
            return
        }

        val turn = coordinator.runTurn(
            identity = identity,
            history = history,
            userText = userText,
            currentRequestRevision = {
                repository.latestUserInputSequence(identity.assistantId, identity.sessionId)
            },
            inputImageBase64List = imageBase64List,
        ) ?: return
        if (turn.requestSuperseded) return
        // V15: 模型始终没调 bot_say 时，明确告诉用户"这条没发出去"，并保留重试入口。
        // 不拿模型正文兜底 —— 那会伪造出一条用户以为是回复的消息。
        val isCurrentScope = currentBotId == identity.assistantId && currentSessionId == identity.sessionId
        if (turn.missingBotSay) {
            if (isCurrentScope) {
                ui.setError(AgentBotError.MISSING_BOT_SAY)
            }
            return
        }
        // 清单 F4：Provider 静默返回纯文本（两次都没有 tool_calls）时，
        // 必须明确告知"当前 Provider 不支持工具调用，主 Bot 无法派工"。
        //
        // 不告诉的话，用户只会看到主 Bot 聊得很正常但从不做事，
        // 而原因（换一个 Provider 就能好）完全无从得知。
        if (turn.providerToolBlind && isCurrentScope) {
            ui.setError(AgentBotError.PROVIDER_TOOL_BLIND)
        }
        var publishedMessageId = turn.publishedMessageId
        if (turn.shouldDispatch) {
            val task = try {
                repository.createTaskForInput(
                    TaskCreationInput(
                        identity = identity,
                        goal = turn.dispatchGoal,
                        acceptanceCriteria = turn.acceptance,
                        resourceScope = turn.resourceScope,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w("AgentBotVM", "派工失败: ${e.message}", e)
                null
            } ?: return

            if (turn.say.isNotBlank()) {
                val result = try {
                    publication.publish(
                        botId = identity.assistantId,
                        originSessionId = identity.sessionId,
                        causeEventIds = identity.requestId,
                        requestRevision = identity.requestRevision,
                        taskRevision = 1,
                        items = listOf(PublicationService.Item(PublicationKind.TEXT, turn.say)),
                        currentRequestRevision = repository.latestUserInputSequence(
                            identity.assistantId,
                            identity.sessionId,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w("AgentBotVM", "派工确认发布失败: task=${task.taskId}", e)
                    null
                }
                publishedMessageId = result?.messageIds?.firstOrNull().orEmpty()
            }
            if (isCurrentScope) refreshBoard()
        }

        if (publishedMessageId.isNotBlank() && isCurrentScope) {
            ui.appendPublished(
                AgentBotUiState.PublishedMessage(
                    messageId = publishedMessageId,
                    role = AgentBotUiState.Role.BOT,
                    text = turn.say,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * 原生流式快慢双轨前台回合：
     * - 实时输出流式气泡草稿，毫秒级首字响应；
     * - 同步结算角标 Emoji 反应、后台异步任务派发、活体插话、后续建议卡与惯例创建；
     * - 派发后台任务后 0ms 立即唤醒调度器。
     */
    private suspend fun runStreamingForegroundTurn(
        streamingEngine: AgentStreamCoordinator,
        identity: io.zer0.muse.data.agentbot.AgentTurnIdentity,
        history: List<io.zer0.ai.core.UIMessage>,
        userText: String,
        imageBase64List: List<String>,
    ) {
        val isCurrentScope = { currentBotId == identity.assistantId && currentSessionId == identity.sessionId }
        if (isCurrentScope()) {
            ui.setTyping(true)
            ui.setStreaming(AgentBotUiState.StreamingBubble(active = true))
        }
        try {
            val assistant = runCatching { assistantRepository?.getById(identity.assistantId) }.getOrNull()
            val activeMissions = runCatching {
                repository.listTasks(identity.assistantId)
                    .filter { it.originSessionId == identity.sessionId && it.status in BotTaskStatus.NON_TERMINAL }
                    .map { task ->
                        AgentCapabilityPromptBuilder.ActiveMissionSummary(
                            taskId = task.taskId,
                            goal = task.goal,
                            status = task.status,
                        )
                    }
            }.getOrDefault(emptyList())
            val systemPrompt = promptBuilder?.build(
                assistantName = assistant?.name.orEmpty(),
                assistantSystemPrompt = assistant?.systemPrompt.orEmpty(),
                activeMissions = activeMissions,
            ).orEmpty()
            val router = modelRouter
            val store = configStore
            val routedFromRouter = if (router != null && store != null) {
                runCatching { router.resolve(AgentModelRouter.Role.FOREGROUND, store) }
                    .getOrDefault(null to null)
            } else {
                null to null
            }
            val (routedProvider, routedModel) = if (routedFromRouter.second != null) {
                routedFromRouter
            } else if (assistant?.modelId != null && store != null) {
                val providers = runCatching { store.getAllProviders() }.getOrDefault(emptyList())
                val matchedProvider = assistant.providerId?.takeIf { it.isNotBlank() }?.let { pid ->
                    providers.firstOrNull { p -> p.id == pid && p.models.any { it.id == assistant.modelId } }
                } ?: providers.firstOrNull { p -> p.models.any { it.id == assistant.modelId } }
                val matchedModel = matchedProvider?.models?.firstOrNull { it.id == assistant.modelId }
                if (matchedModel != null) matchedProvider to matchedModel else routedFromRouter
            } else {
                routedFromRouter
            }

            val webSearchEnabled = runCatching {
                settingsRepository?.webSearchConfigFlow?.first()?.enabled == true
            }.getOrDefault(false)
            val assistantReasoningLevel = assistant?.reasoningLevel?.let { raw ->
                runCatching { io.zer0.ai.core.ReasoningLevel.valueOf(raw) }.getOrNull()
            }
            val reasoningLevel = when {
                _deepThinkingEnabled.value -> _deepThinkingLevel.value
                assistantReasoningLevel != null &&
                    assistantReasoningLevel != io.zer0.ai.core.ReasoningLevel.OFF -> assistantReasoningLevel
                else -> runCatching {
                    settingsRepository?.getChatPreferences()?.let { prefs ->
                        if (prefs.forceReasoning) prefs.forceReasoningLevel else io.zer0.ai.core.ReasoningLevel.DEFAULT
                    }
                }.getOrNull() ?: io.zer0.ai.core.ReasoningLevel.DEFAULT
            }

            val outcome = streamingEngine.runStreamingTurn(
                identity = identity,
                systemPrompt = systemPrompt,
                history = history,
                userText = userText,
                inputImageBase64List = imageBase64List,
                providerConfig = routedProvider,
                model = routedModel,
                assistant = assistant,
                nativeWebSearch = webSearchEnabled,
                reasoningLevel = reasoningLevel,
                currentRequestRevision = {
                    repository.latestUserInputSequence(identity.assistantId, identity.sessionId)
                },
                onDraft = { draft ->
                    if (isCurrentScope()) {
                        ui.setStreaming(
                            AgentBotUiState.StreamingBubble(
                                active = true,
                                text = draft.text,
                                reasoning = draft.reasoning,
                                citationUrls = draft.citationUrls,
                            ),
                        )
                    }
                },
            )
            if (outcome.requestSuperseded) return

            // 1. 气泡角标 Emoji 反应
            val userHash = BotAnnotationService.hashOf(userText)
            outcome.reactions.forEach { reaction ->
                val targetId = reaction.targetMessageId.ifBlank { identity.requestId }
                runCatching {
                    annotationService?.annotate(
                        botId = identity.assistantId,
                        originSessionId = identity.sessionId,
                        targetMessageId = targetId,
                        kind = AnnotationKind.REACTION,
                        assetRef = reaction.emoji,
                        contentHash = if (targetId == identity.requestId) userHash else "",
                    )
                }.onFailure { Logger.w("AgentBotVM", "写入消息角标反应失败: ${it.message}") }
            }

            // 2. 落盘后的自然流式回复并入对话流
            if (outcome.publishedMessageId.isNotBlank() && isCurrentScope()) {
                ui.appendPublished(
                    AgentBotUiState.PublishedMessage(
                        messageId = outcome.publishedMessageId,
                        role = AgentBotUiState.Role.BOT,
                        text = outcome.text,
                        createdAt = System.currentTimeMillis(),
                        reasoning = outcome.reasoning,
                        citationUrls = outcome.citationUrls,
                    ),
                )
            }

            // 3. 派发后台任务并 0ms 唤醒调度器
            var anyDispatched = false
            outcome.dispatches.forEachIndexed { idx, dispatch ->
                val targetBotId = dispatch.assigneeBotId.ifBlank { identity.assistantId }
                val created = runCatching {
                    repository.createTaskForInput(
                        TaskCreationInput(
                            identity = identity.copy(assistantId = targetBotId),
                            goal = dispatch.goal,
                            acceptanceCriteria = dispatch.acceptance,
                            resourceScope = dispatch.resourceScope,
                            dedupeKey = "stream-dispatch:${identity.requestId}:$idx",
                        ),
                    )
                }.onFailure {
                    Logger.w("AgentBotVM", "流式派工失败: ${it.message}", it)
                }.getOrNull()
                if (created != null) {
                    anyDispatched = true
                    repository.upsertMessageCard(
                        BotMessageCardEntity(
                            cardId = "mission:${created.taskId}",
                            botId = identity.assistantId,
                            originSessionId = identity.sessionId,
                            anchorMessageId = outcome.publishedMessageId.ifBlank { identity.requestId },
                            taskId = created.taskId,
                            cardKind = BotCardKind.MISSION,
                            title = created.goal,
                            subtitle = dispatch.acceptance,
                        ),
                    )
                }
            }
            if (anyDispatched) {
                scheduler.requestImmediateTick()
                if (isCurrentScope()) refreshBoard()
            }

            // 4. 活体插话热注入运行中任务
            outcome.steerings.forEach { steer ->
                runCatching { scheduler.steerTask(steer.taskId, steer.instruction) }
                    .onFailure { Logger.w("AgentBotVM", "活体插话失败: ${it.message}") }
            }

            // 5. 可点选后续建议卡片
            outcome.choiceOffer?.let { offer ->
                if (offer.options.isNotEmpty()) {
                    repository.upsertMessageCard(
                        BotMessageCardEntity(
                            cardId = "choice:${outcome.publishedMessageId.ifBlank { identity.requestId }}",
                            botId = identity.assistantId,
                            originSessionId = identity.sessionId,
                            anchorMessageId = outcome.publishedMessageId.ifBlank { identity.requestId },
                            cardKind = BotCardKind.CHOICE,
                            title = offer.prompt,
                            payloadJson = cardJson.encodeToString(offer.options),
                        ),
                    )
                }
            }

            // 6. 一句话沉淀为惯例
            outcome.routineCreations.forEach { routine ->
                runCatching {
                    routineEngine?.createOrUpdate(
                        botId = identity.assistantId,
                        title = routine.title,
                        goalPrompt = routine.goalPrompt,
                        resourceScope = routine.resourceScope,
                        triggerType = routine.triggerType,
                        triggerExpr = routine.triggerExpr,
                        originSessionId = identity.sessionId,
                    )
                }.onFailure { Logger.w("AgentBotVM", "流式创建惯例失败: ${it.message}") }
            }

            if (outcome.streamError != null && !outcome.hasAnyAction && isCurrentScope()) {
                ui.setError(AgentBotError.SAVE_FAILED)
            }
        } finally {
            if (isCurrentScope()) {
                ui.setTyping(false)
                ui.clearStreaming()
            }
        }
    }

    /**
     * 跑一轮收件箱。
     *
     * ## 为什么不是「一律消费」
     *
     * 审查 P1-8：原实现 `when (event.kind) { ... else -> true }` 把所有事件都当成
     * 处理成功，随后 `markConsumed`（`UPDATE … WHERE consumedAt = 0`，不可逆）。
     *
     * 结果是 `TASK_RESULT_READY` / `TASK_FAILED` 既没发布也没落 UI，就被永久吞掉 ——
     * 用户永远收不到「你交代的事做完了」。
     *
     * 现在是"发布成功才消费"：
     *
     *  - 用户输入 / 接单 / 进度：接受即可（派工由 Coordinator 负责，不影响这条已收下）
     *  - 可发布事实：必须经发布通道提交后才能消费，失败留着下轮重试
     *  - 执行结果：只是审核入口，这里确认消费但不发言（正文要等审核结论）
     *  - 审核事实：内部判据，看板读任务状态；确认消费避免占住收件箱配额
     */
    suspend fun runTurnOnce(
        botId: String = currentBotId,
        sessionId: String = currentSessionId,
    ) {
        val result = inbox.runTurn(botId) { event ->
            when (event.kind) {
                BotEventKind.USER_INPUT_RECEIVED -> true
                BotEventKind.TASK_ACCEPTED -> true
                BotEventKind.TASK_PROGRESSED -> true
                // 对外发言只能由"审核允许"的发布事实触发。
                // 执行结果事件同样走这里，但共享发布器对它只做确认消费，不产出正文。
                BotEventKind.TASK_PUBLICATION_READY,
                BotEventKind.TASK_RESULT_READY,
                BotEventKind.TASK_FAILED,
                -> publishResult(event, botId, sessionId)
                // 审核事实不是给用户看的话，是给看板与排障用的。
                BotEventKind.REVIEW_READY -> true
                // 未识别的类型同样保留：宁可堆积也不静默丢弃。
                else -> false
            }
        }
        if ((result.consumed > 0 || result.hasBacklog) && currentBotId == botId && currentSessionId == sessionId) {
            refreshBoard()
        }
    }

    /** 结果事件交给共享发布器：页面与后台必须说一模一样的话。 */
    private suspend fun publishResult(
        event: io.zer0.muse.data.agentbot.BotEventEntity,
        botId: String,
        sessionId: String,
    ): Boolean = results.publish(event, fallbackBotId = botId, fallbackSessionId = sessionId)

    /** 领一轮后台任务。由界面在可见时周期性调用。 */
    fun tickScheduler() {
        viewModelScope.launch {
            runCatching { scheduler.tick() }
                .onFailure { Logger.w("AgentBotVM", "调度 tick 失败: ${it.message}") }
            refreshBoard()
        }
    }

    /** 批准一步。 */
    fun approve(approvalId: String, argumentsHash: String, resourceVersion: String) {
        viewModelScope.launch {
            val approval = runCatching { repository.getApproval(approvalId) }.getOrNull()
            when (val outcome = approvals.approve(approvalId, argumentsHash, resourceVersion)) {
                is ApprovalService.Outcome.Settled -> {
                    if (approval != null && approval.toolName.isNotBlank()) {
                        runCatching { allowances.allow(approval.originSessionId.ifBlank { currentSessionId }, approval.toolName) }
                        refreshAllowances()
                    }
                }
                is ApprovalService.Outcome.AlreadySettled -> {
                    ui.setError(AgentBotError.ALREADY_DECIDED)
                }
                is ApprovalService.Outcome.Stale -> {
                    ui.setError(AgentBotError.APPROVAL_STALE)
                }
                is ApprovalService.Outcome.Expired -> {
                    // 过期不伪造用户拒绝：明确告诉用户"超时了，不是你的决定"。
                    ui.setError(AgentBotError.APPROVAL_EXPIRED)
                }
            }
            refreshBoard()
        }
    }

    /** 拒绝一步。 */
    fun deny(approvalId: String, reason: String = "") {
        viewModelScope.launch {
            approvals.deny(approvalId, reason)
            refreshBoard()
        }
    }

    /**
     * 停止本轮回复（V11）。
     *
     * 只停**前台表达**：正在等模型返回的这一轮不再出话。
     * **不动后台任务** —— 那需要显式取消命令（[cancelTask]）。
     *
     * 为什么必须分开：用户说"别说了"通常只是想让它闭嘴，不是想放弃已经交代的活。
     * 把两者合成一个"停止"按钮，用户会以为自己取消了一件正在做的事。
     */
    fun stopReply() {
        val partial = ui.streaming.value
        val botId = currentBotId
        val sessionId = currentSessionId
        foregroundTurnJob?.cancel()
        foregroundTurnJob = null
        ui.setSending(false)
        ui.setTyping(false)
        ui.clearStreaming()
        if (partial.active && partial.text.isNotBlank() && sessionId.isNotBlank()) {
            val savedId = kotlin.uuid.Uuid.random()
            val savedText = partial.text.trimEnd()
            val savedReasoning = partial.reasoning.trim().ifBlank { null }
            val savedCitations = partial.citationUrls
            val now = System.currentTimeMillis()
            if (currentBotId == botId && currentSessionId == sessionId) {
                ui.appendPublished(
                    AgentBotUiState.PublishedMessage(
                        messageId = savedId.toString(),
                        role = AgentBotUiState.Role.BOT,
                        text = savedText,
                        createdAt = now,
                        reasoning = savedReasoning.orEmpty(),
                        citationUrls = savedCitations,
                    ),
                )
            }
            viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
                runCatching {
                    sessions.appendMessage(
                        sessionId,
                        io.zer0.ai.core.UIMessage(
                            id = savedId,
                            role = io.zer0.ai.core.MessageRole.ASSISTANT,
                            content = savedText,
                            reasoning = savedReasoning,
                            citationUrls = savedCitations,
                            createdAt = now,
                        ),
                    )
                }.onFailure { Logger.w("AgentBotVM", "停止生成保存部分回复失败: ${it.message}", it) }
            }
        }
    }

    /** 直接以纯文本发送（用于点击可点选建议卡、主动简报行动卡或语音转写自动发送）。 */
    fun sendQuickText(text: String) {
        if (text.isBlank()) return
        submit(text.trim(), emptyList(), submittedComposer = null)
    }

    /** 用户长按气泡主动贴 Emoji 反应（双向互动）。 */
    fun reactToMessage(messageId: String, emoji: String, actorType: String = io.zer0.muse.data.agentbot.BotAnnotationEntity.ACTOR_USER) {
        val sessionId = currentSessionId
        if (sessionId.isBlank() || messageId.isBlank() || emoji.isBlank()) return
        val content = ui.transcript.value.firstOrNull { it.messageId == messageId }?.text.orEmpty()
        viewModelScope.launch {
            runCatching {
                annotationService?.annotate(
                    botId = currentBotId,
                    originSessionId = sessionId,
                    targetMessageId = messageId,
                    kind = AnnotationKind.REACTION,
                    assetRef = emoji,
                    contentHash = BotAnnotationService.hashOf(content),
                    actorType = actorType,
                )
            }.onFailure { Logger.w("AgentBotVM", "贴表情失败: ${it.message}") }
        }
    }

    fun removeAnnotation(annotationId: String) {
        if (annotationId.isBlank()) return
        viewModelScope.launch {
            runCatching { annotationService?.remove(annotationId) }
                .onFailure { Logger.w("AgentBotVM", "移除表情反应失败: ${it.message}") }
        }
    }

    /** 对运行中的后台任务进行活体插话纠偏。 */
    fun steerTask(taskId: String, instruction: String) {
        if (taskId.isBlank() || instruction.isBlank()) return
        viewModelScope.launch {
            runCatching { scheduler.steerTask(taskId, instruction) }
                .onFailure { Logger.w("AgentBotVM", "任务插话失败: ${it.message}") }
        }
    }

    /** 点击流内决策/建议卡上的某个选项。 */
    fun selectChoiceOption(cardId: String, option: AgentStreamCoordinator.ChoiceOption) {
        viewModelScope.launch {
            runCatching { repository.resolveMessageCard(cardId, option.id) }
                .onFailure { Logger.w("AgentBotVM", "更新选项卡片状态失败: ${it.message}") }
            val reply = option.prompt.ifBlank { option.label }
            if (reply.isNotBlank()) {
                submit(reply, emptyList(), submittedComposer = null)
            }
        }
    }

    fun dismissCard(cardId: String) {
        viewModelScope.launch {
            runCatching { repository.dismissMessageCard(cardId) }
                .onFailure { Logger.w("AgentBotVM", "收起卡片失败: ${it.message}") }
        }
    }

    /** 向当前 Agent 对话流投递一张主动简报 / 建议行动卡。 */
    fun publishCueBriefing(
        title: String,
        summary: String,
        actions: List<AgentStreamCoordinator.ChoiceOption>,
    ) {
        val sessionId = currentSessionId
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            repository.upsertMessageCard(
                BotMessageCardEntity(
                    cardId = "cue:${java.util.UUID.randomUUID()}",
                    botId = currentBotId,
                    originSessionId = sessionId,
                    cardKind = BotCardKind.CUE_BRIEFING,
                    title = title,
                    subtitle = summary,
                    payloadJson = cardJson.encodeToString(actions),
                ),
            )
        }
    }

    /** 将一条已完成任务一键沉淀成可复用惯例。 */
    fun saveTaskAsRoutine(
        taskId: String,
        title: String = "",
        triggerType: String = RoutineTriggerType.MANUAL,
        triggerExpr: String = "",
    ) {
        viewModelScope.launch {
            val task = repository.getTask(taskId) ?: return@launch
            runCatching {
                routineEngine?.saveFromTask(
                    task = task,
                    title = title,
                    triggerType = triggerType,
                    triggerExpr = triggerExpr,
                )
            }.onFailure { Logger.w("AgentBotVM", "保存任务为惯例失败: ${it.message}") }
        }
    }

    fun createRoutine(
        title: String,
        goalPrompt: String,
        triggerType: String = RoutineTriggerType.MANUAL,
        triggerExpr: String = "",
    ) {
        if (title.isBlank() || goalPrompt.isBlank()) return
        viewModelScope.launch {
            runCatching {
                routineEngine?.createOrUpdate(
                    botId = currentBotId,
                    title = title,
                    goalPrompt = goalPrompt,
                    triggerType = triggerType,
                    triggerExpr = triggerExpr,
                    originSessionId = currentSessionId,
                )
            }.onFailure { Logger.w("AgentBotVM", "创建惯例失败: ${it.message}") }
        }
    }

    fun runRoutineNow(routine: BotRoutineEntity) {
        viewModelScope.launch {
            val session = when (val res = ensureAgentSession(currentBotId)) {
                is SessionResolution.Ready -> res.sessionId
                else -> return@launch
            }
            runCatching { routineEngine?.runNow(routine, session) }
                .onFailure { Logger.w("AgentBotVM", "运行惯例失败: ${it.message}") }
        }
    }

    fun toggleRoutine(routine: BotRoutineEntity, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { routineEngine?.toggleEnabled(routine, enabled) }
                .onFailure { Logger.w("AgentBotVM", "切换惯例开关失败: ${it.message}") }
        }
    }

    fun deleteRoutine(routine: BotRoutineEntity) {
        viewModelScope.launch {
            runCatching { routineEngine?.delete(routine) }
                .onFailure { Logger.w("AgentBotVM", "删除惯例失败: ${it.message}") }
        }
    }

    fun startTeachRecording(title: String = "") {
        teachRecorder?.start(currentBotId, title)
    }

    fun updateTeachTitle(title: String) {
        teachRecorder?.updateTitle(title)
    }

    fun recordTeachStep(action: String, targetApp: String = "") {
        teachRecorder?.recordStep(action, targetApp)
    }

    fun cancelTeachRecording() {
        teachRecorder?.cancel()
    }

    fun finishTeachRecording() {
        val draft = teachRecorder?.finish() ?: return
        viewModelScope.launch {
            runCatching {
                routineEngine?.createOrUpdate(
                    botId = draft.botId.ifBlank { currentBotId },
                    title = draft.title,
                    goalPrompt = draft.goalPrompt,
                    stepsSummary = draft.stepsSummary,
                    triggerType = RoutineTriggerType.MANUAL,
                    originSessionId = currentSessionId,
                )
            }.onFailure { Logger.w("AgentBotVM", "保存示范惯例失败: ${it.message}") }
        }
    }

    fun setModelRoute(role: AgentModelRouter.Role, providerId: String, modelId: String) {
        modelRouter?.setBinding(role, providerId, modelId)
    }

    fun clearModelRoute(role: AgentModelRouter.Role) {
        modelRouter?.clearBinding(role)
    }

    fun toggleChannelForBot(botId: String, channelId: String) {
        squadIdentities?.toggleChannelForBot(botId, channelId)
    }

    fun setBotSpecialty(botId: String, specialty: String) {
        squadIdentities?.setSpecialty(botId, specialty)
    }

    fun refreshChannels() {
        viewModelScope.launch {
            runCatching { channelManager?.refresh() }
                .onFailure { Logger.w("AgentBotVM", "刷新渠道列表失败: ${it.message}") }
        }
    }

    fun clearSandboxLogs() {
        progressBus?.clearSandboxLogs()
    }

    /**
     * 用户在 NEEDS_USER 状态点"继续"。
     *
     * 明确让用户承担"重跑会重复已执行步骤"这件事 —— 不自动做，
     * 因为副作用不可逆。
     */
    fun continueTask(taskId: String) {
        viewModelScope.launch {
            try {
                if (repository.transition(taskId, BotTaskStatusMachine.Event.USER_CONFIRMED_CONTINUE) != null) {
                    scheduler.tick()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w("AgentBotVM", "继续任务失败: ${e.message}", e)
            }
            refreshBoard()
        }
    }

    /** 取消一个任务。 */
    fun cancelTask(taskId: String) {
        viewModelScope.launch {
            runCatching { scheduler.cancel(taskId) }
                .onFailure { Logger.w("AgentBotVM", "取消失败: ${it.message}") }
            refreshBoard()
        }
    }

    fun retryTask(taskId: String) {
        viewModelScope.launch {
            val failed = repository.getTask(taskId)?.takeIf { it.status == BotTaskStatus.FAILED } ?: return@launch
            runCatching {
                repository.createTask(
                    botId = failed.botId,
                    originSessionId = failed.originSessionId,
                    requestMessageId = failed.requestMessageId,
                    goal = failed.goal,
                    acceptanceCriteria = failed.acceptanceCriteria,
                    resourceScope = failed.resourceScope,
                    permissionScopeId = failed.permissionScopeId,
                    priority = failed.priority,
                    dedupeKey = "retry:$taskId:${java.util.UUID.randomUUID()}",
                )
                scheduler.tick()
            }.onFailure {
                Logger.w("AgentBotVM", "重试任务失败: ${it.message}", it)
            }
            refreshBoard()
        }
    }

    fun openTaskDetails(taskId: String) {
        viewModelScope.launch {
            val details = runCatching {
                val task = repository.getTask(taskId) ?: return@runCatching null
                val run = repository.listRuns(taskId).maxByOrNull { it.attempt }
                val fact = run?.let {
                    repository.latestTaskEvent(taskId, it.runId, BotEventKind.REVIEW_READY)
                }?.let { ReviewDecision.decode(it.payloadRef) }
                AgentBotUiState.TaskDetails(
                    taskId = task.taskId,
                    goal = task.goal,
                    acceptanceCriteria = task.acceptanceCriteria,
                    status = task.status,
                    summary = fact?.userVisibleSummary.orEmpty(),
                    findings = fact?.unverifiedItems.orEmpty(),
                    sideEffects = fact?.sideEffects.orEmpty(),
                    candidateRefs = fact?.candidateRefs.orEmpty(),
                )
            }.onFailure {
                Logger.w("AgentBotVM", "读取任务详情失败: ${it.message}", it)
            }.getOrNull()
            _taskDetails.value = details
        }
    }

    fun closeTaskDetails() {
        _taskDetails.value = null
    }

    fun openCandidatePreview(stagedId: String) {
        viewModelScope.launch {
            val candidate = withContext(Dispatchers.IO) {
                resultApplier.readCandidate(stagedId)
            }
            _candidatePreview.value = candidate?.let {
                AgentBotUiState.CandidatePreview(
                    stagedId = stagedId,
                    targetRef = it.targetRef,
                    content = it.content.take(CANDIDATE_PREVIEW_MAX_CHARS),
                    verificationState = it.verificationState,
                    taskRevision = it.taskRevision,
                    truncated = it.content.length > CANDIDATE_PREVIEW_MAX_CHARS,
                )
            }
        }
    }

    fun closeCandidatePreview() {
        _candidatePreview.value = null
    }

    fun retryMessage(messageId: String) {
        val messages = ui.transcript.value
        val index = messages.indexOfFirst { it.messageId == messageId }
        if (index < 0) return
        val selected = messages[index]
        if (selected.role == AgentBotUiState.Role.USER) {
            if (selected.text.isBlank() && selected.imageBase64List.isEmpty()) return
            submit(selected.text, selected.imageBase64List, submittedComposer = null)
            return
        }
        val userSource = messages.take(index).lastOrNull { it.role == AgentBotUiState.Role.USER } ?: return
        if (userSource.text.isBlank() && userSource.imageBase64List.isEmpty()) return
        val botId = currentBotId
        viewModelScope.launch {
            runCatching { sessions.deleteMessage(messageId) }
                .onFailure { Logger.w("AgentBotVM", "重新生成删除旧回复失败: ${it.message}", it) }
            if (currentBotId == botId) {
                ui.replacePublished(ui.transcript.value.filterNot { it.messageId == messageId })
            }
            val sessionId = when (ensureAgentSession(botId)) {
                is SessionResolution.Ready -> currentSessionId
                else -> return@launch
            }
            val rev = runCatching {
                repository.latestUserInputSequence(botId, sessionId)
            }.getOrDefault(0L)
            val identity = io.zer0.muse.data.agentbot.AgentTurnIdentity(
                assistantId = botId,
                sessionId = sessionId,
                requestId = userSource.messageId,
                requestRevision = rev,
            )
            foregroundTurnJob?.cancel()
            foregroundTurnJob = viewModelScope.launch {
                runForegroundTurn(identity, userSource.text, userSource.imageBase64List)
                runTurnOnce(botId, sessionId)
            }
        }
    }

    fun deleteMessage(messageId: String) {
        val sessionId = currentSessionId
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            try {
                sessions.deleteMessage(messageId)
                if (currentSessionId == sessionId) {
                    ui.replacePublished(ui.transcript.value.filterNot { it.messageId == messageId })
                    reloadTranscript()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w("AgentBotVM", "删除消息失败: ${e.message}", e)
                ui.setError(AgentBotError.SAVE_FAILED)
            }
        }
    }

    /** 「本对话内已放行」的工具列表。V26 最后一条：用户必须能看见并收回。 */
    val allowancesFlow: kotlinx.coroutines.flow.Flow<Map<String, Set<String>>> =
        allowances.allowancesFlow

    /** 收回某工具在本对话的允许。 */
    fun revokeAllowance(toolName: String) {
        viewModelScope.launch {
            runCatching { allowances.revoke(currentSessionId, toolName) }
                .onFailure { Logger.w("AgentBotVM", "收回放行失败: ${it.message}") }
            // 收回后立刻重读：不能等下次进页面才更新。
            refreshAllowances()
        }
    }

    /**
     * 当前会话已放行的工具名集合。
     *
     * 用 StateFlow 而不是普通字段：用户点"收回"之后那一行必须立刻消失。
     * 读普通字段的话界面不会重组，用户会以为按钮没生效 —— 然后反复点。
     */
    private val _allowedTools = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val allowedTools: kotlinx.coroutines.flow.StateFlow<Set<String>> = _allowedTools.asStateFlow()

    /**
     * 仍在暂存区的候选（V04：冲突候选留审计）。
     *
     * 用 StateFlow 而不是普通字段：用户丢弃一条后那一行必须立刻消失。
     */
    private val _pendingCandidates =
        kotlinx.coroutines.flow.MutableStateFlow<List<AgentBotUiState.CandidateRow>>(emptyList())
    val pendingCandidates: kotlinx.coroutines.flow.StateFlow<List<AgentBotUiState.CandidateRow>> =
        _pendingCandidates.asStateFlow()

    /**
     * 刷新候选列表。
     *
     * 读失败**不降级成空列表**：结果原样存进 [stagedCandidates]，
     * 页面据此进 Error 并保留上一次清单。空列表会让"读不出来"和
     * "真的没有候选"长得一模一样。
     */
    fun refreshCandidates() {
        stagedCandidates.value = resultApplier.listPendingCandidatesResult()
    }

    /** 用户丢弃某条候选。 */
    fun discardCandidate(stagedId: String) {
        runCatching { resultApplier.discardCandidate(stagedId) }
            .onFailure { Logger.w("AgentBotVM", "丢弃候选失败: ${it.message}") }
        refreshCandidates()
    }

    /** 刷新已放行列表（绑定会话后、每次收回后调）。 */
    fun refreshAllowances() {
        viewModelScope.launch {
            _allowedTools.value = runCatching { allowances.allowedTools(currentSessionId) }
                .onFailure { Logger.w("AgentBotVM", "读取放行列表失败: ${it.message}") }
                .getOrDefault(emptySet())
        }
    }

    /**
     * 从公共消息表重建对话流。
     *
     * ## 为什么必须有这一步
     *
     * transcript 只在内存里累加。进程重启、切走再回来、切 Bot，
     * 用户看到的都是一片空白 —— 而他昨天交代的事和主 Bot 的回复全都不见了。
     *
     * 那些消息其实都在 messages 表里（发布通道写进去的），只是没人读回来。
     *
     * @param limit 取最近多少条。默认 [TRANSCRIPT_DISPLAY_LIMIT]，比模型上下文大得多。
     */
    fun reloadTranscript(limit: Int = TRANSCRIPT_DISPLAY_LIMIT) {
        if (currentSessionId.isBlank()) return
        val sessionAtStart = currentSessionId
        viewModelScope.launch {
            // 两段式：先快速显示最近一小批，再补齐到上限。
            // 具体规则见 [TranscriptRestorer] —— 抽出去是为了能单独测。
            val first = restorer.read(sessionAtStart, TRANSCRIPT_FIRST_PAINT)
            // 读的过程中用户可能又切走了：这批结果不再属于当前会话，丢弃。
            if (currentSessionId != sessionAtStart) return@launch
            ui.replacePublished(first.messages)

            if (limit <= TRANSCRIPT_FIRST_PAINT) {
                _hasOlderTranscript.value = first.messages.size >= limit &&
                    (first.messages.firstOrNull()?.seq ?: 0L) > 1L
                return@launch
            }
            val full = restorer.read(sessionAtStart, limit)
            if (currentSessionId != sessionAtStart) return@launch
            ui.replacePublished(restorer.merge(full.messages, ui.transcript.value))
            _hasOlderTranscript.value = full.messages.size >= limit &&
                (full.messages.firstOrNull()?.seq ?: 0L) > 1L
        }
    }

    fun loadOlderTranscript() {
        val sessionAtStart = currentSessionId
        val first = ui.transcript.value.firstOrNull() ?: return
        if (sessionAtStart.isBlank() || first.seq <= 0L || !_hasOlderTranscript.value ||
            _loadingOlderTranscript.value
        ) {
            return
        }
        viewModelScope.launch {
            _loadingOlderTranscript.value = true
            try {
                val page = restorer.readOlder(sessionAtStart, first.seq, TRANSCRIPT_PAGE_SIZE)
                if (currentSessionId != sessionAtStart) return@launch
                ui.replacePublished(restorer.merge(page.messages, ui.transcript.value))
                _hasOlderTranscript.value = page.messages.size >= TRANSCRIPT_PAGE_SIZE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w("AgentBotVM", "加载更早消息失败: ${e.message}", e)
                ui.setError(AgentBotError.HISTORY_LOAD_FAILED)
            } finally {
                _loadingOlderTranscript.value = false
            }
        }
    }
    /** 刷新任务看板。只放用户需要知道的，不放子 Agent 与工具流水。 */
    /**
     * 看板数据已由 Room Flow 持续观察，这里只推进不来自 Room 的候选暂存区。
     *
     * 保留这个名字是因为界面侧仍在任务动作后调用它；真正的一次性读库
     * 已经删掉 —— 那正是"在 bind 时读死一份、之后不再更新"的来源，
     * 也是把全局审批混进当前页面的入口。
     */
    fun refreshBoard() {
        refreshCandidates()
    }

    /** 审批实体 → 卡片行。浏览时与批准时必须同源，所以两个哈希字段原样透传。 */
    private fun ApprovalRequestEntity.toApprovalRow(task: BotTaskEntity?) = AgentBotUiState.ApprovalRow(
        approvalId = approvalId,
        taskId = taskId,
        toolName = toolName,
        risk = risk,
        argumentsPreview = argumentsRef,
        // 批准时要原样回传这两个值，服务端据此判定参数/资源有没有漂移。
        // 传空会让每次批准都被判 INVALIDATED。
        argumentsHash = argumentsHash,
        resourceVersion = resourceVersion,
        expiresAt = expiresAt,
        resourceScope = task?.resourceScope.orEmpty(),
    )

    private fun StagedArtifactStore.StagedEntry.toCandidateRow() = AgentBotUiState.CandidateRow(
        stagedId = stagedId,
        targetRef = candidate.targetRef,
        taskRevision = candidate.taskRevision,
    )

    /**
     * 任务 → 看板行。
     *
     * 状态文案在这里收敛：用户看到的是"在做""等你批准""需要你决定"，
     * 而不是 QUEUED / WAITING_APPROVAL 这种内部词。
     */
    private fun BotTaskEntity.toRow(
        approvedAwaitingRetry: Boolean,
        steps: List<AgentBotUiState.TaskStepUi> = emptyList(),
        pendingSteerCount: Int = 0,
    ): AgentBotUiState.TaskRow {
        // NEEDS_USER 有两个来源：审批通过（可重跑）、审核要求决策（需处理）。
        // 区分依据：该任务有没有"已结算的批准"且已无待审批。
        val needsUser: AgentBotPrompt? = when (status) {
            BotTaskStatus.NEEDS_USER -> {
                if (approvedAwaitingRetry) {
                    AgentBotPrompt.APPROVED_AWAITING_RETRY
                } else {
                    AgentBotPrompt.NEEDS_DECISION
                }
            }
            BotTaskStatus.UNKNOWN -> AgentBotPrompt.VERIFY_RESULT
            else -> null
        }
        // 操作集由这里显式给出，界面不再靠 status 猜。
        val actions = buildSet {
            if (status == BotTaskStatus.NEEDS_USER && approvedAwaitingRetry) {
                add(AgentBotUiState.TaskAction.CONTINUE)
            }
            if (status == BotTaskStatus.UNKNOWN) add(AgentBotUiState.TaskAction.VERIFY)
            if (status == BotTaskStatus.FAILED) add(AgentBotUiState.TaskAction.RETRY)
            if (status == BotTaskStatus.QUEUED ||
                status == BotTaskStatus.RUNNING ||
                status == BotTaskStatus.WAITING_APPROVAL ||
                status == BotTaskStatus.WAITING_RESOURCE ||
                status == BotTaskStatus.REVIEWING ||
                // NEEDS_USER 也要能取消。
                //
                // 上一轮把"靠 status 猜"改成显式操作集时漏了这一条，
                // 导致"需要你决定"的行上一个按钮都没有 —— 任务会永久停在那里。
                // 用户至少得能把不想要的任务收掉。
                status == BotTaskStatus.NEEDS_USER ||
                // CANCEL_REQUESTED 是"正在取消"，不给按钮：
                // 再点一次取消没有意义，而用户此刻也没别的可做。
                // 显式列出是为了让"这个状态被考虑过"有据可查 ——
                // 漏掉它会让测试失败，而不是悄悄少一个按钮。
                status == BotTaskStatus.CANCEL_REQUESTED
            ) {
                if (status != BotTaskStatus.CANCEL_REQUESTED) {
                    add(AgentBotUiState.TaskAction.CANCEL)
                }
            }
        }
        val latestStepNote = steps.lastOrNull()?.let { step ->
            step.summary.ifBlank { step.displayLabel }
        }.orEmpty()
        return AgentBotUiState.TaskRow(
            taskId = taskId,
            goal = goal,
            status = status,
            note = latestStepNote,
            needsUser = needsUser,
            actions = actions,
            requestMessageId = requestMessageId,
            steps = steps,
            pendingSteerCount = pendingSteerCount,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    companion object {
        private val cardJson = Json { ignoreUnknownKeys = true }
        /** 默认 Bot id。单 Bot 场景下固定值，多 Bot 时由绑定表给出。 */
        const val DEFAULT_BOT_ID = "default"

        /**
         * 必须由用户处理的两种任务状态。
         *
         * WAITING_APPROVAL、NEEDS_USER 与 UNKNOWN 归入 needsAction：它们都需要
         * 用户决策或核验，界面上必须优先于"在做什么"出现。
         */
        private val ACTION_REQUIRED_STATUSES = setOf(
            BotTaskStatus.WAITING_APPROVAL,
            BotTaskStatus.NEEDS_USER,
            BotTaskStatus.UNKNOWN,
        )

        /**
         * 回合上下文条数（喂给模型）。
         *
         * 20 条是折中：够覆盖一段连续对话的指代，又不会让每次前台回合都带着
         * 上千 token 的历史跑 —— 那会把首字延迟推高到用户以为卡住。
         */
        private const val HISTORY_LIMIT = 20

        /**
         * 界面单页条数（更早消息通过显式分页读取）。
         *
         * 与 [HISTORY_LIMIT] 分开是刻意的：两者约束不同。
         *
         *  - 模型上下文要**小**：每轮都带上，token 成本与延迟线性相关；
         *  - 界面按页读取：用户可持续向前翻，不一次性载入长会话。
         */
        private const val TRANSCRIPT_DISPLAY_LIMIT = 50

        private const val TRANSCRIPT_PAGE_SIZE = 50

        private const val CANDIDATE_PREVIEW_MAX_CHARS = 20_000

        /**
         * 切会话时先读一页立刻显示；更早消息由单独的分页动作补齐。
         */
        private const val TRANSCRIPT_FIRST_PAINT = 50
    }
}
