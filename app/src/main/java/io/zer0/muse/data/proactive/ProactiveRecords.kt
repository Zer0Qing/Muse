package io.zer0.muse.data.proactive

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** 一条主动消息的最终去向。 */
enum class OutcomeState {
    /** 发出去了，还没有任何反应。 */
    PENDING,

    /** 用户点开了通知。 */
    OPENED,

    /** 用户回复了。最强的正反馈。 */
    REPLIED,

    /** 用户把通知划掉了。弱负反馈。 */
    DISMISSED,

    /** 超时无任何动作。 */
    EXPIRED,
}

/**
 * 主动消息的结果账 —— 旧实现完全缺失的一层。
 *
 * 发完就结束的系统学不到任何东西：它不知道用户是欢迎还是厌烦，
 * 只能靠调参数猜。这张表让「用户点开没有 / 回复没有 / 划掉没有」
 * 成为可学习的信号，驱动关系亲密度与各类型的生成倾向。
 */
@kotlinx.serialization.Serializable
@Entity(
    tableName = "proactive_outcome",
    indices = [
        Index(value = ["assistantId", "sentAt"], name = "index_proactive_outcome_assistant"),
        Index(value = ["thoughtId"], name = "index_proactive_outcome_thought"),
        Index(value = ["state"], name = "index_proactive_outcome_state"),
    ],
)
data class ProactiveOutcomeEntity(
    @PrimaryKey val id: String,
    /** 对应哪条牵挂。可能为空（用户约定类以外的历史数据）。 */
    @ColumnInfo(defaultValue = "NULL") val thoughtId: String? = null,
    /** 落库后的消息 id，用于「回复检测」反查。 */
    @ColumnInfo(defaultValue = "NULL") val messageId: String? = null,
    @ColumnInfo(defaultValue = "") val assistantId: String = "",
    @ColumnInfo(defaultValue = "") val sessionId: String = "",
    /** 记下当时的 kind，供按类型统计反馈。牵挂行被清理后统计还能继续。 */
    @ColumnInfo(defaultValue = "FOLLOW_UP") val kind: String = ThoughtKind.FOLLOW_UP.name,
    @ColumnInfo(defaultValue = "0") val sentAt: Long = 0L,
    @ColumnInfo(defaultValue = "NULL") val openedAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val repliedAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val dismissedAt: Long? = null,
    /** [OutcomeState] 的 name。 */
    @ColumnInfo(defaultValue = "PENDING") val state: String = OutcomeState.PENDING.name,
) {
    fun stateEnum(): OutcomeState =
        OutcomeState.entries.firstOrNull { it.name == state } ?: OutcomeState.PENDING
}

@Dao
interface ProactiveOutcomeDao {

    @Upsert
    suspend fun upsert(outcome: ProactiveOutcomeEntity)

    @Query("SELECT * FROM proactive_outcome WHERE id = :id")
    suspend fun getById(id: String): ProactiveOutcomeEntity?

    /**
     * 找最近一条还在等反应的消息。
     *
     * 用户回复时用它反查「这条回复是在回应哪条主动消息」：
     * 取该会话内 sentAt 最近且 state 为 PENDING/OPENED 的一条。
     */
    @Query(
        "SELECT * FROM proactive_outcome WHERE sessionId = :sessionId " +
            "AND state IN ('PENDING', 'OPENED') ORDER BY sentAt DESC LIMIT 1",
    )
    suspend fun findPendingInSession(sessionId: String): ProactiveOutcomeEntity?

    /** 某助手最近若干条结果，供设置页展示与统计。 */
    @Query(
        "SELECT * FROM proactive_outcome WHERE assistantId = :assistantId " +
            "ORDER BY sentAt DESC LIMIT :limit",
    )
    suspend fun getRecent(assistantId: String, limit: Int): List<ProactiveOutcomeEntity>

    /** 统计某个时间点之后各状态的数量，驱动 intimacy 更新。 */
    @Query(
        "SELECT COUNT(*) FROM proactive_outcome WHERE assistantId = :assistantId " +
            "AND state = :state AND sentAt >= :since",
    )
    suspend fun countStateSince(assistantId: String, state: String, since: Long): Int

    /** 某类型的历史反馈分布，用于「用户对这类从来不感冒」的判定。 */
    @Query(
        "SELECT COUNT(*) FROM proactive_outcome WHERE assistantId = :assistantId " +
            "AND kind = :kind AND state = :state",
    )
    suspend fun countKindState(assistantId: String, kind: String, state: String): Int

    /** 把超时未响应的记录收尾。 */
    @Query(
        "UPDATE proactive_outcome SET state = 'EXPIRED' " +
            "WHERE state = 'PENDING' AND sentAt <= :before",
    )
    suspend fun expireStale(before: Long): Int

    @Query("DELETE FROM proactive_outcome WHERE sentAt < :before")
    suspend fun deleteBefore(before: Long): Int

    /** 全量，备份导出用。 */
    @Query("SELECT * FROM proactive_outcome")
    suspend fun getAll(): List<ProactiveOutcomeEntity>

    /** 备份恢复前清空。 */
    @Query("DELETE FROM proactive_outcome")
    suspend fun deleteAll()
}

/**
 * 关系亲密度 —— 每个助手一份。
 *
 * 这是「越用越懂事」的载体：预算的地板与天花板由用户选的等级决定，
 * 而中间浮动的那一段由这里的学习结果决定。
 */
@kotlinx.serialization.Serializable
@Entity(tableName = "proactive_relation")
data class ProactiveRelationEntity(
    @PrimaryKey val assistantId: String,
    /** 0..1，初值 0.15（新用户很保守）。 */
    @ColumnInfo(defaultValue = "0.15") val intimacy: Float = 0.15f,
    /** 冷却截止时间戳。用户说「最近别烦我」时写入。 */
    @ColumnInfo(defaultValue = "0") val cooldownUntil: Long = 0L,
    /** 用户选的主动等级（[ProactiveIntensity] 的 name）。 */
    @ColumnInfo(defaultValue = "STANDARD") val intensity: String = ProactiveIntensity.DEFAULT.name,
    /** 预算记账所属日期（yyyy-MM-dd），跨日时清零当天计数。 */
    @ColumnInfo(defaultValue = "") val budgetDate: String = "",
    /** 当天已消耗条数。 */
    @ColumnInfo(defaultValue = "0") val spentToday: Int = 0,
    /** 当天用户主动互动回补的额度。 */
    @ColumnInfo(defaultValue = "0") val earnedToday: Float = 0f,
) {
    fun intensityEnum(): ProactiveIntensity = ProactiveIntensity.fromName(intensity)
}

@Dao
interface ProactiveRelationDao {

    @Upsert
    suspend fun upsert(relation: ProactiveRelationEntity)

    @Query("SELECT * FROM proactive_relation WHERE assistantId = :assistantId")
    suspend fun get(assistantId: String): ProactiveRelationEntity?

    @Query("SELECT * FROM proactive_relation")
    suspend fun getAll(): List<ProactiveRelationEntity>

    @Query("UPDATE proactive_relation SET intimacy = :intimacy WHERE assistantId = :assistantId")
    suspend fun updateIntimacy(assistantId: String, intimacy: Float)

    @Query("UPDATE proactive_relation SET cooldownUntil = :until WHERE assistantId = :assistantId")
    suspend fun updateCooldown(assistantId: String, until: Long)

    @Query("UPDATE proactive_relation SET intensity = :intensity WHERE assistantId = :assistantId")
    suspend fun updateIntensity(assistantId: String, intensity: String)

    @Query(
        "UPDATE proactive_relation SET budgetDate = :date, spentToday = 0, earnedToday = 0 " +
            "WHERE assistantId = :assistantId",
    )
    suspend fun resetBudget(assistantId: String, date: String)

    @Query(
        "UPDATE proactive_relation SET spentToday = spentToday + 1 WHERE assistantId = :assistantId",
    )
    suspend fun incrementSpent(assistantId: String)

    @Query(
        "UPDATE proactive_relation SET earnedToday = earnedToday + :amount " +
            "WHERE assistantId = :assistantId",
    )
    suspend fun addEarned(assistantId: String, amount: Float)

    @Query("DELETE FROM proactive_relation")
    suspend fun deleteAll()
}
