package io.zer0.muse.data.agentbot

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 任务账本的读写口。
 *
 * 只做"存取"，不做状态判断 —— 转移规则全在 [BotTaskStatusMachine]，
 * 那里能用穷尽 when 把非法转移变成编译期错误。DAO 里散落 if 判断，
 * 迟早会出现两个入口各自允许一半的状态。
 */
@Dao
interface BotTaskDao {

    @Upsert
    suspend fun upsert(task: BotTaskEntity)

    /** 备份恢复的批量入口。 */
    @Upsert
    suspend fun upsertAll(tasks: List<BotTaskEntity>)

    @Query("SELECT * FROM bot_tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun getById(taskId: String): BotTaskEntity?

    @Query("SELECT * FROM bot_tasks WHERE botId = :botId ORDER BY updatedAt DESC")
    suspend fun listByBot(botId: String): List<BotTaskEntity>

    @Query("SELECT * FROM bot_tasks WHERE originSessionId = :sessionId")
    suspend fun listByOriginSession(sessionId: String): List<BotTaskEntity>

    @Query(
        "SELECT * FROM bot_tasks WHERE botId IN " +
            "(SELECT botId FROM bot_bindings WHERE assistantId = :assistantId)",
    )
    suspend fun listByAssistant(assistantId: String): List<BotTaskEntity>

    @Query("SELECT * FROM bot_tasks WHERE status = 'REVIEWING' ORDER BY updatedAt ASC")
    suspend fun listReviewing(): List<BotTaskEntity>

    @Query("SELECT * FROM bot_tasks WHERE status = 'RUNNING'")
    suspend fun listRunningTasks(): List<BotTaskEntity>

    /**
     * 页面投影用：只观察当前 assistant 与 canonical 会话的任务。
     *
     * 与 [listByBot] 分开是刻意的：调度器按 Bot 领任务（跨会话），
     * 页面只看当前会话；两者混用会让别的会话的任务出现在看板上。
     */
    @Query("SELECT * FROM bot_tasks WHERE botId = :botId AND originSessionId = :sessionId ORDER BY updatedAt DESC")
    fun observeByScope(botId: String, sessionId: String): Flow<List<BotTaskEntity>>

    @Query("SELECT * FROM bot_tasks WHERE botId = :botId AND status IN (:statuses) ORDER BY priority DESC, createdAt ASC")
    fun observeByStatuses(botId: String, statuses: List<String>): Flow<List<BotTaskEntity>>

    /** 可被 worker 领取的任务：排队中，按优先级与创建时间。 */
    @Query("SELECT * FROM bot_tasks WHERE status = 'QUEUED' ORDER BY priority DESC, createdAt ASC LIMIT :limit")
    suspend fun claimCandidates(limit: Int): List<BotTaskEntity>

    /**
     * 等资源的任务（按优先级）。
     *
     * 单独一条查询而不是复用 [claimCandidates]：后者只查 QUEUED，
     * 拿它过滤 WAITING_RESOURCE 永远是空 —— 那会让恢复分支变成死代码。
     */
    @Query(
        "SELECT * FROM bot_tasks WHERE status = 'WAITING_RESOURCE' " +
            "ORDER BY priority DESC, createdAt ASC LIMIT :limit",
    )
    suspend fun parkedForResource(limit: Int): List<BotTaskEntity>

    /** 备份导出用。 */
    @Query("SELECT * FROM bot_tasks")
    suspend fun getAll(): List<BotTaskEntity>

    @Query("DELETE FROM bot_tasks WHERE taskId = :taskId")
    suspend fun deleteById(taskId: String)

    @Query("DELETE FROM bot_tasks WHERE taskId IN (:taskIds)")
    suspend fun deleteByIds(taskIds: List<String>)

    @Query("DELETE FROM bot_tasks")
    suspend fun deleteAll()

    @Query(
        "SELECT COUNT(*) FROM bot_tasks WHERE status IN (" +
            "'QUEUED','RUNNING','WAITING_RESOURCE','WAITING_APPROVAL','REVIEWING','CANCEL_REQUESTED')",
    )
    suspend fun countActive(): Int
}

@Dao
interface TaskRunDao {

    @Upsert
    suspend fun upsert(run: TaskRunEntity)

    @Upsert
    suspend fun upsertAll(runs: List<TaskRunEntity>)

    @Query("SELECT * FROM bot_task_runs WHERE runId = :runId LIMIT 1")
    suspend fun getById(runId: String): TaskRunEntity?

    @Query("SELECT * FROM bot_task_runs WHERE taskId = :taskId ORDER BY attempt DESC")
    suspend fun listByTask(taskId: String): List<TaskRunEntity>

    @Query("SELECT * FROM bot_task_runs WHERE status = 'RUNNING'")
    suspend fun listRunning(): List<TaskRunEntity>

    /** 租约到期的 run：重启后对账用。 */
    @Query("SELECT * FROM bot_task_runs WHERE status = 'RUNNING' AND leaseUntil < :now")
    suspend fun listStale(now: Long): List<TaskRunEntity>

    @Query("SELECT * FROM bot_task_runs")
    suspend fun getAll(): List<TaskRunEntity>

    @Query("DELETE FROM bot_task_runs WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)

    @Query("DELETE FROM bot_task_runs WHERE taskId IN (:taskIds)")
    suspend fun deleteByTasks(taskIds: List<String>)

    @Query("DELETE FROM bot_task_runs")
    suspend fun deleteAll()
}

@Dao
interface TaskStepDao {

    @Upsert
    suspend fun upsert(step: TaskStepEntity)

    @Upsert
    suspend fun upsertAll(steps: List<TaskStepEntity>)

    @Query("SELECT * FROM bot_task_steps WHERE runId = :runId ORDER BY stepIndex ASC")
    suspend fun listByRun(runId: String): List<TaskStepEntity>

    /** 按作用域观察当前会话所有任务的步骤记录，用于重启后完整还原动态任务卡时间线。 */
    @Query(
        "SELECT s.* FROM bot_task_steps s " +
            "INNER JOIN bot_task_runs r ON s.runId = r.runId " +
            "INNER JOIN bot_tasks t ON r.taskId = t.taskId " +
            "WHERE t.botId = :botId AND t.originSessionId = :sessionId " +
            "ORDER BY s.startedAt ASC, s.stepIndex ASC",
    )
    fun observeByScope(botId: String, sessionId: String): Flow<List<TaskStepEntity>>

    @Query(
        "SELECT s.* FROM bot_task_steps s " +
            "INNER JOIN bot_task_runs r ON s.runId = r.runId " +
            "WHERE r.taskId = :taskId " +
            "ORDER BY r.attempt DESC, s.stepIndex ASC",
    )
    suspend fun listByTask(taskId: String): List<TaskStepEntity>

    @Query("SELECT * FROM bot_task_steps")
    suspend fun getAll(): List<TaskStepEntity>

    @Query("DELETE FROM bot_task_steps WHERE runId = :runId")
    suspend fun deleteByRun(runId: String)

    @Query("DELETE FROM bot_task_steps WHERE runId IN (:runIds)")
    suspend fun deleteByRuns(runIds: List<String>)

    @Query("DELETE FROM bot_task_steps")
    suspend fun deleteAll()
}

@Dao
interface BotEventDao {

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(event: BotEventEntity): Long

    @Upsert
    suspend fun upsert(event: BotEventEntity)

    @Upsert
    suspend fun upsertAll(events: List<BotEventEntity>)

    @Query("SELECT * FROM bot_events WHERE eventId = :eventId LIMIT 1")
    suspend fun getById(eventId: String): BotEventEntity?

    @Query("SELECT * FROM bot_events WHERE dedupeKey = :dedupeKey LIMIT 1")
    suspend fun findByDedupeKey(dedupeKey: String): BotEventEntity?

    @Query("DELETE FROM bot_events WHERE eventId = :eventId")
    suspend fun deleteById(eventId: String)

    @Query("DELETE FROM bot_events WHERE kind = :kind AND consumedAt > 0 AND createdAt < :cutoff")
    suspend fun deleteConsumedBefore(kind: String, cutoff: Long): Int

    /**
     * 某个任务某一轮里最后一次某类事件。
     *
     * 发布前要拿它复核"审核结论仍是允许发布的那一份" —— 消费方不能只信
     * 发布事实自己带的结论，否则一条过期的发布事实会以审核名义发出去。
     */
    @Query(
        "SELECT * FROM bot_events WHERE taskId = :taskId AND runId = :runId AND kind = :kind " +
            "ORDER BY sequence DESC LIMIT 1",
    )
    suspend fun latestOfKind(taskId: String, runId: String, kind: String): BotEventEntity?

    /** 未消费事件，按序号升序 —— 顺序对"先接单后汇报"是语义要求。 */
    @Query("SELECT * FROM bot_events WHERE botId = :botId AND consumedAt = 0 ORDER BY sequence ASC LIMIT :limit")
    suspend fun pending(botId: String, limit: Int): List<BotEventEntity>

    @Query(
        "SELECT * FROM bot_events WHERE botId = :botId AND consumedAt = 0 AND sequence > :afterSequence " +
            "ORDER BY sequence ASC LIMIT :limit",
    )
    suspend fun pendingAfter(botId: String, afterSequence: Long, limit: Int): List<BotEventEntity>

    @Query(
        "SELECT MAX(sequence) FROM bot_events WHERE botId = :botId AND originSessionId = :sessionId " +
            "AND kind = 'UserInputReceived'",
    )
    suspend fun latestUserInputSequence(botId: String, sessionId: String): Long?

    @Query("SELECT * FROM bot_events WHERE botId = :botId AND consumedAt = 0 ORDER BY sequence ASC LIMIT :limit")
    fun observePending(botId: String, limit: Int): Flow<List<BotEventEntity>>

    /**
     * 页面投影用：当前作用域里带暂存候选标记的事件 payload。
     *
     * 候选本体是暂存区里的文件，没有独立的 Room 表；它与所属页面的唯一持久联系
     * 就是事件 payload 末尾的 `[staged=...]`。按作用域读 payload 是唯一能把
     * 别的 Bot 的候选挡在页面之外的办法。
     */
    @Query(
        "SELECT payloadRef FROM bot_events " +
            "WHERE botId = :botId AND originSessionId = :sessionId AND payloadRef LIKE '%[staged=%'",
    )
    fun observeStagedPayloads(botId: String, sessionId: String): Flow<List<String>>

    /** 消费确认：只把 consumedAt 从 0 改成时间戳，重复调用无副作用。 */
    @Query("UPDATE bot_events SET consumedAt = :at WHERE eventId = :eventId AND consumedAt = 0")
    suspend fun markConsumed(eventId: String, at: Long): Int

    @Query("SELECT MAX(sequence) FROM bot_events WHERE botId = :botId")
    suspend fun maxSequence(botId: String): Long?

    @Query("SELECT * FROM bot_events")
    suspend fun getAll(): List<BotEventEntity>

    /**
     * 保留策略：只删已消费且早于 cutoff 的。未消费的永不删除。
     *
     * 带暂存标记的行**不在**清理范围内：页面靠它找到候选，删掉就等于让一个
     * 仍 PENDING 的候选从页面上消失（文件还在磁盘上，用户却再也看不到）。
     * 这些行由 [consumedStagedBefore] + [deleteByIds] 在候选文件消失后收走。
     */
    @Query(
        "DELETE FROM bot_events WHERE consumedAt > 0 AND consumedAt < :cutoff " +
            "AND payloadRef NOT LIKE '%[staged=%' AND kind != 'ComputeExecOwner'",
    )
    suspend fun deleteConsumedBefore(cutoff: Long): Int

    /**
     * 带暂存标记、已消费且早于 cutoff 的事件。
     *
     * 文件系统不在 SQL 的视野内，所以这里只负责"排出候选"，文件是否还在
     * 由调用方核对后再决定删哪几条。
     */
    @Query(
        "SELECT * FROM bot_events WHERE consumedAt > 0 AND consumedAt < :cutoff " +
            "AND payloadRef LIKE '%[staged=%'",
    )
    suspend fun consumedStagedBefore(cutoff: Long): List<BotEventEntity>

    /** 按 id 删除。只给"候选已不在暂存区"的引用清理用。 */
    @Query("DELETE FROM bot_events WHERE eventId IN (:eventIds)")
    suspend fun deleteByIds(eventIds: List<String>): Int

    @Query("DELETE FROM bot_events WHERE taskId = :taskId AND consumedAt > 0")
    suspend fun deleteConsumedByTask(taskId: String)

    @Query("DELETE FROM bot_events WHERE originSessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM bot_events WHERE taskId IN (:taskIds)")
    suspend fun deleteByTasks(taskIds: List<String>)

    @Query("DELETE FROM bot_events")
    suspend fun deleteAll()
}

@Dao
interface ApprovalRequestDao {

    @Upsert
    suspend fun upsert(approval: ApprovalRequestEntity)

    @Upsert
    suspend fun upsertAll(approvals: List<ApprovalRequestEntity>)

    @Query("SELECT * FROM bot_approvals WHERE approvalId = :approvalId LIMIT 1")
    suspend fun getById(approvalId: String): ApprovalRequestEntity?

    @Query("SELECT * FROM bot_approvals WHERE taskId = :taskId ORDER BY createdAt DESC")
    suspend fun listByTask(taskId: String): List<ApprovalRequestEntity>

    @Query("SELECT * FROM bot_approvals WHERE status = 'PENDING' ORDER BY readyAt ASC")
    fun observePending(): Flow<List<ApprovalRequestEntity>>

    /**
     * 页面投影用：当前 assistant 与 canonical 会话的全部审批（含已结算）。
     *
     * 已结算的行不能丢：NEEDS_USER 的两条来源（审批通过待重跑 / 执行需要决策）
     * 只能靠"该任务有没有已结算批准且已无待审批"来区分。
     */
    @Query("SELECT * FROM bot_approvals WHERE botId = :botId AND originSessionId = :sessionId ORDER BY readyAt ASC")
    fun observeByScope(botId: String, sessionId: String): Flow<List<ApprovalRequestEntity>>

    @Query("SELECT * FROM bot_approvals WHERE status = 'PENDING'")
    suspend fun listPending(): List<ApprovalRequestEntity>

    /**
     * CAS 结算：只有仍处于 PENDING 才写入。
     *
     * 返回受影响行数，0 表示"已经被别人结算过"——调用方据此返回已结算状态，
     * 而不是覆盖掉先前的结果。双击"批准"不会执行两次。
     */
    @Query(
        "UPDATE bot_approvals SET status = :status, resolvedAt = :at, decisionActor = :actor, decisionReason = :reason " +
            "WHERE approvalId = :approvalId AND status = 'PENDING'",
    )
    suspend fun settle(approvalId: String, status: String, at: Long, actor: String, reason: String): Int

    @Query(
        "UPDATE bot_approvals SET decisionReason = 'consumed_for_resume' " +
            "WHERE approvalId = :approvalId AND taskId = :taskId AND taskRevision = :taskRevision " +
            "AND toolName = :toolName AND argumentsHash = :argumentsHash AND resourceVersion = :resourceVersion " +
            "AND status = 'APPROVED' AND decisionReason = ''",
    )
    suspend fun consumeApproved(
        approvalId: String,
        taskId: String,
        taskRevision: Int,
        toolName: String,
        argumentsHash: String,
        resourceVersion: String,
    ): Int

    /** 过期扫描：把超过 expiresAt 的 PENDING 标成 EXPIRED。 */
    @Query(
        "UPDATE bot_approvals SET status = 'EXPIRED', resolvedAt = :now, " +
            "decisionActor = 'system-expired', decisionReason = :reason " +
            "WHERE status = 'PENDING' AND expiresAt > 0 AND expiresAt < :now",
    )
    suspend fun expireOverdue(now: Long, reason: String): Int

    @Query("SELECT * FROM bot_approvals")
    suspend fun getAll(): List<ApprovalRequestEntity>

    @Query("DELETE FROM bot_approvals WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)

    @Query("DELETE FROM bot_approvals WHERE originSessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM bot_approvals WHERE taskId IN (:taskIds)")
    suspend fun deleteByTasks(taskIds: List<String>)

    @Query("DELETE FROM bot_approvals")
    suspend fun deleteAll()
}

@Dao
interface PublicationOutboxDao {

    @Upsert
    suspend fun upsert(item: PublicationOutboxEntity)

    @Upsert
    suspend fun upsertAll(items: List<PublicationOutboxEntity>)

    @Query("SELECT * FROM bot_publications WHERE publicationId = :publicationId ORDER BY itemIndex ASC")
    suspend fun listByPublication(publicationId: String): List<PublicationOutboxEntity>

    @Query("SELECT * FROM bot_publications WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun listPending(): List<PublicationOutboxEntity>

    @Query("UPDATE bot_publications SET status = :status, messageId = :messageId WHERE id = :id AND status = 'PENDING'")
    suspend fun markCommitted(id: String, status: String, messageId: String): Int

    /** 新输入到达时，把旧批次里还没发的标成 SUPERSEDED。已发的条目不动。 */
    @Query("UPDATE bot_publications SET status = 'SUPERSEDED' WHERE publicationId = :publicationId AND status = 'PENDING'")
    suspend fun supersedePending(publicationId: String): Int

    @Query("SELECT * FROM bot_publications")
    suspend fun getAll(): List<PublicationOutboxEntity>

    @Query("DELETE FROM bot_publications WHERE originSessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM bot_publications")
    suspend fun deleteAll()
}

@Dao
interface BotBindingDao {

    @Upsert
    suspend fun upsert(binding: BotBindingEntity)

    @Upsert
    suspend fun upsertAll(bindings: List<BotBindingEntity>)

    @Query("SELECT * FROM bot_bindings WHERE botId = :botId LIMIT 1")
    suspend fun getById(botId: String): BotBindingEntity?

    @Query("SELECT * FROM bot_bindings WHERE enabled = 1")
    suspend fun listEnabled(): List<BotBindingEntity>

    @Query("SELECT * FROM bot_bindings")
    fun observeAll(): Flow<List<BotBindingEntity>>

    @Query("SELECT * FROM bot_bindings")
    suspend fun getAll(): List<BotBindingEntity>

    @Query("SELECT * FROM bot_bindings WHERE canonicalAgentSessionId = :sessionId")
    suspend fun listBySession(sessionId: String): List<BotBindingEntity>

    @Query("SELECT * FROM bot_bindings WHERE assistantId = :assistantId")
    suspend fun listByAssistant(assistantId: String): List<BotBindingEntity>

    @Query("DELETE FROM bot_bindings WHERE canonicalAgentSessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM bot_bindings WHERE assistantId = :assistantId")
    suspend fun deleteByAssistant(assistantId: String)

    @Query("DELETE FROM bot_bindings")
    suspend fun deleteAll()
}
