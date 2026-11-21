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

    @Query("DELETE FROM bot_tasks")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM bot_tasks WHERE status IN ('QUEUED','RUNNING','WAITING_RESOURCE','WAITING_APPROVAL','REVIEWING','CANCEL_REQUESTED')")
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
    @Query("SELECT * FROM bot_task_runs WHERE status IN ('RUNNING','WAITING_APPROVAL','WAITING_RESOURCE') AND leaseUntil < :now")
    suspend fun listStale(now: Long): List<TaskRunEntity>

    @Query("SELECT * FROM bot_task_runs")
    suspend fun getAll(): List<TaskRunEntity>

    @Query("DELETE FROM bot_task_runs WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)

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

    @Query("SELECT * FROM bot_task_steps")
    suspend fun getAll(): List<TaskStepEntity>

    @Query("DELETE FROM bot_task_steps WHERE runId = :runId")
    suspend fun deleteByRun(runId: String)

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

    /** 未消费事件，按序号升序 —— 顺序对"先接单后汇报"是语义要求。 */
    @Query("SELECT * FROM bot_events WHERE botId = :botId AND consumedAt = 0 ORDER BY sequence ASC LIMIT :limit")
    suspend fun pending(botId: String, limit: Int): List<BotEventEntity>

    @Query("SELECT * FROM bot_events WHERE botId = :botId AND consumedAt = 0 ORDER BY sequence ASC LIMIT :limit")
    fun observePending(botId: String, limit: Int): Flow<List<BotEventEntity>>

    /** 消费确认：只把 consumedAt 从 0 改成时间戳，重复调用无副作用。 */
    @Query("UPDATE bot_events SET consumedAt = :at WHERE eventId = :eventId AND consumedAt = 0")
    suspend fun markConsumed(eventId: String, at: Long): Int

    @Query("SELECT MAX(sequence) FROM bot_events WHERE botId = :botId")
    suspend fun maxSequence(botId: String): Long?

    @Query("SELECT * FROM bot_events")
    suspend fun getAll(): List<BotEventEntity>

    /** 保留策略：只删已消费且早于 cutoff 的。未消费的永不删除。 */
    @Query("DELETE FROM bot_events WHERE consumedAt > 0 AND consumedAt < :cutoff")
    suspend fun deleteConsumedBefore(cutoff: Long): Int

    @Query("DELETE FROM bot_events WHERE taskId = :taskId AND consumedAt > 0")
    suspend fun deleteConsumedByTask(taskId: String)

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

    @Query("DELETE FROM bot_bindings")
    suspend fun deleteAll()
}