package io.zer0.muse.data.proactive

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 主动消息 backlog 的派发账：一条牵挂最多对应一条派发。
 *
 * [thoughtId] 上的唯一索引是这张表的核心约束 —— 记账侧反复扫到同一条牵挂时，
 * 第二次写入必须落空，否则用户会收到两遍同一句话。
 */
@Serializable
@Entity(
    tableName = "proactive_backlog_dispatch",
    indices = [
        Index(value = ["thoughtId"], unique = true, name = "index_proactive_backlog_dispatch_thoughtId"),
        Index(value = ["assistantId", "sessionId"], name = "index_proactive_backlog_dispatch_scope"),
        Index(value = ["state", "nextAttemptAt"], name = "index_proactive_backlog_dispatch_ready"),
        Index(value = ["state", "claimUntil"], name = "index_proactive_backlog_dispatch_claim"),
    ],
)
data class ProactiveBacklogDispatchEntity(
    @PrimaryKey val dispatchId: String,
    /**
     * 对应 pending_thought.id。唯一：同一条牵挂不重复派发。
     *
     * 只保留列默认值、不给构造默认值：这个字段是整张表唯一的业务主键，
     * 漏传时应当编译失败，而不是静默落成空串 —— 空串会让所有未传 thoughtId
     * 的行挤在同一条唯一索引上，第一条之后的写入全部被忽略。
     */
    @ColumnInfo(defaultValue = "") val thoughtId: String,
    @ColumnInfo(defaultValue = "") val assistantId: String = "",
    /** 发到哪个会话；空串表示由上层按助手挑一个。 */
    @ColumnInfo(defaultValue = "") val sessionId: String = "",
    /** [BacklogDispatchState] 的取值之一。 */
    @ColumnInfo(defaultValue = "READY") val state: String = BacklogDispatchState.READY,
    /** 领取凭据：只有持有它的 worker 才能把这条改成已发 / 失败。 */
    @ColumnInfo(defaultValue = "NULL") val claimToken: String? = null,
    /** 租约截止时间；过了它这条会被回收成 READY，而不是永远卡在 CLAIMED。 */
    @ColumnInfo(defaultValue = "NULL") val claimUntil: Long? = null,
    /** 已领取次数。退避与「试太多次就别再试」都看它。 */
    @ColumnInfo(defaultValue = "0") val attemptCount: Int = 0,
    /** 最早可以再发的时间点（退避由上层算好写进来）。 */
    @ColumnInfo(defaultValue = "0") val nextAttemptAt: Long = 0L,
    /** 最后一次失败的说明，排查用；成功发送时清空。 */
    @ColumnInfo(defaultValue = "NULL") val lastError: String? = null,
    /** 发出后回填的 proactive_outcome.id，用于把反馈挂回这条派发。 */
    @ColumnInfo(defaultValue = "NULL") val outcomeId: String? = null,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

/**
 * 派发状态的闭集。
 *
 * 用字符串常量而不是枚举列：库里存的就是这些名字，读到一个非法值时必须能被
 * 原样读出来并判定为「不是 READY」，而不是在转换阶段抛异常把整轮调度打断。
 */
object BacklogDispatchState {
    const val READY = "READY"
    const val CLAIMED = "CLAIMED"
    const val SENT = "SENT"
    const val FAILED = "FAILED"
    const val EXPIRED = "EXPIRED"

    val all: Set<String> = setOf(READY, CLAIMED, SENT, FAILED, EXPIRED)

    /** 终态不会再被领取或改写；未知值不算终态，留给巡检重新归类。 */
    fun isTerminal(state: String): Boolean = state == SENT || state == FAILED || state == EXPIRED
}

@Serializable
@androidx.room.Entity(tableName = "proactive_backlog_schedule")
data class ProactiveBacklogScheduleEntity(
    @androidx.room.PrimaryKey val id: Int = SINGLETON_ID,
    val nextBacklogDispatchAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@androidx.room.Dao
interface ProactiveBacklogScheduleDao {
    @androidx.room.Upsert
    suspend fun upsert(schedule: ProactiveBacklogScheduleEntity)

    @androidx.room.Query("SELECT * FROM proactive_backlog_schedule")
    suspend fun getAll(): List<ProactiveBacklogScheduleEntity>

    @androidx.room.Query("DELETE FROM proactive_backlog_schedule")
    suspend fun deleteAll()

    @androidx.room.Query("SELECT nextBacklogDispatchAt FROM proactive_backlog_schedule WHERE id = 1")
    suspend fun getNextBacklogDispatchAt(): Long?

    @androidx.room.Query(
        "INSERT OR REPLACE INTO proactive_backlog_schedule (id, nextBacklogDispatchAt, updatedAt) " +
            "SELECT id, MAX(nextBacklogDispatchAt, :nextAt), :now FROM proactive_backlog_schedule WHERE id = 1 " +
            "UNION ALL SELECT 1, :nextAt, :now WHERE NOT EXISTS " +
            "(SELECT 1 FROM proactive_backlog_schedule WHERE id = 1)",
    )
    suspend fun deferUntil(nextAt: Long, now: Long)

    @androidx.room.Query("UPDATE proactive_backlog_schedule SET nextBacklogDispatchAt = :nextAt, updatedAt = :now WHERE id = 1")
    suspend fun setNextBacklogDispatchAt(nextAt: Long, now: Long)
}
