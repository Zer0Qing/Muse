package io.zer0.muse.data.proactive

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * backlog 派发账的读写口。
 *
 * 状态流转全部走「带旧状态 / token / 时间条件的 UPDATE 并返回 affected rows」：
 * 调度器可能有多份同时在跑，先读后写会在读与写之间被另一份插入一脚，
 * 表现就是同一条被发两次。条件写进 SQL 之后，抢输的一方拿到 0 就退让。
 */
@Dao
interface ProactiveBacklogDao {

    /**
     * 幂等记账。
     *
     * 冲突（同 thoughtId 已有派发、或同 dispatchId 已存在）直接忽略并返回 -1，
     * 调用方据此知道「这条牵挂已经派过了」。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(dispatch: ProactiveBacklogDispatchEntity): Long

    @Upsert
    suspend fun upsertAll(dispatches: List<ProactiveBacklogDispatchEntity>)

    @Query("SELECT * FROM proactive_backlog_dispatch WHERE dispatchId = :dispatchId")
    suspend fun getById(dispatchId: String): ProactiveBacklogDispatchEntity?

    @Query("SELECT * FROM proactive_backlog_dispatch WHERE thoughtId = :thoughtId")
    suspend fun findByThoughtId(thoughtId: String): ProactiveBacklogDispatchEntity?

    @Query(
        "SELECT * FROM proactive_backlog_dispatch WHERE state = 'READY' AND nextAttemptAt <= :now " +
            "ORDER BY nextAttemptAt ASC, createdAt ASC, dispatchId ASC LIMIT 1",
    )
    suspend fun findNextReady(now: Long): ProactiveBacklogDispatchEntity?

    @Query("SELECT COUNT(*) FROM proactive_backlog_dispatch WHERE state IN ('READY', 'CLAIMED')")
    suspend fun countUnfinished(): Int

    @Query(
        "SELECT MIN(CASE WHEN state = 'READY' THEN nextAttemptAt ELSE COALESCE(claimUntil, :now) END) " +
            "FROM proactive_backlog_dispatch WHERE state IN ('READY', 'CLAIMED')",
    )
    suspend fun getNextUnfinishedAt(now: Long): Long?

    /** 某个助手在某个会话里的派发。会话 id 为空时表示助手级范围。 */
    @Query(
        "SELECT * FROM proactive_backlog_dispatch WHERE assistantId = :assistantId " +
            "AND sessionId = :sessionId ORDER BY createdAt ASC, dispatchId ASC",
    )
    fun observeForScope(assistantId: String, sessionId: String): Flow<List<ProactiveBacklogDispatchEntity>>

    /**
     * 到期待发的行。limit 在 SQL 里生效，避免每轮把整张表读进内存。
     *
     * 排序末位补 `dispatchId`：同一次巡检写入的行 `nextAttemptAt` 与 `createdAt`
     * 常常完全相同，少了这层 tiebreak，SQLite 给出的相对顺序没有承诺，
     * 调度表现成「同一批行每次跑的顺序都可能不同」，回放问题也复现不出来。
     */
    @Query(
        "SELECT * FROM proactive_backlog_dispatch WHERE state = 'READY' AND nextAttemptAt <= :now " +
            "ORDER BY nextAttemptAt ASC, createdAt ASC, dispatchId ASC LIMIT :limit",
    )
    suspend fun findReady(now: Long, limit: Int): List<ProactiveBacklogDispatchEntity>

    /**
     * 领取：只有 READY 且已到期的行能被别人领走，领取即记一次尝试。
     *
     * @return 1 = 领到了；0 = 被别人抢先 / 未到期 / 已是终态
     */
    @Query(
        "UPDATE proactive_backlog_dispatch SET state = 'CLAIMED', claimToken = :token, " +
            "claimUntil = :claimUntil, attemptCount = attemptCount + 1, lastError = NULL, " +
            "updatedAt = :now WHERE dispatchId = :dispatchId AND state = 'READY' " +
            "AND nextAttemptAt <= :now",
    )
    suspend fun claim(dispatchId: String, token: String, claimUntil: Long, now: Long): Int

    @Query(
        "UPDATE proactive_backlog_dispatch SET sessionId = :sessionId, updatedAt = :now " +
            "WHERE dispatchId = :dispatchId AND state = 'CLAIMED' AND claimToken = :token",
    )
    suspend fun bindClaimedSession(dispatchId: String, token: String, sessionId: String, now: Long): Int

    /**
     * 回收死掉的租约：进程被杀时租约不会自己到期清除。
     *
     * 没有 claimUntil 的 CLAIMED 行一并收回 —— 那种行永远等不到到期判定，
     * 留着就是一条永远不会再发的牵挂。
     */
    @Query(
        "UPDATE proactive_backlog_dispatch SET state = 'READY', claimToken = NULL, " +
            "claimUntil = NULL, updatedAt = :now WHERE state = 'CLAIMED' " +
            "AND (claimUntil IS NULL OR claimUntil <= :now)",
    )
    suspend fun releaseExpiredClaims(now: Long): Int

    @Query(
        "UPDATE proactive_backlog_dispatch SET state = 'READY', claimToken = NULL, claimUntil = NULL, " +
            "nextAttemptAt = MAX(nextAttemptAt, :nextAttemptAt), updatedAt = :now WHERE state = 'CLAIMED' " +
            "AND (claimUntil IS NULL OR claimUntil <= :now)",
    )
    suspend fun releaseExpiredClaimsWithDelay(now: Long, nextAttemptAt: Long): Int

    /** 发送成功。token 不符（租约已被回收）时返回 0，调用方丢弃这次结果。 */
    @Query(
        "UPDATE proactive_backlog_dispatch SET state = 'SENT', outcomeId = :outcomeId, " +
            "claimToken = NULL, claimUntil = NULL, lastError = NULL, updatedAt = :updatedAt " +
            "WHERE dispatchId = :dispatchId AND state = 'CLAIMED' AND claimToken = :token " +
            "AND claimUntil > :updatedAt",
    )
    suspend fun markSent(dispatchId: String, token: String, outcomeId: String, updatedAt: Long): Int

    /** 可重试失败：回到 READY 并推到 nextAttemptAt，退避由上层算。 */
    @Query(
        "UPDATE proactive_backlog_dispatch SET state = 'READY', claimToken = NULL, claimUntil = NULL, " +
            "nextAttemptAt = :nextAttemptAt, lastError = :error, updatedAt = :updatedAt " +
            "WHERE dispatchId = :dispatchId AND state = 'CLAIMED' AND claimToken = :token " +
            "AND claimUntil > :updatedAt",
    )
    suspend fun markRetry(dispatchId: String, token: String, nextAttemptAt: Long, error: String, updatedAt: Long): Int

    /** 不可重试的失败：停在 FAILED，不再回到待发队列。 */
    @Query(
        "UPDATE proactive_backlog_dispatch SET state = 'FAILED', claimToken = NULL, claimUntil = NULL, " +
            "lastError = :error, updatedAt = :updatedAt " +
            "WHERE dispatchId = :dispatchId AND state = 'CLAIMED' AND claimToken = :token " +
            "AND claimUntil > :updatedAt",
    )
    suspend fun markFailed(dispatchId: String, token: String, error: String, updatedAt: Long): Int

    /**
     * 牵挂过期或已被别的路径处理：未完成的派发作废，已发出的不动。
     *
     * CLAIMED 只在租约已经失效（无租约或已到期）时才作废：租约仍有效意味着
     * 有 worker 正在发这一条，此刻把它改成 EXPIRED 会让那次发送的回执
     * 全部落空 —— 消息可能已经发出去了，库里却记着「没发」。
     */
    @Query(
        "UPDATE proactive_backlog_dispatch SET state = 'EXPIRED', claimToken = NULL, " +
            "claimUntil = NULL, updatedAt = :updatedAt WHERE dispatchId = :dispatchId " +
            "AND (state = 'READY' OR (state = 'CLAIMED' AND (claimUntil IS NULL OR claimUntil <= :now)))",
    )
    suspend fun markExpired(dispatchId: String, now: Long, updatedAt: Long): Int

    @Query(
        "UPDATE proactive_backlog_dispatch SET state = 'EXPIRED', claimToken = NULL, claimUntil = NULL, " +
            "updatedAt = :now WHERE thoughtId = :thoughtId AND state IN ('READY', 'CLAIMED') " +
            "AND (state = 'READY' OR claimUntil IS NULL OR claimUntil <= :now)",
    )
    suspend fun expireForThought(thoughtId: String, now: Long): Int

    @Query("SELECT * FROM proactive_backlog_dispatch")
    suspend fun getAll(): List<ProactiveBacklogDispatchEntity>

    @Query("DELETE FROM proactive_backlog_dispatch")
    suspend fun deleteAll()
}
