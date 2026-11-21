package io.zer0.muse.ui.agentbot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.common.Logger
import io.zer0.muse.data.agentbot.AgentBotRepository
import io.zer0.muse.data.agentbot.AgentBotError
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
import io.zer0.muse.data.agentbot.TranscriptRestorer
import io.zer0.muse.data.agentbot.ConversationToolAllowanceStore
import io.zer0.muse.data.agentbot.ForegroundCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import io.zer0.muse.data.agentbot.PublicationService
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
) : ViewModel() {

    private val ui = AgentBotUiState()

    val composer: StateFlow<AgentBotUiState.Composer> = ui.composer
    val transcript: StateFlow<List<AgentBotUiState.PublishedMessage>> = ui.transcript
    val taskBoard: StateFlow<AgentBotUiState.TaskBoard> = ui.taskBoard
    val busy: StateFlow<AgentBotUiState.Busy> = ui.busy

    /** 当前绑定的 Bot。切 Bot 时草稿不串。 */
    /** 当前前台回合的 Job。stopReply() 只取消它。 */
    private var foregroundTurnJob: Job? = null

    private var currentBotId: String = DEFAULT_BOT_ID
    private var currentSessionId: String = ""

    /**
     * 绑定当前 Bot 与它的 canonical 会话。
     *
     * 会话 id 由宿主传入，不从"当前在看哪个会话"推断 —— 那正是跨会话污染的来源。
     */
    fun bind(botId: String, originSessionId: String) {
        currentBotId = botId.ifBlank { DEFAULT_BOT_ID }
        currentSessionId = originSessionId
        ui.setComposerBot(currentBotId)
        refreshBoard()
        refreshAllowances()
        refreshCandidates()
        // 对话流也必须从库里重建：只在内存里累加的话，重启/切会话后是空白。
        reloadTranscript()
    }

    /**
     * 确保有可用的 Agent 会话；没有就创建一个并绑定。
     *
     * @return 会话 id；创建失败返回 null（调用方报错，绝不静默）。
     */
    private suspend fun ensureAgentSession(): String? {
        if (currentSessionId.isNotBlank()) return currentSessionId
        val created = runCatching { sessions.createAgentSession(currentBotId) }
            .onFailure { Logger.e("AgentBotVM", "自动创建 Agent 会话失败", it) }
            .getOrNull() ?: return null
        currentSessionId = created
        Logger.i("AgentBotVM", "已自动创建 Agent 会话: $created")
        return created
    }
    fun onComposerTextChange(text: String) {
        ui.setComposerText(text)
    }

    /**
     * 发送。
     *
     * 关键点：**永不因为"正在生成"而拒绝**。后台任务在跑的时候，
     * 用户必须还能说话 —— 那是这个功能存在的理由。
     */
    fun send() {
        val text = ui.composer.value.text
        if (text.isBlank()) return
        viewModelScope.launch {
            ui.setSending(true)
            ui.setError(null)
            // 无会话时自动创建 —— 沿用旧 Agent 聊天的既有行为（v1.28）。
            //
            // 真机验收抓到的缺陷：新装应用进 Agent Tab 打字发送，
            // 原来直接报「还没有可用的会话」。会话不是用户该操心的东西 ——
            // 第一次说话时应用就该替他把这个 Bot 的 canonical 会话建出来。
            val sessionId = ensureAgentSession()
            if (sessionId == null) {
                ui.setSending(false)
                ui.setError(AgentBotError.NO_SESSION)
                return@launch
            }
            val accepted = facade.acceptUserInput(
                botId = currentBotId,
                originSessionId = sessionId,
                text = text,
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
            runCatching {
                sessions.appendMessage(
                    sessionId,
                    io.zer0.ai.core.UIMessage(
                        role = io.zer0.ai.core.MessageRole.USER,
                        content = text,
                    ),
                )
            }.onFailure {
                // 消息落库失败不回滚事件：事件已接纳，回合照常走；
                // 但要在日志里留痕，否则"消息去哪了"无从排查。
                Logger.w("AgentBotVM", "用户消息落库失败（事件已接纳）: ${it.message}")
            }
            ui.appendPublished(
                AgentBotUiState.PublishedMessage(
                    messageId = "user-${accepted.eventId}",
                    role = AgentBotUiState.Role.USER,
                    text = text,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            ui.setComposerText("")
            // 关键：先放开输入再调模型。
            //
            // 旧实现在同一个协程里等模型返回，"发送中"会一直亮到模型答完 ——
            // 用户看到的仍然是"我在等你说话"，而不是"你可以继续"。那正是这个
            // 功能要消除的等待感。所以回合丢到独立 Job，输入立刻可用。
            ui.setSending(false)
            // 事件 id 即这次接纳的凭据；任务靠它回溯到触发它的那条消息。
            val acceptedMessageId = accepted.batchId
            // 记下这一轮的 Job：stopReply() 只取消它，不碰后台任务。
            foregroundTurnJob = viewModelScope.launch {
                runForegroundTurn(text, acceptedMessageId)
                runTurnOnce()
            }
        }
    }

    /**
     * 跑一个前台回合：让主 Bot 说一句，必要时派工。
     *
     * 失败时不弹错误 —— 用户的输入已经落盘（facade 保证），
     * 只是这一轮没回复。给错误横幅会让用户以为消息丢了。
     */
    private suspend fun runForegroundTurn(userText: String, acceptedMessageId: String) {
        // 多轮上下文：取该会话最近若干条。不传空列表 ——
        // 主 Bot 只看到"这一句"时无法理解"那个""再来一次"这类指代。
        val history = runCatching {
            sessions.getRecentMessages(currentSessionId, HISTORY_LIMIT)
        }.onFailure {
            // 读不到历史不该让整轮失败：退化成单轮仍能回答，只是少了上下文。
            Logger.w("AgentBotVM", "读取历史失败，退化为单轮: ${it.message}")
        }.getOrDefault(emptyList())
        val turn = coordinator.runTurn(
            botId = currentBotId,
            originSessionId = currentSessionId,
            history = history,
            userText = userText,
            requestRevision = 0L,
        ) ?: return
        // V15: 模型始终没调 bot_say 时，明确告诉用户"这条没发出去"，并保留重试入口。
        // 不拿模型正文兜底 —— 那会伪造出一条用户以为是回复的消息。
        if (turn.missingBotSay) {
            ui.setError(AgentBotError.MISSING_BOT_SAY)
            return
        }
        // 清单 F4：Provider 静默返回纯文本（两次都没有 tool_calls）时，
        // 必须明确告知"当前 Provider 不支持工具调用，主 Bot 无法派工"。
        //
        // 不告诉的话，用户只会看到主 Bot 聊得很正常但从不做事，
        // 而原因（换一个 Provider 就能好）完全无从得知。
        if (turn.providerToolBlind) {
            ui.setError(AgentBotError.PROVIDER_TOOL_BLIND)
        }
        if (turn.say.isNotBlank()) {
            ui.appendPublished(
                AgentBotUiState.PublishedMessage(
                    messageId = "fg-" + System.nanoTime(),
                    role = AgentBotUiState.Role.BOT,
                    text = turn.say,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        if (turn.shouldDispatch) {
            runCatching {
                repository.createTask(
                    botId = currentBotId,
                    originSessionId = currentSessionId,
                    // 记住是哪条消息触发的：审核阶段要能回溯"用户到底说了什么"。
                    requestMessageId = acceptedMessageId,
                    goal = turn.dispatchGoal,
                    acceptanceCriteria = turn.acceptance,
                )
            }.onFailure { Logger.w("AgentBotVM", "派工失败: ${it.message}") }
            refreshBoard()
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
     * 现在只消费**这一层真的处理过**的事件：
     *
     *  - 用户输入：接受即可（派工由 Coordinator 负责，那是后续动作，不影响这条已收下）
     *  - 结果类：必须经发布通道提交后才能消费。发布还没接，所以返回 false 保留待处理
     */
    suspend fun runTurnOnce() {
        val result = inbox.runTurn(currentBotId) { event ->
            when (event.kind) {
                BotEventKind.USER_INPUT_RECEIVED -> true
                BotEventKind.TASK_ACCEPTED -> true
                BotEventKind.TASK_PROGRESSED -> true
                // 结果类事件保留待处理。
                //
                // 返回 false 会让收件箱留下它、下轮再试。这比"吞掉"好：
                // 吞掉是永久丢失，留下只是延迟 —— 等发布通道接上就能处理。
                BotEventKind.TASK_RESULT_READY,
                BotEventKind.TASK_FAILED,
                -> {
                    Logger.d(
                        "AgentBotVM",
                        "结果事件暂不消费（发布通道未接）: ${event.kind} ${event.eventId}",
                    )
                    false
                }
                // 未识别的类型同样保留：宁可堆积也不静默丢弃。
                else -> false
            }
        }
        if (result.consumed > 0 || result.hasBacklog) refreshBoard()
    }

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
            when (val outcome = approvals.approve(approvalId, argumentsHash, resourceVersion)) {
                is ApprovalService.Outcome.Settled -> Unit
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
        foregroundTurnJob?.cancel()
        foregroundTurnJob = null
        ui.setSending(false)
        ui.setTyping(false)
    }

    /**
     * 用户在 NEEDS_USER 状态点"继续"。
     *
     * 明确让用户承担"重跑会重复已执行步骤"这件事 —— 不自动做，
     * 因为副作用不可逆。
     */
    fun continueTask(taskId: String) {
        viewModelScope.launch {
            runCatching {
                repository.transition(taskId, BotTaskStatusMachine.Event.USER_CONFIRMED_CONTINUE)
            }.onFailure { Logger.w("AgentBotVM", "继续任务失败: ${it.message}") }
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

    /** 刷新候选列表。 */
    fun refreshCandidates() {
        _pendingCandidates.value = runCatching {
            resultApplier.listPendingCandidates().map { entry ->
                AgentBotUiState.CandidateRow(
                    stagedId = entry.stagedId,
                    targetRef = entry.candidate.targetRef,
                    taskRevision = entry.candidate.taskRevision,
                )
            }
        }.onFailure {
            Logger.w("AgentBotVM", "读取候选列表失败: ${it.message}")
        }.getOrDefault(emptyList())
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

            if (limit <= TRANSCRIPT_FIRST_PAINT) return@launch
            val full = restorer.read(sessionAtStart, limit)
            if (currentSessionId != sessionAtStart) return@launch
            ui.replacePublished(restorer.merge(full.messages, ui.transcript.value))
        }
    }
    /** 刷新任务看板。只放用户需要知道的，不放子 Agent 与工具流水。 */
    fun refreshBoard() {
        viewModelScope.launch {
            val tasks = runCatching { repository.listTasks(currentBotId) }.getOrDefault(emptyList())
            val pending = runCatching { repository.pendingApprovals() }.getOrDefault(emptyList())
            // NEEDS_USER 的两种来源靠"该任务是否已有结算过的批准"区分。
            // 这里一次性算出，避免在 toRow 里做挂起调用。
            val approvedTaskIds = tasks.filter { it.status == BotTaskStatus.NEEDS_USER }
                .filter { task ->
                    runCatching {
                        val approvals = repository.listApprovals(task.taskId)
                        approvals.isNotEmpty() && approvals.none { it.status == ApprovalStatus.PENDING }
                    }.getOrDefault(false)
                }
                .map { it.taskId }
                .toSet()
            ui.setTaskBoard(
                AgentBotUiState.TaskBoard(
                    tasks = tasks.map { it.toRow(approvedAwaitingRetry = it.taskId in approvedTaskIds) },
                    approvals = pending.map {
                        AgentBotUiState.ApprovalRow(
                            approvalId = it.approvalId,
                            taskId = it.taskId,
                            toolName = it.toolName,
                            risk = it.risk,
                            argumentsPreview = it.argumentsRef,
                            // 批准时要原样回传这两个值，服务端据此判定参数/资源有没有漂移。
                            // 传空会让每次批准都被判 INVALIDATED。
                            argumentsHash = it.argumentsHash,
                            resourceVersion = it.resourceVersion,
                            expiresAt = it.expiresAt,
                        )
                    },
                ),
            )
        }
    }

    /**
     * 任务 → 看板行。
     *
     * 状态文案在这里收敛：用户看到的是"在做""等你批准""需要你决定"，
     * 而不是 QUEUED / WAITING_APPROVAL 这种内部词。
     */
    private fun BotTaskEntity.toRow(approvedAwaitingRetry: Boolean): AgentBotUiState.TaskRow {
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
        return AgentBotUiState.TaskRow(
            taskId = taskId,
            goal = goal,
            status = status,
            note = "",
            needsUser = needsUser,
            actions = actions,
        )
    }

    companion object {
        /** 默认 Bot id。单 Bot 场景下固定值，多 Bot 时由绑定表给出。 */
        const val DEFAULT_BOT_ID = "default"

        /**
         * 回合上下文条数（喂给模型）。
         *
         * 20 条是折中：够覆盖一段连续对话的指代，又不会让每次前台回合都带着
         * 上千 token 的历史跑 —— 那会把首字延迟推高到用户以为卡住。
         */
        private const val HISTORY_LIMIT = 20

        /**
         * 界面显示条数（从库里读回）。
         *
         * 与 [HISTORY_LIMIT] 分开是刻意的：两者约束不同。
         *
         *  - 模型上下文要**小**：每轮都带上，token 成本与延迟线性相关；
         *  - 界面显示要**大**：用户往上翻是要看历史，看不到就是"消息丢了"。
         *
         * 共用一个常量时，20 条这个"够覆盖指代"的值被同时当成"够看完对话"，
         * 结果聊过 20 轮之后往上翻就断头。
         */
        private const val TRANSCRIPT_DISPLAY_LIMIT = 200

        /**
         * 首屏条数。
         *
         * 切会话时先读这么多条立刻显示，再在后台补齐到 [TRANSCRIPT_DISPLAY_LIMIT]。
         * 50 条够填满一屏还有余量，读起来不会让用户等待。
         */
        private const val TRANSCRIPT_FIRST_PAINT = 50
    }
}