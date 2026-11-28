package io.zer0.muse.data.agentbot

import androidx.room.withTransaction
import io.zer0.common.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * 页面投影的 Room 事实快照。
 *
 * 只含「当前 assistant + canonical 会话」的行。调度器仍按 Bot 领任务（跨会话），
 * 但页面看板必须收窄到当前会话 —— 否则用户会在看板上看到别的对话交代的事。
 *
 * [candidateIds] 不是实体：候选本体在暂存区（文件），这里只记下当前作用域
 * 引用到的暂存 id，由 UI 层与暂存区清单求交集。
 */
data class AgentBotScope(
    val assistantId: String,
    val sessionId: String,
    val tasks: List<BotTaskEntity>,
    val approvals: List<ApprovalRequestEntity>,
    val candidateIds: Set<String>,
    val annotations: List<BotAnnotationEntity> = emptyList(),
    val cards: List<BotMessageCardEntity> = emptyList(),
    val steps: List<TaskStepEntity> = emptyList(),
) {

    /** 待用户决定的审批。已结算的留在 [approvals] 里供状态判定。 */
    val pendingApprovals: List<ApprovalRequestEntity>
        get() = approvals.filter { it.status == ApprovalStatus.PENDING }
}

/** Room gateways used by the task ledger. Keeping them together makes the write boundary explicit. */
data class AgentBotRepositoryDaos(
    val task: BotTaskDao,
    val run: TaskRunDao,
    val step: TaskStepDao,
    val event: BotEventDao,
    val approval: ApprovalRequestDao,
    val publication: PublicationOutboxDao,
    val binding: BotBindingDao,
    val annotation: BotAnnotationDao? = null,
    val card: BotMessageCardDao? = null,
    val routine: BotRoutineDao? = null,
)

/** Runtime services that are not Room gateways. */
data class AgentBotRepositoryRuntime(
    val database: io.zer0.muse.data.session.MuseDb? = null,
    val now: () -> Long = { System.currentTimeMillis() },
)

/**
 * 任务账本的唯一写入口。
 *
 * ## 为什么不让各调用方直接写 DAO
 *
 * "终态不可被迟到事件改写"这条不变量，如果靠每个调用点各自 if 判断，
 * 迟早会漏一条路径。所有状态变更都过这里，转移合法性只由
 * [BotTaskStatusMachine] 判定，非法转移返回失败而不是硬写。
 *
 * ## 为什么事件要在这里生成
 *
 * 状态变了却不发事件，等于中枢不知道发生了什么；发了事件但状态没变，
 * 等于主 Bot 收到一条无对应事实的通知。两者都在同一个函数里做，
 * 顺序固定为"先改状态 → 再落事件"。
 */
class AgentBotRepository(
    daos: AgentBotRepositoryDaos,
    runtime: AgentBotRepositoryRuntime = AgentBotRepositoryRuntime(),
) {

    private val taskDao = daos.task
    private val runDao = daos.run
    private val stepDao = daos.step
    private val eventDao = daos.event
    private val approvalDao = daos.approval
    private val publicationDao = daos.publication
    private val bindingDao = daos.binding
    private val database = runtime.database
    private val now = runtime.now
    private val annotationDao = daos.annotation ?: database?.botAnnotationDao()
    private val cardDao = daos.card ?: database?.botMessageCardDao()
    private val routineDao = daos.routine ?: database?.botRoutineDao()
    private val remoteExecOwnerStore = AgentRemoteExecOwnerStore(eventDao, now)

    /** Compatibility adapter for existing isolated data-layer tests during dependency migration. */
    constructor(
        taskDao: BotTaskDao,
        runDao: TaskRunDao,
        stepDao: TaskStepDao,
        eventDao: BotEventDao,
        approvalDao: ApprovalRequestDao,
        publicationDao: PublicationOutboxDao,
        bindingDao: BotBindingDao,
        database: io.zer0.muse.data.session.MuseDb? = null,
        now: () -> Long = { System.currentTimeMillis() },
        annotationDao: BotAnnotationDao? = database?.botAnnotationDao(),
        cardDao: BotMessageCardDao? = database?.botMessageCardDao(),
        routineDao: BotRoutineDao? = database?.botRoutineDao(),
    ) : this(
        daos = AgentBotRepositoryDaos(
            task = taskDao,
            run = runDao,
            step = stepDao,
            event = eventDao,
            approval = approvalDao,
            publication = publicationDao,
            binding = bindingDao,
            annotation = annotationDao,
            card = cardDao,
            routine = routineDao,
        ),
        runtime = AgentBotRepositoryRuntime(database = database, now = now),
    )

    suspend fun recordRemoteExecOwner(nodeId: String, remoteRunId: String, botId: String, originSessionId: String): Boolean {
        return remoteExecOwnerStore.record(nodeId, remoteRunId, botId, originSessionId)
    }

    suspend fun findRemoteExecOwner(nodeId: String, remoteRunId: String): BotEventEntity? {
        return remoteExecOwnerStore.find(nodeId, remoteRunId)
    }

    suspend fun clearRemoteExecOwner(nodeId: String, remoteRunId: String) {
        remoteExecOwnerStore.clear(nodeId, remoteRunId)
    }

    enum class ReviewCommitResult {
        COMMITTED,
        ALREADY_COMMITTED,
        EFFECT_REJECTED,
        STALE,
    }

    /** 单写者锁：同一进程内状态变更串行，避免并发转移互相覆盖。 */
    private val writeLock = Mutex()

    private val _activeTaskCount = MutableStateFlow(0)

    /** 在途任务数（排队、执行、等待资源或审批、审核中、取消请求中）。诊断与保活用。 */
    val activeTaskCount: StateFlow<Int> = _activeTaskCount.asStateFlow()

    // ── 任务 ──────────────────────────────────────────────────────────

    /**
     * 建任务。同一 [dedupeKey] 已存在时返回既有任务，不重复建。
     *
     * 幂等键只认同一次输入 / 同一次 action —— 用户再提一次"同样的事"
     * 仍然是一个新任务，不能因为语义相似就吞掉。
     */
    suspend fun createTask(
        botId: String,
        originSessionId: String,
        requestMessageId: String,
        goal: String,
        acceptanceCriteria: String,
        resourceScope: String = "",
        permissionScopeId: String = "",
        priority: Int = 0,
        dedupeKey: String = "",
    ): BotTaskEntity = writeLock.withLock {
        createTaskLocked(
            botId = botId,
            originSessionId = originSessionId,
            requestMessageId = requestMessageId,
            goal = goal,
            acceptanceCriteria = acceptanceCriteria,
            resourceScope = resourceScope,
            permissionScopeId = permissionScopeId,
            priority = priority,
            dedupeKey = dedupeKey,
        )
    }

    /** 在输入 revision 仍为最新时，原子地创建对应任务和接单事件。 */
    suspend fun createTaskForInput(input: TaskCreationInput): BotTaskEntity? = createTaskForInput(
        identity = input.identity,
        goal = input.goal,
        acceptanceCriteria = input.acceptanceCriteria,
        resourceScope = input.resourceScope,
        permissionScopeId = input.permissionScopeId,
        dedupeKey = input.dedupeKey,
    )

    suspend fun createTaskForInput(
        identity: AgentTurnIdentity,
        goal: String,
        acceptanceCriteria: String,
        resourceScope: String,
        permissionScopeId: String = identity.permissionScopeId,
        dedupeKey: String,
    ): BotTaskEntity? = writeLock.withLock {
        if (identity.validate() != null) return@withLock null
        var created: BotTaskEntity? = null
        val operation: suspend () -> Unit = {
            val currentRevision = eventDao.latestUserInputSequence(identity.assistantId, identity.sessionId) ?: 0L
            if (currentRevision == identity.requestRevision) {
                created = createTaskLocked(
                    botId = identity.assistantId,
                    originSessionId = identity.sessionId,
                    requestMessageId = identity.requestId,
                    goal = goal,
                    acceptanceCriteria = acceptanceCriteria,
                    resourceScope = resourceScope,
                    permissionScopeId = permissionScopeId,
                    priority = 0,
                    dedupeKey = dedupeKey,
                )
            }
        }
        val db = database
        if (db == null) operation() else db.withTransaction { operation() }
        created
    }

    private suspend fun createTaskLocked(
        botId: String,
        originSessionId: String,
        requestMessageId: String,
        goal: String,
        acceptanceCriteria: String,
        resourceScope: String,
        permissionScopeId: String,
        priority: Int,
        dedupeKey: String,
    ): BotTaskEntity {
        if (dedupeKey.isNotBlank()) {
            val existing = eventDao.findByDedupeKey(dedupeKey)
            val existingTaskId = existing?.taskId.orEmpty()
            if (existingTaskId.isNotBlank()) {
                taskDao.getById(existingTaskId)?.let { return it }
            }
        }
        val ts = now()
        val task = BotTaskEntity(
            taskId = UUID.randomUUID().toString(),
            botId = botId,
            originSessionId = originSessionId,
            requestMessageId = requestMessageId,
            goal = goal,
            acceptanceCriteria = acceptanceCriteria,
            resourceScope = resourceScope,
            permissionScopeId = permissionScopeId,
            revision = 1,
            status = BotTaskStatus.QUEUED,
            priority = priority,
            createdAt = ts,
            updatedAt = ts,
        )
        taskDao.upsert(task)
        val key = if (dedupeKey.isNotBlank()) dedupeKey else "task-accepted:${task.taskId}"
        appendEventLocked(
            botId = botId,
            originSessionId = originSessionId,
            taskId = task.taskId,
            runId = "",
            kind = BotEventKind.TASK_ACCEPTED,
            dedupeKey = key,
        )
        refreshActiveCount()
        return task
    }

    /**
     * 施加一次状态转移。
     *
     * @return 转移后的任务；非法转移（迟到事件 / 重复事件）返回 null，**不改库**。
     */
    suspend fun transition(taskId: String, event: BotTaskStatusMachine.Event): BotTaskEntity? = writeLock.withLock {
        var updatedResult: BotTaskEntity? = null
        val operation: suspend () -> Unit = {
            val current = taskDao.getById(taskId)
            if (current != null) {
                val next = BotTaskStatusMachine.next(current.status, event)
                if (next == null) {
                    Logger.d("AgentBotRepo", "忽略非法转移: ${current.status} + $event (task=$taskId)")
                } else {
                    val updated = current.copy(status = next, updatedAt = now())
                    taskDao.upsert(updated)
                    settleRoutineTask(current, next, updated.updatedAt)
                    updatedResult = updated
                    refreshActiveCount()
                }
            }
        }
        val db = database
        if (db == null) operation() else db.withTransaction { operation() }
        updatedResult
    }

    suspend fun claimAndOpenRun(taskId: String): Pair<BotTaskEntity, TaskRunEntity>? = writeLock.withLock {
        var claimed: Pair<BotTaskEntity, TaskRunEntity>? = null
        val operation: suspend () -> Unit = {
            val current = taskDao.getById(taskId)
            val next = current?.let { BotTaskStatusMachine.next(it.status, BotTaskStatusMachine.Event.START) }
            if (current != null && next != null) {
                val ts = now()
                val updated = current.copy(status = next, updatedAt = ts)
                val runs = runDao.listByTask(taskId)
                val run = TaskRunEntity(
                    runId = UUID.randomUUID().toString(),
                    taskId = taskId,
                    attempt = (runs.maxOfOrNull { it.attempt } ?: 0) + 1,
                    threadId = runs.firstOrNull { it.threadId.isNotBlank() }?.threadId.orEmpty(),
                    taskRevision = current.revision,
                    status = TaskRunStatus.RUNNING,
                    leaseOwner = current.botId,
                    leaseUntil = ts + LEASE_MS,
                    fenceToken = 1L,
                    startedAt = ts,
                )
                taskDao.upsert(updated)
                runDao.upsert(run)
                refreshActiveCount()
                claimed = updated to run
            }
        }
        val db = database
        if (db == null) operation() else db.withTransaction { operation() }
        claimed
    }

    /**
     * Atomically settle execution and retain its result event for review recovery.
     */
    suspend fun finishExecution(taskId: String, runId: String, taskRevision: Int, resultPayload: String): Boolean = writeLock.withLock {
        var finished = false
        val operation: suspend () -> Unit = {
            val task = taskDao.getById(taskId)
            val run = runDao.getById(runId)
            val dedupeKey = "task-result:$taskId:$runId"
            val existingEvent = eventDao.findByDedupeKey(dedupeKey)
            if (task != null && run != null && task.revision == taskRevision &&
                run.taskId == taskId && run.taskRevision == taskRevision
            ) {
                if (task.status == BotTaskStatus.REVIEWING && run.status == TaskRunStatus.COMPLETED &&
                    existingEvent?.payloadRef == resultPayload
                ) {
                    finished = true
                } else if (task.status == BotTaskStatus.RUNNING && run.status == TaskRunStatus.RUNNING &&
                    existingEvent == null
                ) {
                    val next = BotTaskStatusMachine.next(task.status, BotTaskStatusMachine.Event.EXECUTE_DONE)
                    if (next != null) {
                        val ts = now()
                        taskDao.upsert(task.copy(status = next, updatedAt = ts))
                        runDao.upsert(run.copy(status = TaskRunStatus.COMPLETED, finishedAt = ts))
                        appendEventLocked(
                            botId = task.botId,
                            originSessionId = task.originSessionId,
                            taskId = taskId,
                            runId = runId,
                            kind = BotEventKind.TASK_RESULT_READY,
                            dedupeKey = dedupeKey,
                            payloadRef = resultPayload,
                        )
                        refreshActiveCount()
                        finished = true
                    }
                }
            }
        }
        val db = database
        if (db == null) operation() else db.withTransaction { operation() }
        finished
    }

    /**
     * Commit review state and its durable facts together. [applyEffect] runs only after
     * the task/run revisions have been checked and while repository writes are fenced.
     */
    suspend fun commitReview(
        taskId: String,
        runId: String,
        taskRevision: Int,
        transition: BotTaskStatusMachine.Event,
        reviewPayload: String,
        applyEffect: suspend () -> Boolean = { true },
    ): ReviewCommitResult = writeLock.withLock {
        val fact = ReviewDecision.decode(reviewPayload) ?: return@withLock ReviewCommitResult.STALE
        val publicationAllowed = transition == BotTaskStatusMachine.Event.REVIEW_ACCEPT
        val expectedReviewTransition = BotReviewWorker().transitionFor(
            BotReviewWorker.Decision(disposition = fact.disposition),
        )
        val expectedPublicationKey = PublicationService.publicationKeyFor(taskId, runId, taskRevision)
        if (!isReviewPayloadConsistent(
                fact = fact,
                taskRevision = taskRevision,
                publicationAllowed = publicationAllowed,
                expectedPublicationKey = expectedPublicationKey,
                expectedReviewTransition = expectedReviewTransition,
                transition = transition,
            )
        ) {
            return@withLock ReviewCommitResult.STALE
        }
        val input = ReviewCommitInput(
            taskId = taskId,
            runId = runId,
            taskRevision = taskRevision,
            transition = transition,
            reviewPayload = reviewPayload,
            fact = fact,
            publicationAllowed = publicationAllowed,
            applyEffect = applyEffect,
        )
        val operation: suspend () -> ReviewCommitResult = { commitReviewLocked(input) }
        val db = database
        if (db == null) operation() else db.withTransaction { operation() }
    }

    private suspend fun commitReviewLocked(input: ReviewCommitInput): ReviewCommitResult {
        val entities = loadReviewCommitEntities(input) ?: return ReviewCommitResult.STALE
        if (!reviewEventsAreValid(input, entities)) return ReviewCommitResult.STALE
        val next = BotTaskStatusMachine.next(entities.task.status, input.transition)
        val alreadySettled = entities.task.status == input.fact.finalState && next == null
        if ((!alreadySettled && next == null) ||
            (entities.existingReview != null && entities.task.status != input.fact.finalState)
        ) {
            return ReviewCommitResult.STALE
        }
        if (!input.applyEffect()) return ReviewCommitResult.EFFECT_REJECTED
        settleReview(input, entities, next, alreadySettled)
        refreshActiveCount()
        return if (alreadySettled && entities.existingReview != null &&
            (!input.publicationAllowed || entities.existingPublication != null)
        ) {
            ReviewCommitResult.ALREADY_COMMITTED
        } else {
            ReviewCommitResult.COMMITTED
        }
    }

    private suspend fun loadReviewCommitEntities(input: ReviewCommitInput): ReviewCommitEntities? {
        val task = taskDao.getById(input.taskId) ?: return null
        val run = runDao.getById(input.runId) ?: return null
        val staleRevisionReview = task.revision > input.taskRevision &&
            input.transition == BotTaskStatusMachine.Event.MARK_UNKNOWN &&
            input.fact.disposition == ReviewDisposition.UNKNOWN && !input.fact.publicationAllowed
        if (task.revision != input.taskRevision && !staleRevisionReview) return null
        if (run.taskId != input.taskId || run.taskRevision != input.taskRevision || run.status != TaskRunStatus.COMPLETED) {
            return null
        }
        val reviewKey = "review-ready:${input.taskId}:${input.runId}"
        val publicationKey = "publication-ready:${input.taskId}:${input.runId}"
        return ReviewCommitEntities(
            task = task,
            reviewKey = reviewKey,
            publicationKey = publicationKey,
            existingReview = eventDao.findByDedupeKey(reviewKey),
            existingPublication = eventDao.findByDedupeKey(publicationKey),
        )
    }

    private fun reviewEventsAreValid(input: ReviewCommitInput, entities: ReviewCommitEntities): Boolean {
        val review = entities.existingReview
        val publication = entities.existingPublication
        val reviewInvalid = review?.let {
            it.payloadRef != input.reviewPayload || it.taskId != input.taskId || it.runId != input.runId ||
                it.kind != BotEventKind.REVIEW_READY
        } == true
        val publicationInvalid = publication?.let {
            !input.publicationAllowed || it.payloadRef != input.reviewPayload ||
                it.taskId != input.taskId || it.runId != input.runId ||
                it.kind != BotEventKind.TASK_PUBLICATION_READY
        } == true
        return !reviewInvalid && !publicationInvalid
    }

    private suspend fun settleReview(
        input: ReviewCommitInput,
        entities: ReviewCommitEntities,
        next: String?,
        alreadySettled: Boolean,
    ) {
        val task = entities.task
        if (!alreadySettled) {
            val ts = now()
            taskDao.upsert(task.copy(status = next.orEmpty(), updatedAt = ts))
            settleRoutineTask(task, next.orEmpty(), ts)
        } else {
            settleRoutineTask(task, task.status, now())
        }
        if (entities.existingReview == null) {
            appendEventLocked(
                botId = task.botId,
                originSessionId = task.originSessionId,
                taskId = input.taskId,
                runId = input.runId,
                kind = BotEventKind.REVIEW_READY,
                dedupeKey = entities.reviewKey,
                payloadRef = input.reviewPayload,
            )
        }
        if (input.publicationAllowed && entities.existingPublication == null) {
            appendEventLocked(
                botId = task.botId,
                originSessionId = task.originSessionId,
                taskId = input.taskId,
                runId = input.runId,
                kind = BotEventKind.TASK_PUBLICATION_READY,
                dedupeKey = entities.publicationKey,
                payloadRef = input.reviewPayload,
            )
        }
    }

    private data class ReviewCommitInput(
        val taskId: String,
        val runId: String,
        val taskRevision: Int,
        val transition: BotTaskStatusMachine.Event,
        val reviewPayload: String,
        val fact: ReviewDecision,
        val publicationAllowed: Boolean,
        val applyEffect: suspend () -> Boolean,
    )

    private data class ReviewCommitEntities(
        val task: BotTaskEntity,
        val reviewKey: String,
        val publicationKey: String,
        val existingReview: BotEventEntity?,
        val existingPublication: BotEventEntity?,
    )

    private fun isReviewPayloadConsistent(
        fact: ReviewDecision,
        taskRevision: Int,
        publicationAllowed: Boolean,
        expectedPublicationKey: String,
        expectedReviewTransition: BotTaskStatusMachine.Event,
        transition: BotTaskStatusMachine.Event,
    ): Boolean = fact.taskRevision == taskRevision &&
        fact.finalState == BotReviewWorker().finalStateFor(fact.disposition) &&
        fact.publicationAllowed == publicationAllowed &&
        fact.publicationKey == expectedPublicationKey &&
        expectedReviewTransition == transition

    /**
     * 追加约束：revision +1。
     *
     * 不覆盖正在执行的历史快照 —— 旧 run 的 taskRevision 落后，其结果在
     * 审核阶段会被判 stale 而不得发布。这正是"标题先别动"能生效的机制。
     */
    suspend fun bumpRevision(taskId: String): BotTaskEntity? = writeLock.withLock {
        val current = taskDao.getById(taskId) ?: return@withLock null
        val updated = current.copy(revision = current.revision + 1, updatedAt = now())
        taskDao.upsert(updated)
        updated
    }

    suspend fun getTask(taskId: String): BotTaskEntity? = taskDao.getById(taskId)

    suspend fun listTasks(botId: String): List<BotTaskEntity> = taskDao.listByBot(botId)

    suspend fun reviewingTasks(): List<BotTaskEntity> = taskDao.listReviewing()

    suspend fun runningTasks(): List<BotTaskEntity> = taskDao.listRunningTasks()

    suspend fun latestTaskEvent(taskId: String, runId: String, kind: String): BotEventEntity? = eventDao.latestOfKind(taskId, runId, kind)

    suspend fun claimCandidates(limit: Int): List<BotTaskEntity> = taskDao.claimCandidates(limit)

    /** 等资源的任务。恢复排队用。 */
    suspend fun parkedForResource(limit: Int): List<BotTaskEntity> = taskDao.parkedForResource(limit)

    // ── 运行 ──────────────────────────────────────────────────────────

    /**
     * 为任务开一轮新 attempt。
     *
     * attempt 号取"该任务已有轮次 + 1"，并由唯一索引兜底 —— 两个 worker
     * 同时开同一轮会有一方撞约束失败，而不是各写一份。
     */
    suspend fun openRun(taskId: String, threadId: String = ""): TaskRunEntity? = writeLock.withLock {
        val task = taskDao.getById(taskId) ?: return@withLock null
        val previousRuns = runDao.listByTask(taskId)
        val attempt = (previousRuns.maxOfOrNull { it.attempt } ?: 0) + 1
        val continuationThreadId = threadId.ifBlank {
            previousRuns.firstOrNull { it.threadId.isNotBlank() }?.threadId.orEmpty()
        }
        val ts = now()
        val run = TaskRunEntity(
            runId = UUID.randomUUID().toString(),
            taskId = taskId,
            attempt = attempt,
            threadId = continuationThreadId,
            taskRevision = task.revision,
            status = TaskRunStatus.RUNNING,
            leaseOwner = task.botId,
            leaseUntil = ts + LEASE_MS,
            fenceToken = 1L,
            startedAt = ts,
        )
        runDao.upsert(run)
        run
    }

    /**
     * 续租。返回 false 表示租约已被别人接手（fence 已推进），
     * 调用方必须停止写入 —— 否则两个 worker 会同时改同一份产物。
     */
    suspend fun renewLease(runId: String, fenceToken: Long): Boolean = writeLock.withLock {
        val run = runDao.getById(runId) ?: return@withLock false
        if (run.fenceToken != fenceToken) return@withLock false
        runDao.upsert(run.copy(leaseUntil = now() + LEASE_MS, fenceToken = fenceToken + 1))
        true
    }

    suspend fun closeRun(runId: String, status: String, errorCode: String = ""): TaskRunEntity? = writeLock.withLock {
        val run = runDao.getById(runId) ?: return@withLock null
        val updated = run.copy(status = status, finishedAt = now(), errorCode = errorCode)
        runDao.upsert(updated)
        updated
    }

    suspend fun listRuns(taskId: String): List<TaskRunEntity> = runDao.listByTask(taskId)

    /** 重启后对账：租约过期的在途 run。 */
    suspend fun staleRuns(): List<TaskRunEntity> = runDao.listStale(now())

    /** 当前所有在跑的 run（超时巡检用）。 */
    suspend fun runningRuns(): List<TaskRunEntity> = runDao.listRunning()

    // ── 步骤 ──────────────────────────────────────────────────────────

    suspend fun recordStep(
        runId: String,
        stepIndex: Int,
        toolName: String,
        argumentsHash: String,
        baseHash: String,
        approvalId: String,
        sideEffect: String,
        displayLabel: String = "",
        assigneeBotId: String = "",
    ): TaskStepEntity = writeLock.withLock {
        val ts = now()
        val step = TaskStepEntity(
            stepId = UUID.randomUUID().toString(),
            runId = runId,
            stepIndex = stepIndex,
            toolName = toolName,
            argumentsHash = argumentsHash,
            baseHash = baseHash,
            approvalId = approvalId,
            sideEffect = sideEffect,
            startedAt = ts,
            displayLabel = displayLabel,
            status = StepExecutionStatus.RUNNING,
            assigneeBotId = assigneeBotId,
        )
        stepDao.upsert(step)
        step
    }

    suspend fun finishStep(step: TaskStepEntity, status: String = StepExecutionStatus.SUCCEEDED, summary: String = ""): TaskStepEntity =
        writeLock.withLock {
            val updated = step.copy(
                finishedAt = now(),
                status = status,
                summary = summary.ifBlank { step.summary },
            )
            stepDao.upsert(updated)
            updated
        }

    suspend fun listTaskSteps(taskId: String): List<TaskStepEntity> = stepDao.listByTask(taskId)

    // ── 流内交互卡片 ───────────────────────────────────────────────────

    suspend fun getMessageCard(cardId: String): BotMessageCardEntity? = cardDao?.getById(cardId)

    suspend fun upsertMessageCard(card: BotMessageCardEntity) {
        val dao = cardDao ?: return
        val ts = now()
        dao.upsert(
            card.copy(
                createdAt = if (card.createdAt > 0L) card.createdAt else ts,
                updatedAt = ts,
            ),
        )
    }

    suspend fun insertMessageCardIfAbsent(card: BotMessageCardEntity): Boolean {
        val dao = cardDao ?: return false
        val ts = now()
        return dao.insertIfAbsent(
            card.copy(
                createdAt = if (card.createdAt > 0L) card.createdAt else ts,
                updatedAt = ts,
            ),
        ) != -1L
    }

    suspend fun resolveMessageCard(cardId: String, selectedOptionId: String = ""): Boolean {
        val dao = cardDao ?: return false
        return dao.updateInteraction(cardId, BotCardStatus.RESOLVED, selectedOptionId, now()) > 0
    }

    suspend fun resolveActiveMessageCard(cardId: String, botId: String, sessionId: String, selectedOptionId: String): Boolean {
        val dao = cardDao ?: return false
        return dao.resolveActiveForScope(cardId, botId, sessionId, selectedOptionId, now()) > 0
    }

    suspend fun dismissActiveMessageCard(cardId: String, botId: String, sessionId: String): Boolean {
        val dao = cardDao ?: return false
        return dao.dismissActiveForScope(cardId, botId, sessionId, now()) > 0
    }

    suspend fun dismissMessageCard(cardId: String): Boolean {
        val dao = cardDao ?: return false
        return dao.updateInteraction(cardId, BotCardStatus.DISMISSED, "", now()) > 0
    }

    // ── 惯例（Routines）───────────────────────────────────────────────

    fun observeRoutines(botId: String): Flow<List<BotRoutineEntity>> =
        routineDao?.observeByBot(botId) ?: kotlinx.coroutines.flow.flowOf(emptyList())

    fun observeAllRoutines(): Flow<List<BotRoutineEntity>> = routineDao?.observeAll() ?: kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun upsertRoutine(routine: BotRoutineEntity) {
        val dao = routineDao ?: return
        val ts = now()
        dao.upsert(
            routine.copy(
                createdAt = if (routine.createdAt > 0L) routine.createdAt else ts,
                updatedAt = ts,
            ),
        )
    }

    suspend fun setRoutineEnabled(routineId: String, enabled: Boolean): Boolean {
        val dao = routineDao ?: return false
        return dao.setEnabled(routineId, enabled, now()) > 0
    }

    suspend fun deleteRoutine(routineId: String) {
        routineDao?.deleteById(routineId)
    }

    suspend fun recordRoutineExecution(routineId: String, status: String) {
        routineDao?.recordExecution(routineId, now(), status)
    }

    suspend fun recordRoutineExecutionIfNewer(routineId: String, runAt: Long, status: String) {
        routineDao?.recordExecutionIfNewer(routineId, runAt, status)
    }

    suspend fun getRoutine(routineId: String): BotRoutineEntity? = routineDao?.getById(routineId)

    suspend fun listEnabledRoutinesByTrigger(triggerType: String): List<BotRoutineEntity> =
        routineDao?.listEnabledByTrigger(triggerType).orEmpty()

    /**
     * 原子地记录步骤、创建审批、挂起任务并追加审批事件。
     *
     * 任一事实写入失败时，事务回滚，避免出现没有审批卡的等待任务或未挂起却可批准的步骤。
     */
    suspend fun requestApproval(
        taskId: String,
        runId: String,
        taskRevision: Int,
        stepId: String,
        toolCallId: String,
        toolName: String,
        arguments: String,
        argumentsHash: String,
        baseHash: String,
        sideEffect: String,
        risk: String,
    ): ApprovalRequestEntity? = writeLock.withLock {
        var created: ApprovalRequestEntity? = null
        val operation: suspend () -> Unit = {
            val task = taskDao.getById(taskId)
            val run = runDao.getById(runId)
            val existingStep = stepDao.listByRun(runId).firstOrNull { it.stepId == stepId }
            if (task == null || run == null || existingStep == null) return@operation
            if (task.revision != taskRevision || run.taskId != taskId || run.taskRevision != taskRevision ||
                run.status != TaskRunStatus.RUNNING
            ) return@operation
            if (existingStep.toolName != toolName || existingStep.argumentsHash != argumentsHash) return@operation
            val nextStatus = BotTaskStatusMachine.next(task.status, BotTaskStatusMachine.Event.WAIT_APPROVAL)
            if (nextStatus != null) {
                val ts = now()
                val approvalId = UUID.randomUUID().toString()
                val step = existingStep.copy(
                    baseHash = baseHash,
                    approvalId = approvalId,
                    sideEffect = sideEffect,
                )
                val approval = ApprovalRequestEntity(
                    approvalId = approvalId,
                    botId = task.botId,
                    originSessionId = task.originSessionId,
                    taskId = taskId,
                    runId = runId,
                    stepId = step.stepId,
                    taskRevision = taskRevision,
                    toolCallId = toolCallId,
                    toolName = toolName,
                    argumentsRef = arguments.take(500),
                    argumentsHash = argumentsHash,
                    resourceVersion = baseHash,
                    risk = risk,
                    createdAt = ts,
                    readyAt = ts,
                    expiresAt = ts + APPROVAL_TTL_MS,
                )
                stepDao.upsert(step)
                approvalDao.upsert(approval)
                taskDao.upsert(task.copy(status = nextStatus, updatedAt = ts))
                appendEventLocked(
                    botId = task.botId,
                    originSessionId = task.originSessionId,
                    taskId = taskId,
                    runId = runId,
                    kind = BotEventKind.APPROVAL_REQUIRED,
                    dedupeKey = "approval-required:$approvalId",
                    payloadRef = toolName,
                )
                refreshActiveCount()
                created = approval
            }
        }
        val db = database
        if (db == null) operation() else db.withTransaction { operation() }
        created
    }

    /**
     * 仅将与本次工具参数和资源版本完全一致的批准消费一次。
     *
     * 用户批准后显式继续任务会创建新 run；原审批仍保留创建时的 run/step 供审计，
     * 该 CAS 票据只允许相同任务 revision 下相同工具参数通过一次。
     */
    suspend fun consumeApprovedApproval(
        taskId: String,
        runId: String,
        taskRevision: Int,
        stepId: String,
        toolName: String,
        argumentsHash: String,
        resourceVersion: String,
        sideEffect: String,
    ): ApprovalRequestEntity? = writeLock.withLock {
        var consumed: ApprovalRequestEntity? = null
        val operation: suspend () -> Unit = {
            val task = taskDao.getById(taskId)
            val run = runDao.getById(runId)
            val step = stepDao.listByRun(runId).firstOrNull { it.stepId == stepId }
            if (task == null || run == null || step == null) return@operation
            if (task.status != BotTaskStatus.RUNNING || task.revision != taskRevision || run.taskId != taskId ||
                run.taskRevision != taskRevision || run.status != TaskRunStatus.RUNNING
            ) return@operation
            if (step.toolName != toolName || step.argumentsHash != argumentsHash) return@operation
            val approval = approvalDao.listByTask(taskId).firstOrNull {
                it.status == ApprovalStatus.APPROVED && it.decisionReason.isBlank() &&
                    it.taskRevision == taskRevision && it.toolName == toolName &&
                    it.argumentsHash == argumentsHash && it.resourceVersion == resourceVersion
            }
            if (approval != null && approvalDao.consumeApproved(
                    approvalId = approval.approvalId,
                    taskId = taskId,
                    taskRevision = taskRevision,
                    toolName = toolName,
                    argumentsHash = argumentsHash,
                    resourceVersion = resourceVersion,
                ) == 1
            ) {
                stepDao.upsert(step.copy(approvalId = approval.approvalId, sideEffect = sideEffect))
                consumed = approval.copy(decisionReason = APPROVED_CONSUMED_REASON)
            }
        }
        val db = database
        if (db == null) operation() else db.withTransaction { operation() }
        consumed
    }

    suspend fun attachRunThread(runId: String, threadId: String): TaskRunEntity? = writeLock.withLock {
        if (threadId.isBlank()) return@withLock runDao.getById(runId)
        val run = runDao.getById(runId) ?: return@withLock null
        run.copy(threadId = threadId).also { runDao.upsert(it) }
    }

    suspend fun listSteps(runId: String): List<TaskStepEntity> = stepDao.listByRun(runId)

    // ── 事件 ──────────────────────────────────────────────────────────

    /**
     * 追加事件。同 [dedupeKey] 重复调用只留一条。
     *
     * @return 实际落库的事件；重复时返回既有那条。
     */
    suspend fun appendEvent(
        botId: String,
        originSessionId: String,
        taskId: String,
        runId: String,
        kind: String,
        dedupeKey: String,
        payloadRef: String = "",
    ): BotEventEntity = writeLock.withLock {
        appendEventLocked(botId, originSessionId, taskId, runId, kind, dedupeKey, payloadRef)
    }

    internal suspend fun commitUserInput(
        sessions: io.zer0.muse.data.session.SessionRepository,
        botId: String,
        sessionId: String,
        message: io.zer0.ai.core.UIMessage,
        replacedMessageId: String?,
        payloadRef: String,
    ): BotEventEntity? = writeLock.withLock {
        var event: BotEventEntity? = null
        val committed = sessions.commitAgentInput(sessionId, message, replacedMessageId) {
            val dedupeKey = "input:${message.id}"
            appendEventLocked(
                botId,
                sessionId,
                "",
                "",
                BotEventKind.USER_INPUT_RECEIVED,
                dedupeKey,
                payloadRef,
            )
            event = checkNotNull(eventDao.findByDedupeKey(dedupeKey)) { "Agent input event was not persisted" }
                .also { check(it.botId == botId && it.originSessionId == sessionId && it.kind == BotEventKind.USER_INPUT_RECEIVED) }
        }
        if (committed) checkNotNull(event) else null
    }

    private suspend fun appendEventLocked(
        botId: String,
        originSessionId: String,
        taskId: String,
        runId: String,
        kind: String,
        dedupeKey: String,
        payloadRef: String = "",
    ): BotEventEntity {
        require(kind in BotEventKind.all) { "未知事件类型: $kind" }
        val seq = (eventDao.maxSequence(botId) ?: 0L) + 1
        val event = BotEventEntity(
            eventId = UUID.randomUUID().toString(),
            botId = botId,
            originSessionId = originSessionId,
            taskId = taskId,
            runId = runId,
            kind = kind,
            sequence = seq,
            payloadRef = payloadRef,
            dedupeKey = dedupeKey,
            createdAt = now(),
        )
        val inserted = eventDao.insertIgnore(event)
        if (inserted == -1L) {
            // 已存在：返回既有那条，调用方据此判断"这次是重复投递"。
            return eventDao.findByDedupeKey(dedupeKey) ?: event
        }
        return event
    }

    suspend fun pendingEvents(botId: String, limit: Int = 50): List<BotEventEntity> = eventDao.pending(botId, limit)

    suspend fun pendingEventsAfter(botId: String, sequence: Long, limit: Int): List<BotEventEntity> =
        eventDao.pendingAfter(botId, sequence, limit)

    suspend fun latestUserInputSequence(botId: String, sessionId: String): Long = eventDao.latestUserInputSequence(botId, sessionId) ?: 0L

    /** 消费确认。返回 false 表示已被别人消费过。 */
    suspend fun markConsumed(eventId: String): Boolean = eventDao.markConsumed(eventId, now()) > 0

    /**
     * 保留策略：只清已消费且早于保留期的。
     *
     * 带暂存标记的行不在这里清：页面靠那条事件找到候选，删掉之后仍 PENDING 的候选
     * 会从页面消失、文件却留在磁盘上。它们的清理走 [pruneStagedReferences] ——
     * 只有确认暂存文件已经不在了才允许删。
     */
    suspend fun pruneEvents(retentionMs: Long = EVENT_RETENTION_MS): Int = eventDao.deleteConsumedBefore(now() - retentionMs)

    /**
     * 清理"候选已经不在暂存区"的事件引用。
     *
     * @param liveStagedIds 此刻仍在暂存区的候选 id。由持有暂存区的一方读出来传进来 ——
     *   仓储看不到文件系统，猜"文件还在"只能靠瞎蒙。
     * @return 实际删除的行数
     */
    suspend fun pruneStagedReferences(liveStagedIds: Set<String>, retentionMs: Long = EVENT_RETENTION_MS): Int {
        val settled = eventDao.consumedStagedBefore(now() - retentionMs).filter { event ->
            val stagedIds = stagedIdsFromPayload(event.payloadRef)
            stagedIds.isEmpty() || stagedIds.none { it in liveStagedIds }
        }
        if (settled.isEmpty()) return 0
        return eventDao.deleteByIds(settled.map { it.eventId })
    }

    // ── 审批 ──────────────────────────────────────────────────────────

    suspend fun createApproval(
        botId: String,
        originSessionId: String,
        taskId: String,
        runId: String,
        stepId: String,
        taskRevision: Int,
        toolCallId: String,
        toolName: String,
        argumentsRef: String,
        argumentsHash: String,
        resourceVersion: String,
        risk: String,
        ttlMs: Long = APPROVAL_TTL_MS,
    ): ApprovalRequestEntity = writeLock.withLock {
        // 幂等：同一个 (run, step) 已经有挂着的审批时直接返回它，不再建第二张。
        //
        // V24 要求"重复不生成第二张"。用户看到两张一模一样的卡片会困惑：
        // 该点哪个？点了一个另一个还在。这比"没有卡片"更糟。
        //
        // ## 幂等键必须是 (runId, stepId) 而不是 stepId
        //
        // stepCounter 是 launchRun 的局部变量，每个 run 都从 0 开始。
        // 只用 stepId 做键的话，第二个 run 的第 0 步会命中第一个 run 的第 0 步审批 ——
        // 那张卡可能已过期，也可能指向完全不同的参数。这是**跨 run 串审批**。
        //
        // 只复用 PENDING 的：已结算的（批准/拒绝/过期）不该被"复活"成新的待办。
        if (stepId.isNotBlank()) {
            val existing = approvalDao.listPending().firstOrNull {
                it.runId == runId && it.stepId == stepId && it.status == ApprovalStatus.PENDING
            }
            if (existing != null) {
                Logger.d("AgentBotRepo", "同一 run 的同一步已有待审批，复用: $runId/$stepId")
                return@withLock existing
            }
        }
        val ts = now()
        val approval = ApprovalRequestEntity(
            approvalId = UUID.randomUUID().toString(),
            botId = botId,
            originSessionId = originSessionId,
            taskId = taskId,
            runId = runId,
            stepId = stepId,
            taskRevision = taskRevision,
            toolCallId = toolCallId,
            toolName = toolName,
            argumentsRef = argumentsRef,
            argumentsHash = argumentsHash,
            resourceVersion = resourceVersion,
            risk = risk,
            status = ApprovalStatus.PENDING,
            createdAt = ts,
            // readyAt 与 createdAt 分开：卡片"可被读取"不等于"用户已看到"。
            readyAt = ts,
            expiresAt = ts + ttlMs,
        )
        approvalDao.upsert(approval)
        approval
    }

    /**
     * 结算审批。CAS 只成功一次。
     *
     * @return true = 本次结算成功；false = 已被结算过（返回既有状态由调用方查）。
     */
    suspend fun settleApproval(approvalId: String, status: String, actor: String, reason: String = ""): Boolean {
        require(status in ApprovalStatus.SETTLED) { "非法结算状态: $status" }
        return approvalDao.settle(approvalId, status, now(), actor, reason) > 0
    }

    suspend fun expireApprovals(): Int = approvalDao.expireOverdue(now(), "timeout")

    suspend fun getApproval(approvalId: String): ApprovalRequestEntity? = approvalDao.getById(approvalId)

    suspend fun pendingApprovals(): List<ApprovalRequestEntity> = approvalDao.listPending()

    /**
     * 某个任务的全部审批（含已结算）。
     *
     * 用于区分 NEEDS_USER 的两种来源：有已结算批准 → "审批通过待重跑"；
     * 没有 → "执行需要决策"。
     */
    suspend fun listApprovals(taskId: String): List<ApprovalRequestEntity> = approvalDao.listByTask(taskId)

    // ── 发布 ──────────────────────────────────────────────────────────

    /**
     * 把一批对外 item 写进发件箱。
     *
     * item 的 `publicationId + itemIndex` 唯一，重复调用不会产生两份。
     */
    suspend fun enqueuePublication(
        botId: String,
        originSessionId: String,
        causeEventIds: String,
        requestRevision: Long,
        taskRevision: Int,
        items: List<Pair<String, String>>,
    ): String = writeLock.withLock {
        val publicationId = UUID.randomUUID().toString()
        val ts = now()
        items.forEachIndexed { index, item ->
            val kind = item.first
            val payloadRef = item.second
            require(kind in PublicationKind.all) { "非法发布类型: $kind" }
            publicationDao.upsert(
                PublicationOutboxEntity(
                    id = "$publicationId:$index",
                    publicationId = publicationId,
                    botId = botId,
                    originSessionId = originSessionId,
                    causeEventIds = causeEventIds,
                    requestRevision = requestRevision,
                    taskRevision = taskRevision,
                    itemIndex = index,
                    kind = kind,
                    payloadRef = payloadRef,
                    status = PublicationStatus.PENDING,
                    createdAt = ts,
                ),
            )
        }
        publicationId
    }

    /** 标记某条 item 已落进公共消息。已提交的不会被改写。 */
    suspend fun commitPublication(itemId: String, messageId: String): Boolean =
        publicationDao.markCommitted(itemId, PublicationStatus.COMMITTED, messageId) > 0

    /**
     * 放弃某批次里还没发出的 item。
     *
     * 已提交的条目不动 —— 用户看到的消息不能凭空消失。
     */
    suspend fun supersedePublication(publicationId: String): Int = publicationDao.supersedePending(publicationId)

    suspend fun pendingPublications(): List<PublicationOutboxEntity> = publicationDao.listPending()

    // ── 绑定 ──────────────────────────────────────────────────────────

    suspend fun upsertBinding(binding: BotBindingEntity) = bindingDao.upsert(binding)

    suspend fun getBinding(botId: String): BotBindingEntity? = bindingDao.getById(botId)

    suspend fun enabledBindings(): List<BotBindingEntity> = bindingDao.listEnabled()

    /**
     * 确保助手与 canonical 会话之间有稳定绑定。同一对参数重复调用返回同一行。
     *
     * ## botId 为什么就是 assistantId
     *
     * 页面、任务、事件、审批一直用 assistantId 作为 botId 落库（宿主把
     * `currentAssistant.id` 直接传给绑定入口）。改成 assistant + session 的复合键
     * 会让升级前的任务全部从页面上消失，而且后台消费者按 botId 扫绑定时
     * 会与既有数据对不上。所以主键保持 assistantId，会话 id 只作为行内字段。
     *
     * ## 幂等怎么保证
     *
     * 先按主键读。参数完全一致时直接返回既有行 —— **不刷新 updatedAt**，
     * 否则"重复打开页面"会不断制造写入，备份与审计里全是无意义的变更。
     * 只有会话或助手真正变化时才写。
     */
    suspend fun ensureBinding(assistantId: String, canonicalAgentSessionId: String): BotBindingEntity = writeLock.withLock {
        require(assistantId.isNotBlank()) { "assistantId 不能为空：没有归属的绑定无法回读" }
        require(canonicalAgentSessionId.isNotBlank()) {
            "canonicalAgentSessionId 不能为空：空绑定会让页面每次重开都丢掉会话"
        }
        val existing = bindingDao.getById(assistantId)
        if (existing != null &&
            existing.assistantId == assistantId &&
            existing.canonicalAgentSessionId == canonicalAgentSessionId
        ) {
            return@withLock existing
        }
        val ts = now()
        val binding = BotBindingEntity(
            botId = assistantId,
            assistantId = assistantId,
            canonicalAgentSessionId = canonicalAgentSessionId,
            // 已有行被显式停用时，重进页面不应把它悄悄打开。
            enabled = existing?.enabled ?: true,
            schemaVersion = existing?.schemaVersion ?: 1,
            createdAt = existing?.createdAt ?: ts,
            updatedAt = ts,
        )
        bindingDao.upsert(binding)
        binding
    }

    /** 页面在只有 assistantId 时回读 canonical 会话。 */
    suspend fun getBindingForAssistant(assistantId: String): BotBindingEntity? = bindingDao.getById(assistantId)

    /**
     * 观察当前页面的作用域快照。
     *
     * 任务、审批来自 Room Flow，候选 id 来自同作用域的事件 payload；
     * 三者按 botId + originSessionId 同时收窄。**不在 UI 层先查全局再过滤** ——
     * 那样任何一处忘记过滤都会把别的 Bot 的卡显示出来。
     */
    fun observeScope(assistantId: String, sessionId: String): Flow<AgentBotScope> {
        require(assistantId.isNotBlank()) { "assistantId 不能为空：作用域没有归属" }
        require(sessionId.isNotBlank()) { "sessionId 不能为空：作用域没有归属" }
        val baseFlow = combine(
            taskDao.observeByScope(assistantId, sessionId),
            approvalDao.observeByScope(assistantId, sessionId),
            eventDao.observeStagedPayloads(assistantId, sessionId),
        ) { tasks, approvals, payloads ->
            AgentBotScope(
                assistantId = assistantId,
                sessionId = sessionId,
                tasks = tasks,
                approvals = approvals,
                candidateIds = payloads.flatMapTo(mutableSetOf(), ::stagedIdsFromPayload),
            )
        }
        val annotationsFlow = annotationDao?.observeActiveBySession(sessionId)
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
        val cardsFlow = cardDao?.observeByScope(assistantId, sessionId)
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
        val stepsFlow = if (database != null) {
            stepDao.observeByScope(assistantId, sessionId)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
        return combine(baseFlow, annotationsFlow, cardsFlow, stepsFlow) { scope, annotations, cards, steps ->
            scope.copy(
                annotations = annotations,
                cards = cards,
                steps = steps,
            )
        }.distinctUntilChanged()
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    /**
     * 从事件 payload 末尾取暂存 id。
     *
     * 必须从后往前找：摘要正文是模型输出，里面可能恰好出现 `[staged=`；
     * 写入侧总是把真实标记追加在最后。
     */
    private fun stagedIdsFromPayload(payloadRef: String): List<String> {
        val start = payloadRef.lastIndexOf(BotResultApplier.STAGED_MARKER)
        if (start < 0) return emptyList()
        val end = payloadRef.indexOf(']', start)
        if (end < 0) return emptyList()
        return payloadRef.substring(start + BotResultApplier.STAGED_MARKER.length, end)
            .split(',')
            .map(String::trim)
            .filter { id ->
                val suffix = id.removePrefix(STAGED_ID_PREFIX)
                id.startsWith(STAGED_ID_PREFIX) && suffix.isNotEmpty() && suffix.all(Char::isDigit)
            }
    }

    private suspend fun refreshActiveCount() {
        runCatching { _activeTaskCount.value = taskDao.countActive() }
            .onFailure { Logger.w("AgentBotRepo", "在途任务计数刷新失败: ${it.message}") }
    }

    private suspend fun settleRoutineTask(task: BotTaskEntity, status: String, at: Long) {
        if (!BotTaskStatus.isTerminal(status)) return
        val link = parseRoutineTaskLink(task.requestMessageId) ?: return
        routineDao?.settleExecution(
            link.first,
            link.second,
            at,
            status,
        )
    }

    private fun parseRoutineTaskLink(value: String): Pair<String, Long>? {
        if (!value.startsWith(ROUTINE_TASK_PREFIX)) return null
        val payload = value.removePrefix(ROUTINE_TASK_PREFIX)
        val timestampSeparator = payload.lastIndexOf(':')
        if (timestampSeparator <= 0) return null
        val runAt = payload.substring(timestampSeparator + 1).toLongOrNull() ?: return null
        return payload.substring(0, timestampSeparator) to runAt
    }

    companion object {
        private const val APPROVED_CONSUMED_REASON = "consumed_for_resume"

        /** 租约时长。短到能及时发现死 worker，长到不会把正常慢任务误判成掉线。 */
        const val LEASE_MS = 60_000L

        /** 审批交互期限。从 readyAt 起算，与 createdAt 无关。 */
        const val APPROVAL_TTL_MS = 10 * 60_000L

        /** 事件保留期。必须晚于"消费成功 + 任务终态 + 审计留存"三者最晚者。 */
        const val EVENT_RETENTION_MS = 7 * 24 * 3600_000L

        /** 暂存候选 id 前缀。格式由 [StagedArtifactStore.stage] 生成。 */
        private const val STAGED_ID_PREFIX = "cand-"
        private const val ROUTINE_TASK_PREFIX = "routine-task:"
    }
}
