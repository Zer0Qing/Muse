package io.zer0.muse.data.agentbot

import io.zer0.common.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

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
    private val taskDao: BotTaskDao,
    private val runDao: TaskRunDao,
    private val stepDao: TaskStepDao,
    private val eventDao: BotEventDao,
    private val approvalDao: ApprovalRequestDao,
    private val publicationDao: PublicationOutboxDao,
    private val bindingDao: BotBindingDao,
    /** 注入时钟，便于测试断言而不必真等。 */
    private val now: () -> Long = { System.currentTimeMillis() },
) {

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
        if (dedupeKey.isNotBlank()) {
            val existing = eventDao.findByDedupeKey(dedupeKey)
            val existingTaskId = existing?.taskId.orEmpty()
            if (existingTaskId.isNotBlank()) {
                taskDao.getById(existingTaskId)?.let { return@withLock it }
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
        task
    }

    /**
     * 施加一次状态转移。
     *
     * @return 转移后的任务；非法转移（迟到事件 / 重复事件）返回 null，**不改库**。
     */
    suspend fun transition(taskId: String, event: BotTaskStatusMachine.Event): BotTaskEntity? =
        writeLock.withLock {
            val current = taskDao.getById(taskId) ?: return@withLock null
            val next = BotTaskStatusMachine.next(current.status, event) ?: run {
                // 迟到事件不是错误，是正常并发的结果；记一条便于诊断即可。
                Logger.d("AgentBotRepo", "忽略非法转移: ${current.status} + $event (task=$taskId)")
                return@withLock null
            }
            val updated = current.copy(status = next, updatedAt = now())
            taskDao.upsert(updated)
            refreshActiveCount()
            updated
        }

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
        val attempt = (runDao.listByTask(taskId).maxOfOrNull { it.attempt } ?: 0) + 1
        val ts = now()
        val run = TaskRunEntity(
            runId = UUID.randomUUID().toString(),
            taskId = taskId,
            attempt = attempt,
            threadId = threadId,
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

    suspend fun closeRun(runId: String, status: String, errorCode: String = ""): TaskRunEntity? =
        writeLock.withLock {
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
        )
        stepDao.upsert(step)
        step
    }

    suspend fun finishStep(step: TaskStepEntity): TaskStepEntity = writeLock.withLock {
        val updated = step.copy(finishedAt = now())
        stepDao.upsert(updated)
        updated
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

    suspend fun pendingEvents(botId: String, limit: Int = 50): List<BotEventEntity> =
        eventDao.pending(botId, limit)

    /** 消费确认。返回 false 表示已被别人消费过。 */
    suspend fun markConsumed(eventId: String): Boolean = eventDao.markConsumed(eventId, now()) > 0

    /** 保留策略：只清已消费且早于保留期的。 */
    suspend fun pruneEvents(retentionMs: Long = EVENT_RETENTION_MS): Int =
        eventDao.deleteConsumedBefore(now() - retentionMs)

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
    suspend fun settleApproval(
        approvalId: String,
        status: String,
        actor: String,
        reason: String = "",
    ): Boolean {
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
    suspend fun listApprovals(taskId: String): List<ApprovalRequestEntity> =
        approvalDao.listByTask(taskId)

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
    suspend fun supersedePublication(publicationId: String): Int =
        publicationDao.supersedePending(publicationId)

    suspend fun pendingPublications(): List<PublicationOutboxEntity> = publicationDao.listPending()

    // ── 绑定 ──────────────────────────────────────────────────────────

    suspend fun upsertBinding(binding: BotBindingEntity) = bindingDao.upsert(binding)

    suspend fun getBinding(botId: String): BotBindingEntity? = bindingDao.getById(botId)

    suspend fun enabledBindings(): List<BotBindingEntity> = bindingDao.listEnabled()

    // ── 内部 ──────────────────────────────────────────────────────────

    private suspend fun refreshActiveCount() {
        runCatching { _activeTaskCount.value = taskDao.countActive() }
            .onFailure { Logger.w("AgentBotRepo", "在途任务计数刷新失败: ${it.message}") }
    }

    companion object {
        private const val TAG = "AgentBotRepo"

        /** 租约时长。短到能及时发现死 worker，长到不会把正常慢任务误判成掉线。 */
        const val LEASE_MS = 60_000L

        /** 审批交互期限。从 readyAt 起算，与 createdAt 无关。 */
        const val APPROVAL_TTL_MS = 10 * 60_000L

        /** 事件保留期。必须晚于"消费成功 + 任务终态 + 审计留存"三者最晚者。 */
        const val EVENT_RETENTION_MS = 7 * 24 * 3600_000L
    }
}