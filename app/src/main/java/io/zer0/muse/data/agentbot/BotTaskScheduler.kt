package io.zer0.muse.data.agentbot

import io.zer0.common.Logger
import io.zer0.muse.runtime.RuntimeBridge
import io.zer0.muse.runtime.RuntimeMissing
import io.zer0.muse.tools.SubagentCancellation
import io.zer0.muse.tools.SubagentRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import android.content.Context

/**
 * 隐藏任务执行器 —— 把"用户交代的一件事"跑成真实产物。
 *
 * ## 与旧链路的边界
 *
 * 旧 subagent_run 是同步阻塞的：调用方等结果。中枢不能这样 ——
 * 主 Bot 接单后必须立刻能继续聊天。这里只做三件事：领任务、在独立 Job 里跑、
 * 把结果写成事件。**不直接发消息、不发通知、不读当前 UI 会话**。
 *
 * ## 为什么 Job 自己管
 *
 * 旧 SessionRuntime 按 sessionId 管 Job，而中枢的 Job 与"用户正在看哪个会话"无关。
 * 按 runId 登记后，切换 Tab / 切会话都不会误杀后台任务；取消必须走显式命令。
 */
class BotTaskScheduler(
    private val repository: AgentBotRepository,
    private val runner: SubagentRunner,
    private val scope: CoroutineScope,
    /** 配额判定。并发/时长/小时上限都在这里，tick 只消费它的结论。 */
    private val governor: BotResourceGovernor = BotResourceGovernor(),
    /** 单进程同时执行的任务数上限。超过则留在 QUEUED，由下一轮 tick 领取。 */
    private val maxConcurrentRuns: Int = 2,
    /** 拉起前台保活服务。空实现便于纯 JVM 测试，App 侧注入真实启动。 */
    private val keepAlive: (android.content.Context) -> Unit = {},
    /**
     * 候选暂存区。任务产出先进这里，审核通过且版本最新才应用到目标。
     *
     * 为 null 时跳过暂存（纯 JVM 测试）。App 侧由 Koin 注入真实实例。
     */
    private val staging: StagedArtifactStore? = null,
    /**
     * 审核 → 应用 的执行者。
     *
     * 为 null 时只发结果事件、不自动应用（纯 JVM 测试 / 只读场景）。
     */
    private val applier: BotResultApplier? = null,
    /** 应用上下文。为 null 时跳过保活（单测环境）。 */
    private val context: android.content.Context? = null,
    /**
     * 工具注册表，用于按风险分级判定副作用类别。
     *
     * 为 null 时所有工具都按 NO_BLIND_REPLAY 处理 —— 保守但会让只读工具也判 PARTIAL。
     */
    private val toolRegistryRef: io.zer0.muse.tools.ToolRegistry? = null,
    /**
     * 工作区 IO。用于清理孤儿临时文件。
     *
     * 为 null 时跳过清理（纯 JVM 测试）。
     */
    private val workspaceIO: WorkspaceArtifactIO? = null,
    private val approvalService: ApprovalService? = null,
    private val workspaceManager: io.zer0.muse.workspace.WorkspaceManager? = null,
    private val progressBus: LiveMissionProgressBus? = null,
    private val modelRouter: AgentModelRouter? = null,
    private val configStore: io.zer0.ai.ProviderConfigStore? = null,
    private val annotationService: BotAnnotationService? = null,
) {

    /** runId → Job。取消、诊断、保活都读它。 */
    private val runningJobs = ConcurrentHashMap<String, Job>()

    /** runId → 取消句柄。Runner 在工具循环间断点检查它。 */
    private val cancellations = ConcurrentHashMap<String, SubagentCancellation>()

    /** 派单即时唤醒信号：让新任务 0ms 起跑，不再等待 5 秒轮询间隔。 */
    private val wakeupChannel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** 当前在跑的任务数（诊断用）。 */
    val runningCount: Int get() = runningJobs.size

    /** 驱动循环的 Job。null 表示还没启动。 */
    private var driveJob: Job? = null

    /** 请求调度循环立刻执行下一轮 tick。 */
    fun requestImmediateTick() {
        wakeupChannel.trySend(Unit)
    }

    /**
     * 向运行中或排队中的任务实时注入补充约束（活体插话）。
     *
     * 运行中任务直接热注入到下一轮推理上下文，避免无意义中断重跑；
     * 事件仍完整落入 `bot_events` 以备审计与回溯。
     */
    suspend fun steerTask(taskId: String, instruction: String): Boolean {
        val cleaned = instruction.trim()
        if (taskId.isBlank() || cleaned.isBlank()) return false
        val task = repository.getTask(taskId) ?: return false
        if (BotTaskStatus.isTerminal(task.status)) return false
        repository.appendEvent(
            botId = task.botId,
            originSessionId = task.originSessionId,
            taskId = task.taskId,
            runId = "",
            kind = BotEventKind.TASK_STEERED,
            dedupeKey = "task-steered:$taskId:${System.nanoTime()}",
            payloadRef = cleaned.take(500),
        )
        progressBus?.enqueueSteer(taskId, cleaned)
        requestImmediateTick()
        return true
    }

    /**
     * 启动驱动循环。
     *
     * 之前 `tick()` 与 `sweepOverdue()` 都实现完整但**没有调用方** ——
     * 队列里的任务永远不会被领走，跑太久的也不会被发现。这个循环把它们接上。
     *
     * 幂等：重复调用不会起第二个循环。
     *
     * @param intervalMs 轮询间隔。5 秒是折中：用户派工后最多等这么久就开始做，
     *   而空转开销可以忽略（一次 Room 计数查询）。
     */
    fun start(intervalMs: Long = DEFAULT_DRIVE_INTERVAL_MS) {
        if (driveJob?.isActive == true) return
        driveJob = scope.launch {
            while (isActive) {
                runCatching { tick() }
                    .onFailure { Logger.w("BotTaskScheduler", "调度轮询失败: ${it.message}") }
                runCatching { sweepOverdue() }
                    .onFailure { Logger.w("BotTaskScheduler", "超时巡检失败: ${it.message}") }
                runCatching { approvalService?.sweepExpired() ?: repository.expireApprovals() }
                    .onFailure { Logger.w("BotTaskScheduler", "审批过期扫描失败: ${it.message}") }
                // 清理写入中断留下的临时文件。用户看不到它们，但会一直占空间。
                runCatching { workspaceIO?.sweepOrphanTemps() }
                    .onFailure { Logger.w("BotTaskScheduler", "临时文件清理失败: ${it.message}") }
                // 清理已消费的事件。
                //
                // 审查前 `pruneEvents` 从未被调用 —— 已消费的事件永久留在库里。
                // 事件是中枢里写入最频繁的表（每轮对话、每个工具步骤都会追加），
                // 不清的话它会无界增长，最终拖慢每一次 `pendingEvents` 查询。
                //
                // 保留期由 `EVENT_RETENTION_MS`（7 天）决定，不是"立即删"：
                // 出问题时要能回看最近发生过什么。
                runCatching { repository.pruneEvents() }
                    .onFailure { Logger.w("BotTaskScheduler", "事件清理失败: ${it.message}") }
                // 候选引用事件：文件还在就不能删（页面靠它找到候选），
                // 只有被应用/丢弃、文件已经不在了的才随保留期收走。
                // 文件存不存在只有持有暂存区的一方判断得了，所以走 applier。
                runCatching { applier?.pruneSettledStagedEvents() }
                    .onFailure { Logger.w("BotTaskScheduler", "候选引用清理失败: ${it.message}") }
                kotlinx.coroutines.withTimeoutOrNull(intervalMs) {
                    wakeupChannel.receive()
                }
            }
        }
    }

    /** 停止驱动循环（测试与"关闭中枢"时用）。 */
    fun stop() {
        driveJob?.cancel()
        driveJob = null
    }

    /**
     * 领一轮任务并异步执行。
     *
     * 只领 QUEUED：等待资源/审批的任务不占执行位，由各自的条件满足后重新入队。
     * 并发已满时什么都不做 —— 让任务留在队列里，比排队等锁更容易对账。
     */
    suspend fun tick() {
        // V17: 备份恢复期间库文件正在被替换，此时写库会产生在途写丢失，
        // 或让新库 WAL 与已删附属文件不一致。整轮跳过，恢复结束后自然继续。
        if (io.zer0.common.ProcessWriteGate.restoring) {
            Logger.d("BotTaskScheduler", "恢复窗口内，跳过本轮调度")
            return
        }
        val capacity = maxConcurrentRuns - runningJobs.size
        if (capacity <= 0) {
            // 并发满了：把排在最前的任务显式转入 WAITING_RESOURCE，
            // 而不是让它一直躺在 QUEUED 里无声无息。
            //
            // 审查 P2-12：`WAITING_RESOURCE` 与 `WAIT_RESOURCE` / `RESUME_FROM_RESOURCE`
            // 此前**没有任何生产代码发出** —— 那是个进了就出不来的死状态。
            // 界面文案、`isBlocked`、对账逻辑都为它写好了，只是没人真的进去。
            //
            // 接上之后：并发满 → 最前的任务进 WAITING_RESOURCE → 有空位时恢复。
            // 用户看到的从"排队中（其实在等什么不知道）"变成"在等资源"。
            parkHeadForResource();
            return;
        }
        // 配额先判：并发满就整轮不领，触到小时上限就直接停 ——
        // 后者不是"等一会儿"，继续领只会把用户配额吃光。
        when (val verdict = governor.admit(runningJobs.size)) {
            is BotResourceGovernor.Verdict.Go -> Unit
            is BotResourceGovernor.Verdict.Queue -> {
                Logger.d("BotTaskScheduler", "配额满，本轮不领: ${verdict.reason}")
                return
            }
            is BotResourceGovernor.Verdict.Reject -> {
                Logger.w("BotTaskScheduler", "配额拒绝，本轮不领: ${verdict.reason}")
                return
            }
        }
        val candidates = repository.claimCandidates(capacity)
        candidates.forEach { task ->
            // 先转移再起 Job：转移失败说明它已被别人领走，不必再开一份。
            val claimed = repository.transition(task.taskId, BotTaskStatusMachine.Event.START)
            if (claimed == null) {
                Logger.d("BotTaskScheduler", "任务已被领走，跳过: ${task.taskId}")
                return@forEach
            }
            val run = repository.openRun(task.taskId)
            if (run == null) {
                Logger.w("BotTaskScheduler", "开轮失败，任务转 UNKNOWN: ${task.taskId}")
                repository.transition(task.taskId, BotTaskStatusMachine.Event.MARK_UNKNOWN)
                return@forEach
            }
            governor.recordDispatch()
            launchRun(claimed, run)
            // 有活干了就把进程提到前台：否则用户锁屏后 Doze 会冻住任务，
            // 而用户以为它在做 —— 比"做不了"更糟。
            context?.let { ctx ->
                runCatching { keepAlive(ctx) }
                    .onFailure { Logger.w("BotTaskScheduler", "拉起保活服务失败: ${it.message}") }
            }
        }
    }

    suspend fun resumeRuntimeMissingTasks(): Int {
        if (!io.zer0.muse.feature.FeatureFlags.AGENT_BOT || io.zer0.common.ProcessWriteGate.restoring) return 0
        val appContext = context ?: return 0
        val runtimeReady = RuntimeBridge.isReady(appContext)
        if (!runtimeReady) return 0
        var resumed = 0
        repository.parkedForResource(MAX_PARKED).forEach { task ->
            val latestRun = repository.listRuns(task.taskId).firstOrNull()
            if (latestRun != null && RuntimeAgentResumePolicy.shouldResume(task, latestRun, runtimeReady)) {
                if (repository.transition(task.taskId, BotTaskStatusMachine.Event.RESUME_FROM_RESOURCE) != null) {
                    resumed++
                }
            }
        }
        if (resumed > 0) tick()
        return resumed
    }

    /**
     * 在独立 Job 里跑一轮。
     *
     * 身份由宿主构造并校验；校验失败不进 Runner —— 缺 originAgentSessionId 时
     * 宁可把任务标 UNKNOWN，也不能拿当前会话兜底。
     */
    private fun launchRun(task: BotTaskEntity, run: TaskRunEntity) {
        val executionScope = ExecutionScope(
            botId = task.botId,
            assistantId = task.botId,
            originAgentSessionId = task.originSessionId,
            taskId = task.taskId,
            runId = run.runId,
            attempt = run.attempt,
            taskRevision = run.taskRevision,
            requestMessageId = task.requestMessageId,
            permissionScopeId = task.permissionScopeId,
            resourceScope = task.resourceScope,
            threadId = run.threadId,
            generationId = run.fenceToken,
        )
        val invalid = executionScope.validate()
        if (invalid != null) {
            Logger.e("BotTaskScheduler", "执行身份不完整（$invalid），任务转 UNKNOWN: ${task.taskId}")
            scope.launch {
                repository.closeRun(run.runId, TaskRunStatus.FAILED, "invalid_scope")
                repository.transition(task.taskId, BotTaskStatusMachine.Event.MARK_UNKNOWN)
                repository.appendEvent(
                    botId = task.botId,
                    originSessionId = task.originSessionId,
                    taskId = task.taskId,
                    runId = run.runId,
                    kind = BotEventKind.TASK_FAILED,
                    dedupeKey = "task-failed:${task.taskId}:${run.runId}",
                )
            }
            return
        }

        // 步骤序号在本轮内单调递增。用局部变量而不是成员：
        // 两个 run 并行时各自独立计数，不会互相干扰。
        var stepCounter = 0
        val stagedArtifactRefs = mutableListOf<String>()
        val stagedArtifactSteps = mutableSetOf<String>()
        val stagedArtifactBaseHashes = mutableMapOf<String, String>()
        var stagedArtifactsTransferred = false
        val cancellation = SubagentCancellation()
        cancellations[run.runId] = cancellation
        // 先占位再启动。
        //
        // 审查 P2-13：原来是 `scope.launch { ... }` 拿到 Job 后才 `runningJobs[runId] = job`。
        // 如果协程在赋值前就跑完，`finally` 会先 `remove` 一个还不存在的 key，
        // 随后写入的是**已完成的 Job** —— 僵尸条目。
        //
        // `runningCount` 用 `runningJobs.size` 算容量，僵尸条目会让并发额度永久 -1。
        //
        // `CoroutineStart.LAZY` + 先 put 再 start：保证"表里有它"与"它在跑"同时成立。
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val routedBackgroundModel = if (modelRouter != null && configStore != null) {
                    runCatching { modelRouter.resolve(AgentModelRouter.Role.BACKGROUND, configStore) }
                        .onFailure { Logger.w("BotTaskScheduler", "解析后台模型路由失败: ${it.message}") }
                        .getOrNull()
                } else {
                    null
                }
                val stepLabels = mutableMapOf<String, String>()
                // 走 background 路径：只占非预留槽位，前台永远至少有一个槽可用。
                // 旧 subagent_run / subagent_task 不传这个标志，行为不变（计划 V25）。
                val result = runner.run(
                    params = SubagentRunner.Params(
                        task = task.goal,
                        contextText = buildContext(task),
                        targetPaths = parsePaths(task.resourceScope),
                        // 高风险写入可被请求，但仍会逐次创建持久审批。
                        access = "full",
                        threadId = run.threadId.ifBlank { null },
                        persistSession = true,
                        background = true,
                        modelOverride = routedBackgroundModel,
                        pollSteeringInstructions = { progressBus?.drainSteer(task.taskId).orEmpty() },
                        toolExecutionContext = io.zer0.muse.tools.ToolExecutionContext(
                            scope = "main",
                            spaceId = "default",
                            assistantId = task.botId,
                            sessionId = task.originSessionId,
                            resourceScope = task.resourceScope,
                            isSubagentContext = true,
                            onRuntimeMissing = { throw RuntimeMissing.RequiresInstallException() },
                            stageWorkspaceArtifact = { stepId, targetPath, content, baseContent ->
                                if (staging == null) {
                                    null
                                } else {
                                    val candidate = StagedArtifactStore.Candidate(
                                        targetRef = targetPath,
                                        content = content,
                                        baseHash = StagedArtifactStore.hashSnapshot(baseContent),
                                        taskRevision = run.taskRevision,
                                        sourceRunId = run.runId,
                                        hasBaseSnapshot = true,
                                        baseContent = baseContent,
                                    )
                                    val artifact = staging.stageArtifact(candidate)
                                    stagedArtifactRefs += artifact.ref
                                    stagedArtifactSteps += stepId
                                    stagedArtifactBaseHashes[stepId] = candidate.baseHash
                                    artifact.ref
                                }
                            },
                        ),
                        // 每次工具执行都记进步骤账本。
                        //
                        // 这是 V23 的数据来源：只有真的记了步骤，"哪些操作已发生"
                        // 才有据可查。不记的话审核永远看到空表，副作用永远汇报不出来。
                        //
                        // ## baseHash 必须在这里读，不能留空
                        //
                        // V13 要求"应用产物时不覆盖用户在这期间的修改"。那条防线靠
                        // baseHash 比对：记下动手前的 hash，应用前再比一次。
                        //
                        // 此前两处 recordStep 都写 `baseHash = ""`，于是整条链上
                        // 所有 baseHash 都是空，`StagedArtifactStore.apply` 的冲突检查
                        // 被 `isNotBlank()` 短路跳过 —— **无条件覆盖用户文件**。
                        //
                        // 这里在工具执行**之前**读一次目标，把当时的内容指纹记下来。
                        onToolStarted = { toolName, argsJson ->
                            runCatching {
                                val index = stepCounter++
                                val label = describeToolStep(toolName, argsJson)
                                val step = repository.recordStep(
                                    runId = run.runId,
                                    stepIndex = index,
                                    toolName = toolName,
                                    argumentsHash = StagedArtifactStore.hashOf(argsJson),
                                    baseHash = baselineHashFor(
                                        toolTargetPath(toolName, argsJson, task.resourceScope),
                                        task.originSessionId,
                                    ),
                                    approvalId = "",
                                    // 按工具风险分级，而不是一律保守。
                                    //
                                    // 全标 NO_BLIND_REPLAY 会让每个任务都判 PARTIAL ——
                                    // 读文件、查资料这类只读操作也会被算成"已发生且无法回滚"，
                                    // 用户看到满屏"只完成了一部分"，而实际上可能全做完了。
                                    // 过度保守和过度乐观一样有害。
                                    sideEffect = classifySideEffect(toolName),
                                    displayLabel = label,
                                    assigneeBotId = task.botId,
                                )
                                stepLabels[step.stepId] = label
                                progressBus?.onStepStarted(
                                    LiveMissionProgressBus.LiveStep(
                                        stepId = step.stepId,
                                        taskId = task.taskId,
                                        stepIndex = index,
                                        toolName = toolName,
                                        displayLabel = label,
                                        status = StepExecutionStatus.RUNNING,
                                        assigneeBotId = task.botId,
                                        startedAt = step.startedAt,
                                    ),
                                )
                                step.stepId
                            }.onFailure {
                                // 记账失败不能只是记一条日志。
                                //
                                // 审查 P0-3：吞掉之后账本变成"成功但为空"，审核看不到
                                // 副作用，判 REWORK，自动回队列从头重跑 —— 已发消息、
                                // 已改文件都会再来一遍。
                                //
                                // 用 error 级并带上工具名：这条日志是排查
                                // "为什么这次重跑了"的唯一线索。
                                Logger.e(
                                    "BotTaskScheduler",
                                    "记录步骤失败，账本将不完整（可能导致误判返工）: $toolName ${it.message}",
                                    it,
                                )
                                // 返回哨兵值：让 Runner 中止本轮，而不是带着不完整的账本继续。
                                //
                                // 审查 P0-3：只记日志的话，账本会变成"成功但为空"，
                                // 审核看不到副作用 → 判 REWORK → 自动回队列 → 重复执行不可逆操作。
                                SubagentRunner.STEP_LEDGER_FAILED
                            }.getOrDefault(SubagentRunner.STEP_LEDGER_FAILED)
                        },
                        // 中枢的审批是**持久化**的，不是阻塞等待。
                        //
                        // 现有 ToolApprovalRouter 是同步桥接：子 Agent 挂在那里等 UI 返回，
                        // 超时即拒绝。中枢不能这样 —— 用户可能几小时后才看手机，
                        // 那时任务早被"超时拒绝"了，而用户从未见过卡片。
                        //
                        // 所以这里建一张持久审批卡，把任务转 WAITING_APPROVAL 并结束本轮。
                        // 用户批准后由 BotResultApplier / 后续 run 继续。
                        onApprovalRequired = { toolName, toolCallId, argsJson, stepId ->
                            val argumentsHash = StagedArtifactStore.hashOf(argsJson)
                            val resourceVersion = baselineHashFor(
                                toolTargetPath(toolName, argsJson, task.resourceScope),
                                task.originSessionId,
                            )
                            val sideEffect = classifySideEffect(toolName)
                            val approved = try {
                                repository.consumeApprovedApproval(
                                    taskId = task.taskId,
                                    runId = run.runId,
                                    taskRevision = run.taskRevision,
                                    stepId = stepId,
                                    toolName = toolName,
                                    argumentsHash = argumentsHash,
                                    resourceVersion = resourceVersion,
                                    sideEffect = sideEffect,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Logger.w("BotTaskScheduler", "读取已批准步骤失败: ${e.message}", e)
                                null
                            }
                            if (approved != null) {
                                SubagentRunner.ApprovalRequestResult.Approved(approved.approvalId)
                            } else {
                                val approval = try {
                                    repository.requestApproval(
                                        taskId = task.taskId,
                                        runId = run.runId,
                                        taskRevision = run.taskRevision,
                                        stepId = stepId,
                                        toolCallId = toolCallId,
                                        toolName = toolName,
                                        arguments = argsJson,
                                        argumentsHash = argumentsHash,
                                        baseHash = resourceVersion,
                                        sideEffect = sideEffect,
                                        risk = "NORMAL",
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Logger.w("BotTaskScheduler", "建审批卡失败: ${e.message}", e)
                                    null
                                }
                                when {
                                    approval == null -> SubagentRunner.ApprovalRequestResult.Failed(
                                        "审批请求没有保存，任务状态或版本已变化",
                                    )
                                    else -> SubagentRunner.ApprovalRequestResult.Created(approval.approvalId)
                                }
                            }
                        },
                        onToolFinished = { stepId, success ->
                            if (stepId.isNotBlank()) {
                                val stepStatus = if (success) {
                                    StepExecutionStatus.SUCCEEDED
                                } else {
                                    StepExecutionStatus.FAILED
                                }
                                runCatching {
                                    // 用 ?.let 而不是 if (x != null)：后者会留下一个空 else，
                                    // 读起来像"还有一种情况没处理"。
                                    repository.listSteps(run.runId)
                                        .firstOrNull { it.stepId == stepId }
                                        ?.let { step ->
                                            val stagedBaseHash = stagedArtifactBaseHashes[stepId]
                                            val completed = if (stepId !in stagedArtifactSteps || stagedBaseHash == null) {
                                                step
                                            } else {
                                                step.copy(baseHash = stagedBaseHash, sideEffect = StepSideEffect.RETRY_SAFE)
                                            }
                                            repository.finishStep(completed, status = stepStatus)
                                        }
                                }.onFailure {
                                    Logger.w("BotTaskScheduler", "收尾步骤失败: ${it.message}")
                                }
                                progressBus?.onStepFinished(
                                    taskId = task.taskId,
                                    stepId = stepId,
                                    status = stepStatus,
                                )
                            }
                        },
                        onToolOutput = { stepId, toolName, summary, success ->
                            val stepStatus = if (success) {
                                StepExecutionStatus.SUCCEEDED
                            } else {
                                StepExecutionStatus.FAILED
                            }
                            progressBus?.onStepFinished(
                                taskId = task.taskId,
                                stepId = stepId,
                                status = stepStatus,
                                summary = summary,
                            )
                            if (stepId.isNotBlank() && summary.isNotBlank()) {
                                runCatching {
                                    repository.listSteps(run.runId)
                                        .firstOrNull { it.stepId == stepId }
                                        ?.let { existing ->
                                            repository.finishStep(existing, status = stepStatus, summary = summary)
                                        }
                                }.onFailure {
                                    Logger.w("BotTaskScheduler", "回写步骤摘要失败($toolName): ${it.message}")
                                }
                            }
                        },
                    ),
                    cancellation = cancellation,
                )
                result.threadId?.let { repository.attachRunThread(run.runId, it) }
                stagedArtifactsTransferred = onRunFinished(task, run, result, stagedArtifactRefs)
            } catch (e: CancellationException) {
                // 取消是正常路径，不是失败：任务已由 cancel 流程单独处理状态。
                Logger.i("BotTaskScheduler", "任务被取消: ${task.taskId}")
                throw e
            } catch (e: Exception) {
                Logger.e("BotTaskScheduler", "任务执行异常: ${task.taskId}", e)
                repository.closeRun(run.runId, TaskRunStatus.FAILED, e.message ?: "unknown")
                repository.transition(task.taskId, BotTaskStatusMachine.Event.MARK_UNKNOWN)
                repository.appendEvent(
                    botId = task.botId,
                    originSessionId = task.originSessionId,
                    taskId = task.taskId,
                    runId = run.runId,
                    kind = BotEventKind.TASK_FAILED,
                    dedupeKey = "task-failed:${task.taskId}:${run.runId}",
                )
            } finally {
                if (!stagedArtifactsTransferred) stagedArtifactRefs.forEach { staging?.discard(it) }
                runningJobs.remove(run.runId)
                cancellations.remove(run.runId)
            }
        }
        runningJobs[run.runId] = job
        // 占位完成后才真正开跑：此时 finally 里的 remove 一定能删到东西。
        job.start()
    }

    /**
     * 并发满时把队首任务转成"等资源"，并在有空位时恢复。
     *
     * 只动**队首一条**：把整个队列都转成等待会让用户看到满屏"在等资源"，
     * 而实际上只有一个能先跑。转一条，语义最接近真实情况。
     *
     * 恢复侧按 `WAITING_RESOURCE` 状态查，不猜 Bot id ——
     * `claimCandidates` 本身就是跨 Bot 的。
     */
    private suspend fun parkHeadForResource() {
        // 有空位：把等资源的任务放回队列。
        //
        // 用专门的 `parkedForResource` 而不是拿 `claimCandidates` 过滤 ——
        // 后者只查 QUEUED，过滤 WAITING_RESOURCE 永远为空，
        // 那会让这个恢复分支变成死代码，任务进了等待态就再也出不来。
        val waiting = repository.parkedForResource(MAX_PARKED)
        if (waiting.isNotEmpty()) {
            waiting.forEach { task ->
                repository.transition(task.taskId, BotTaskStatusMachine.Event.RESUME_FROM_RESOURCE)
                Logger.d("BotTaskScheduler", "有空位，任务恢复排队: ${task.taskId}")
            }
            return;
        }
        // 没空位：把队首一条转成等资源。
        val head = repository.claimCandidates(1).firstOrNull() ?: return;
        val moved = repository.transition(head.taskId, BotTaskStatusMachine.Event.WAIT_RESOURCE);
        if (moved != null) {
            Logger.d("BotTaskScheduler", "并发满，任务转等资源: ${head.taskId}")
        }
    }

    /**
     * 收尾：落 run 终态、把任务送进审核、发一条结果事件。
     *
     * 注意 COMPLETED 只代表"跑完了"，任务仍停在 REVIEWING ——
     * 未经审核的结果不能当作成功汇报给用户。
     */
    private suspend fun onRunFinished(
        task: BotTaskEntity,
        run: TaskRunEntity,
        result: SubagentRunner.Result,
        stagedArtifactRefs: List<String>,
    ): Boolean {
        if (result.approvalPending) {
            repository.closeRun(run.runId, TaskRunStatus.WAITING_APPROVAL)
            return false
        }
        if (RuntimeAgentResumePolicy.shouldPark(result)) {
            repository.closeRun(run.runId, TaskRunStatus.WAITING_RESOURCE, RuntimeMissing.CODE)
            repository.transition(task.taskId, BotTaskStatusMachine.Event.WAIT_RESOURCE)
            Logger.i("BotTaskScheduler", "任务等待运行环境安装: ${task.taskId}/${run.runId}")
            return false
        }
        if (!result.success) {
            repository.closeRun(run.runId, TaskRunStatus.FAILED, result.error ?: "runner_failed")
            repository.transition(task.taskId, BotTaskStatusMachine.Event.MARK_UNKNOWN)
            repository.appendEvent(
                botId = task.botId,
                originSessionId = task.originSessionId,
                taskId = task.taskId,
                runId = run.runId,
                kind = BotEventKind.TASK_FAILED,
                dedupeKey = "task-failed:${task.taskId}:${run.runId}",
            )
            return false
        }
        val stagedIds = stagedArtifactRefs.distinct()
        val payload = buildString {
            append(result.summary.take(MAX_SUMMARY_CHARS))
            if (stagedIds.isNotEmpty()) {
                append("\n")
                append(BotResultApplier.STAGED_MARKER)
                append(stagedIds.joinToString(","))
                append("]")
            }
        }
        if (!repository.finishExecution(task.taskId, run.runId, run.taskRevision, payload)) {
            Logger.w("BotTaskScheduler", "执行结果与任务版本不匹配，结果未进入审核: ${task.taskId}")
            return false
        }

        // 立刻走一遍审核 → 应用。
        //
        // 不做这一步的话，候选会一直躺在暂存区：用户看到"任务完成"，
        // 但目标文件一个字没变。那是最糟的失败方式 —— 声称做了却没做。
        val reviewer = applier ?: run {
            // 没有 applier 就没人审核，任务会永久停在 REVIEWING。
            // 这不是"跳过审核"，是装配缺失 —— 必须留痕，否则用户看到
            // 任务一直"在核对结果"，而实际上根本没人在核对。
            Logger.e("BotTaskScheduler", "没有注入 BotResultApplier，任务将停在 REVIEWING: ${task.taskId}")
            return true
        }
        val reviewOutcome = runCatching {
            reviewer.process(
                task = task,
                run = run,
                payloadRef = payload,
                expectedBaseHash = "",
                criteria = task.acceptanceCriteria,
            )
        }.onFailure {
            // 审核/应用失败不能吞：任务状态必须反映"结果没落地"。
            Logger.w("BotTaskScheduler", "审核应用失败: ${it.message}", it)
            repository.transition(task.taskId, BotTaskStatusMachine.Event.MARK_UNKNOWN)
        }.getOrNull()

        // 审查 P1-10：`process` 的返回值此前被丢弃。
        //
        // 后果：`BotResultApplier` 在"审核通过但没写成功"时返回 `applied = false`，
        // 但状态已经被它内部转成了 SUCCEEDED —— 用户看到"完成了"，文件却没变。
        // 那正是这个函数自己点名的"声称做了却没做"。
        //
        // 这里只把这种情况显式打出来，不强行改状态：
        // `process` 内部已按审核结论做过转移，外部再改会和状态机打架
        // （SUCCEEDED 是终态，任何事件都会被拒）。真正的修法在 applier 内部。
        if (reviewOutcome != null && !reviewOutcome.accepted) {
            Logger.e(
                "BotTaskScheduler",
                "结果未通过审核或未能结算: ${task.taskId} ${reviewOutcome.blockedReason}",
            )
        } else if (reviewOutcome?.accepted == true && task.requestMessageId.isNotBlank()) {
            // 审核通过正式交付时，将来源用户消息的角标反应联动更新为完成态，形成自然闭环。
            runCatching {
                annotationService?.annotate(
                    botId = task.botId,
                    originSessionId = task.originSessionId,
                    targetMessageId = task.requestMessageId,
                    kind = AnnotationKind.REACTION,
                    assetRef = "✅",
                    contentHash = "",
                )
            }.onFailure {
                Logger.w("BotTaskScheduler", "任务完成联动更新消息角标失败: ${it.message}")
            }
        }
        return true
    }

    /**
     * 取消一个任务。
     *
     * 先置 CANCEL_REQUESTED 再真正中断 —— 顺序反了会让用户在"已取消"之后
     * 又收到一条完成汇报。
     *
     * ## 副作用已发生时要报 PARTIAL，不能报"已取消"
     *
     * 审查 P1-5：原实现无条件走 `CANCEL_CONFIRM → CANCELLED`，从不看步骤账本。
     *
     * 后果：已经发过通知、已经改过文件的任务，被如实汇报成"已取消，什么都没发生"。
     * 用户会以为世界回到了动手之前 —— 而通知已经发出去了，文件已经变了。
     *
     * `CANCEL_TO_PARTIAL` 事件一直存在，但从未被发出。现在补上：
     * 账本里有任何 `NO_BLIND_REPLAY` 步骤，就走 PARTIAL。
     */
    suspend fun cancel(taskId: String) {
        val task = repository.getTask(taskId) ?: return
        repository.transition(taskId, BotTaskStatusMachine.Event.CANCEL_REQUEST)
        val run = repository.listRuns(taskId).firstOrNull { it.status == TaskRunStatus.RUNNING }
        if (run == null) {
            // 没有在跑的 run：仍要判断这个任务之前有没有留下不可回滚的痕迹。
            settleCancel(task, runId = null)
            return
        }
        cancellations[run.runId]?.cancel()
        // 给 Runner 一点时间在间断点收尾，再硬取消。
        runningJobs[run.runId]?.cancel()
        repository.closeRun(run.runId, TaskRunStatus.CANCELLED, "user_cancelled")
        settleCancel(task, run.runId)
    }

    /**
     * 取消的收尾：有不可回滚副作用就走 PARTIAL，否则才算真的取消。
     *
     * @param runId 正在跑的 run；null 表示没有在跑的（只查历史 run）。
     */
    private suspend fun settleCancel(task: BotTaskEntity, runId: String?) {
        val irreversible = runCatching {
            val runs = repository.listRuns(task.taskId)
            val scoped = if (runId != null) runs.filter { it.runId == runId } else runs
            scoped.flatMap { repository.listSteps(it.runId) }
                .filter { it.sideEffect == StepSideEffect.NO_BLIND_REPLAY }
                .map { it.toolName.ifBlank { "未知操作" } }
        }.onFailure {
            // 读不到账本时按"可能有副作用"处理 —— 宁可让用户核验，也不要谎称干净。
            Logger.w("BotTaskScheduler", "读步骤账本失败，取消按部分完成处理: ${task.taskId}", it)
        }.getOrElse { listOf("无法确认已发生的操作（步骤账本读取失败）") }

        if (irreversible.isEmpty()) {
            repository.transition(task.taskId, BotTaskStatusMachine.Event.CANCEL_CONFIRM)
            repository.appendEvent(
                botId = task.botId,
                originSessionId = task.originSessionId,
                taskId = task.taskId,
                runId = runId.orEmpty(),
                kind = BotEventKind.TASK_CANCELLED,
                dedupeKey = "task-cancelled:${task.taskId}:${runId.orEmpty()}",
            )
            return
        }

        // 有不可回滚的操作：取消不能抹掉它们。
        repository.transition(task.taskId, BotTaskStatusMachine.Event.CANCEL_TO_PARTIAL)
        repository.appendEvent(
            botId = task.botId,
            originSessionId = task.originSessionId,
            taskId = task.taskId,
            runId = runId.orEmpty(),
            kind = BotEventKind.TASK_CANCELLED,
            dedupeKey = "task-cancelled-partial:${task.taskId}:${runId.orEmpty()}",
            payloadRef = irreversible.joinToString("、"),
        )
    }

    /**
     * 巡检跑太久的任务。
     *
     * 超时的不自动重跑 —— 它可能已经产生了副作用。标 UNKNOWN 让用户核验，
     * 比"再跑一遍"安全得多。
     *
     * @return 被标 UNKNOWN 的任务数
     */
    suspend fun sweepOverdue(): Int {
        // 恢复窗口内不写库：此时 runningRuns 读到的可能是即将被替换的旧库。
        if (io.zer0.common.ProcessWriteGate.restoring) return 0
        val overdue = repository.runningRuns().filter { run -> governor.isOverdue(run.startedAt) }
        overdue.forEach { run ->
            Logger.w("BotTaskScheduler", "任务超时，标 UNKNOWN: ${run.runId}")
            // 先取消 Job 再落状态：反序会让 worker 在状态已改后继续写产物。
            cancellations[run.runId]?.cancel()
            runningJobs[run.runId]?.cancel()
            repository.closeRun(run.runId, TaskRunStatus.UNKNOWN, "task_timeout")
            repository.transition(run.taskId, BotTaskStatusMachine.Event.MARK_UNKNOWN)
        }
        return overdue.size
    }

    /**
     * 进程重启后对账：租约过期的在途 run 一律标 UNKNOWN，等用户核验。
     *
     * ## 为什么不能只看 `staleRuns()`
     *
     * 审查 P1-9：`renewLease` 全仓没有生产调用，租约永远停在 `openRun` 时写的
     * `now + LEASE_MS`（60 秒）。而任务预算是 30 分钟。
     *
     * 后果：任何跑超过 60 秒的正常任务，在**本进程仍活着**的情况下，
     * 只要对账被触发（切 Bot、重启保活服务），就会被标 UNKNOWN ——
     * 用户看到「结果未知」，而任务其实还在正常跑。
     *
     * 修法：对账前先排除"本进程正在跑"的 run。
     * `runningJobs` 里有它，说明 worker 活着，租约过期只是没人续租。
     */
    suspend fun reconcileAfterRestart() {
        reconcileReviewingResults()
        val active = runningJobs.keys
        repository.staleRuns().forEach { run ->
            if (run.runId in active) {
                // 本进程还在跑它：租约过期是"没续租"，不是"worker 死了"。
                // 标 UNKNOWN 会打断一个正常任务，而且它的 Job 还在写产物。
                Logger.d(
                    "BotTaskScheduler",
                    "租约过期但 worker 仍活跃，跳过对账: ${run.runId}",
                )
                return@forEach
            }
            Logger.w("BotTaskScheduler", "发现租约过期的 run，标 UNKNOWN: ${run.runId}")
            repository.closeRun(run.runId, TaskRunStatus.UNKNOWN, "stale_lease")
            repository.transition(run.taskId, BotTaskStatusMachine.Event.MARK_UNKNOWN)
        }
    }

    private suspend fun reconcileReviewingResults() {
        val reviewer = applier ?: return
        repository.reviewingTasks().forEach { task ->
            val run = repository.listRuns(task.taskId).firstOrNull {
                it.status == TaskRunStatus.COMPLETED && it.taskRevision == task.revision
            } ?: return@forEach
            val resultEvent = repository.latestTaskEvent(task.taskId, run.runId, BotEventKind.TASK_RESULT_READY)
                ?: return@forEach
            runCatching {
                reviewer.recover(task, run, resultEvent.payloadRef)
            }.onFailure {
                Logger.w("BotTaskScheduler", "审核恢复失败: ${task.taskId} | ${it.message}", it)
            }.getOrNull()?.let { outcome ->
                if (!outcome.accepted) {
                    Logger.w("BotTaskScheduler", "审核恢复仍需处理: ${task.taskId} | ${outcome.blockedReason}")
                }
            }
        }
    }

    /**
     * 工具 → 副作用类别。
     *
     * SAFE 级工具是只读的（读文件、搜索、查询），重放不会产生新副作用；
     * NORMAL 及以上一律按"不可盲重放"处理 —— 它们会写东西，而"写得对不对"
     * 只有审核能判断。
     *
     * 拿不到风险等级时按 NO_BLIND_REPLAY 处理：不知道就不重放。
     */
    /**
     * 读目标当前内容并算 hash；读不到返回空串。
     *
     * 空串的语义是"不知道动手前是什么样"，不是"文件原本不存在"。
     * 应用阶段见到空 baseHash 会跳过冲突检查 —— 那是既有行为，
     * 但至少现在"读到了"的情况下不会再跳过。
     */
    private suspend fun baselineHashFor(targetRef: String, sessionId: String): String {
        if (targetRef.isBlank()) return ""
        val manager = workspaceManager
        if (manager != null) {
            val mountScope = manager.sessionMountIds(sessionId)
            return when (val snapshot = manager.readSnapshotForWrite(targetRef, mountScope)) {
                is io.zer0.muse.workspace.WorkspaceManager.WriteSnapshot.Existing ->
                    StagedArtifactStore.hashSnapshot(snapshot.content)
                io.zer0.muse.workspace.WorkspaceManager.WriteSnapshot.Missing ->
                    StagedArtifactStore.MISSING_TARGET_HASH
                is io.zer0.muse.workspace.WorkspaceManager.WriteSnapshot.Refused -> {
                    Logger.w("BotTaskScheduler", "读取资源基线失败: $targetRef | ${snapshot.reason}")
                    StagedArtifactStore.UNREADABLE_TARGET_HASH
                }
            }
        }
        val io = workspaceIO ?: return ""
        return runCatching {
            StagedArtifactStore.hashSnapshot(io.read(targetRef))
        }.onFailure {
            Logger.d("BotTaskScheduler", "读取目标基线失败: $targetRef ${it.message}")
        }.getOrDefault(StagedArtifactStore.MISSING_TARGET_HASH)
    }

    private fun toolTargetPath(toolName: String, argsJson: String, fallback: String): String {
        val args = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(argsJson) as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return fallback
        val key = when (toolName) {
            io.zer0.muse.tools.WorkspaceTool.NAME_COPY -> "to"
            io.zer0.muse.tools.WorkspaceTool.NAME_MOVE -> "from"
            else -> "path"
        }
        return args[key]
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?: fallback
    }
    /**
     * 工具 → 副作用类别。
     *
     * ## 为什么不能只用风险等级
     *
     * 审查 P2-11：原实现把 `ToolRiskLevel.SAFE` 一律判成 `RETRY_SAFE`（可重放）。
     * 但风险等级衡量的是"要不要用户批准"，不是"重放会不会产生新副作用"。
     *
     * 具体例子：`generate_qr_code` 是 SAFE（不危险，不需要批准），
     * 但它**会生成图片并挂到消息上** —— 重放会多出一张图。
     *
     * 更糟的是它在中心任务里**既不过审批、又被当可重放**，两个判断都基于同一个错误前提。
     *
     * ## 判据
     *
     * 1. 显式列出的写盘工具 → `NO_BLIND_REPLAY`（不管风险等级）；
     * 2. 其余 SAFE → `RETRY_SAFE`；
     * 3. 拿不到等级 → `NO_BLIND_REPLAY`（不知道就不重放）。
     */
    private fun classifySideEffect(toolName: String): String {
        // 已知会写盘但风险等级为 SAFE 的工具。
        //
        // 这份清单是**补偿**，不是正解：正解是给 `ToolDef` 加显式的副作用声明，
        // 让每个工具自己说"我重放安全吗"。那要动工具注册表，改动面更大。
        // 在此之前，这里至少不会把已知的写盘操作当成可重放。
        if (toolName in SAFE_BUT_WRITES) {
            Logger.d("BotTaskScheduler", "SAFE 但会写盘，按不可重放处理: $toolName")
            return StepSideEffect.NO_BLIND_REPLAY
        }
        val level = runCatching { toolRegistryRef?.getToolRiskLevel(toolName) }
            .onFailure { Logger.w("BotTaskScheduler", "读取工具风险失败: ${it.message}") }
            .getOrNull()
        return when (level) {
            io.zer0.muse.tools.ToolRiskLevel.SAFE -> StepSideEffect.RETRY_SAFE
            null -> StepSideEffect.NO_BLIND_REPLAY
            else -> StepSideEffect.NO_BLIND_REPLAY
        }
    }

    /** 是否还有在跑的任务（保活层据此决定前台服务去留）。 */
    fun hasRunningWork(): Boolean = runningJobs.values.any { it.isActive }

    private fun buildContext(task: BotTaskEntity): String {
        val sb = StringBuilder()
        if (task.acceptanceCriteria.isNotBlank()) {
            sb.appendLine("验收标准：")
            sb.appendLine(task.acceptanceCriteria)
        }
        if (task.resourceScope.isNotBlank()) {
            sb.appendLine("可改动范围：")
            sb.appendLine(task.resourceScope)
            sb.appendLine("范围之外一律不要改动。")
        }
        sb.appendLine("任务版本：${task.revision}（约束变更时以最新版本为准）")
        return sb.toString().trim()
    }

    /**
     * 从 resourceScope 里抽出路径提示。
     *
     * 只做"看起来像路径"的粗筛：这里不是安全边界，真正的边界在工具执行前的
     * 权限闸与写锁里。给错了顶多让子 Agent 多找一遍文件。
     */
    private fun parsePaths(scope: String): List<String>? {
        if (scope.isBlank()) return null
        val paths = scope.split(COMMA, NEWLINE, SEMICOLON)
            .map { it.trim() }
            .filter { it.contains("/") || it.contains(".") }
        return paths.takeIf { it.isNotEmpty() }
    }

    /** 将底层工具名和参数摘要翻译成用户一眼能看懂的实时步骤说明。 */
    private fun describeToolStep(toolName: String, argsJson: String): String {
        val targetPath = toolTargetPath(toolName, argsJson, "")
        val shortTarget = targetPath.substringAfterLast('/').take(40)
        return when {
            toolName.contains("search", ignoreCase = true) || toolName.contains("web", ignoreCase = true) ->
                "联网检索相关资料"
            toolName.contains("python", ignoreCase = true) ->
                "在沙盒电脑中运行 Python 脚本"
            toolName.contains("node", ignoreCase = true) || toolName.contains("javascript", ignoreCase = true) ->
                "在沙盒电脑中运行 Node 脚本"
            toolName.contains("shell", ignoreCase = true) || toolName.contains("bash", ignoreCase = true) ->
                "在沙盒终端执行命令"
            toolName.contains("virtual_display", ignoreCase = true) || toolName.contains("ui_agent", ignoreCase = true) ->
                "在后台虚拟屏操作应用"
            toolName == io.zer0.muse.tools.WorkspaceTool.NAME_READ ->
                if (shortTarget.isNotBlank()) "读取文件 $shortTarget" else "读取工作区文件"
            toolName == io.zer0.muse.tools.WorkspaceTool.NAME_WRITE ||
                toolName == io.zer0.muse.tools.WorkspaceTool.NAME_EDIT ->
                if (shortTarget.isNotBlank()) "更新文件 $shortTarget" else "写入工作区文件"
            toolName == io.zer0.muse.tools.WorkspaceTool.NAME_LIST ->
                "浏览工作区目录"
            toolName == io.zer0.muse.tools.WorkspaceTool.NAME_GREP ->
                "检索工作区内容"
            toolName.contains("channel", ignoreCase = true) ->
                "通过外部渠道同步信息"
            else ->
                "执行 ${toolName.replace('_', ' ')}"
        }
    }

    companion object {
        /** 事件里保留的结果摘要上限。正文走产物，事件只留够判断的片段。 */
        private const val MAX_SUMMARY_CHARS = 2000

        /**
         * 驱动轮询间隔。
         *
         * 5 秒：用户派工后最多等这么久就开始做，而空转成本只是一次计数查询。
         * 更快没有意义（用户感知不到 2 秒与 5 秒的差别），更慢会让"接单"显得迟滞。
         */
        /**
         * 一次最多恢复多少条"等资源"任务。
         *
         * 不需要大：并发空位通常只有 1–2 个，多恢复只会让它们重新排回队首。
         */
        private const val MAX_PARKED = 4

        const val DEFAULT_DRIVE_INTERVAL_MS = 5_000L

        /**
         * 风险等级为 SAFE、但重放会产生新副作用的工具。
         *
         * `ToolRiskLevel` 衡量的是"要不要用户批准"，不是"重放安全吗"。
         * 两者在大多数工具上一致，但不完全重合 —— 这个清单收的是不重合的部分。
         *
         * 加新条目时问一句：这个工具跑两次，用户会看到两份结果吗？
         */
        private val SAFE_BUT_WRITES: Set<String> = setOf(
            // 生成二维码图片并挂到消息上：跑两次会多一张图。
            "generate_qr_code",
        )

        private const val COMMA = ","
        private const val NEWLINE = "\n"
        private const val SEMICOLON = ";"
    }
}
