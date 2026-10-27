package io.zer0.muse.data.proactive

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * 牵挂账本 —— 「我有什么事想跟这个人说」。
 *
 * 这张表是主动消息机制的地基。旧实现每次从头判断「现在该不该发」，
 * 而正确的问题顺序是「账上有没有东西可说」。没有这张表，
 * 「无事可说」就不是一个可表达的状态，系统只能靠评分和概率硬凑出「不发」。
 *
 * 记账本身不产生 LLM 成本：来源要么寄生在正常对话的工具调用里，
 * 要么是纯本地规则推导。
 */
@kotlinx.serialization.Serializable
@Entity(
    tableName = "pending_thought",
    indices = [
        Index(value = ["assistantId", "status", "expiresAt"], name = "index_pending_thought_dispatch"),
        Index(value = ["sourceRef"], name = "index_pending_thought_sourceRef"),
    ],
)
data class PendingThoughtEntity(
    @PrimaryKey val id: String,
    /** 属于哪个助手。多助手各有各的账，互不串门。 */
    @ColumnInfo(defaultValue = "") val assistantId: String = "",
    /** [ThoughtKind] 的 name。 */
    @ColumnInfo(defaultValue = "FOLLOW_UP") val kind: String = ThoughtKind.FOLLOW_UP.name,
    /** 事由原文，一句话。写给助手看，不是写给用户看。 */
    @ColumnInfo(defaultValue = "") val seed: String = "",
    /** [ThoughtSource] 的 name。 */
    @ColumnInfo(defaultValue = "CONVERSATION") val source: String = ThoughtSource.CONVERSATION.name,
    /** 来源对象 id（记忆 id / 里程碑 id / 消息 id），用于回溯与去重。 */
    @ColumnInfo(defaultValue = "NULL") val sourceRef: String? = null,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    /** 过期时间戳。NULL = 永不过期。 */
    @ColumnInfo(defaultValue = "NULL") val expiresAt: Long? = null,
    /** [ThoughtUrgency] 的 name。 */
    @ColumnInfo(defaultValue = "WINDOW") val urgency: String = ThoughtUrgency.WINDOW.name,
    /** 我多在意这件事，0..1。 */
    @ColumnInfo(defaultValue = "0.5") val weight: Float = 0.5f,
    /** 上次说出去的时间，NULL = 从没说过。 */
    @ColumnInfo(defaultValue = "NULL") val lastSurfacedAt: Long? = null,
    /** 说过几次。用来抑制反复念叨同一件事。 */
    @ColumnInfo(defaultValue = "0") val surfaceCount: Int = 0,
    /** [ThoughtStatus] 的 name。 */
    @ColumnInfo(defaultValue = "OPEN") val status: String = ThoughtStatus.OPEN.name,
) {
    fun kindEnum(): ThoughtKind = enumOr(ThoughtKind.entries, kind, ThoughtKind.FOLLOW_UP)

    fun urgencyEnum(): ThoughtUrgency = enumOr(ThoughtUrgency.entries, urgency, ThoughtUrgency.WINDOW)

    fun statusEnum(): ThoughtStatus = enumOr(ThoughtStatus.entries, status, ThoughtStatus.OPEN)

    fun sourceEnum(): ThoughtSource = enumOr(ThoughtSource.entries, source, ThoughtSource.CONVERSATION)

    /**
     * 按 name 还原枚举，非法值回落到 [fallback]。
     *
     * 不用 `enumValueOf` 直接解析：库里可能存着旧版本写过、
     * 现在已删掉的枚举名，直接 parse 会抛异常把整个调度打断。
     */
    private fun <T : Enum<T>> enumOr(values: List<T>, raw: String, fallback: T): T =
        values.firstOrNull { it.name == raw } ?: fallback
}

/**
 * 牵挂账本的读取口。
 *
 * 与 [PendingThoughtWriteDao] 分开是因为两者被调用的场景完全不同：
 * 读在每轮巡检里跑（热路径），写在记账/维护时跑（冷路径）。
 * 拆开也让两边的函数数都落在可读范围内 —— 一个二十来个方法的 DAO
 * 谁也说不清它到底负责什么。
 *
 * 本接口只做存取，不做业务判断：fitness 怎么算、要不要发、什么时候发，
 * 都由上层判定引擎决定。这里返回原始行。
 */
@Dao
interface PendingThoughtDao {

    @Upsert
    suspend fun upsert(thought: PendingThoughtEntity)

    @Query("SELECT * FROM pending_thought WHERE id = :id")
    suspend fun getById(id: String): PendingThoughtEntity?

    /**
     * 取某个助手所有还没说出去的牵挂。
     *
     * 不做排序：fitness 依赖当前时刻（freshness / windowFit 都跟 now 有关），
     * 排序必须拿到内存里算，SQL 排不出来。
     */
    @Query("SELECT * FROM pending_thought WHERE assistantId = :assistantId AND status = 'OPEN'")
    suspend fun getOpen(assistantId: String): List<PendingThoughtEntity>

    /** 全部 OPEN（跨助手），供设置页展示账本。 */
    @Query("SELECT * FROM pending_thought WHERE status = 'OPEN' ORDER BY createdAt DESC")
    suspend fun getAllOpen(): List<PendingThoughtEntity>

    /** 最近记下的若干条，供设置页展示。 */
    @Query("SELECT * FROM pending_thought ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<PendingThoughtEntity>

    /**
     * 同源去重：同一来源对象只记一条。
     *
     * 没有这条，同一条记忆每轮巡检都会被重新推导成一条新牵挂，
     * 账本会以每小时一条的速度膨胀。
     */
    @Query("SELECT COUNT(*) FROM pending_thought WHERE sourceRef = :sourceRef AND status = 'OPEN'")
    suspend fun countOpenBySourceRef(sourceRef: String): Int

    /**
     * 取全部已记账的来源 id，供推导时批量去重。
     *
     * 比逐条调 [countOpenBySourceRef] 好：记忆一次可能几十条，
     * 每条一次查询会把推导变成 N 次 DB 往返（而且这是在轮询路径上）。
     */
    @Query(
        "SELECT sourceRef FROM pending_thought WHERE assistantId = :assistantId " +
            "AND status = 'OPEN' AND sourceRef IS NOT NULL",
    )
    suspend fun getOpenSourceRefs(assistantId: String): List<String>

    @Query("SELECT COUNT(*) FROM pending_thought WHERE assistantId = :assistantId AND status = 'OPEN'")
    suspend fun countOpen(assistantId: String): Int

    /** 全量（含已结束），备份导出用。 */
    @Query("SELECT * FROM pending_thought")
    suspend fun getAll(): List<PendingThoughtEntity>
}

/**
 * 牵挂账本的写入与维护口。
 *
 * 状态流转只有一个方向：OPEN → DONE / EXPIRED / DROPPED。
 * 没有「回到 OPEN」的路径 —— 已经放弃的事再翻出来说，
 * 比一开始就没记过更糟。
 */
@Dao
interface PendingThoughtWriteDao {

    @Query("UPDATE pending_thought SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    /** 标记已说出口，并把提醒次数 +1。 */
    @Query(
        "UPDATE pending_thought SET lastSurfacedAt = :at, surfaceCount = surfaceCount + 1 " +
            "WHERE id = :id",
    )
    suspend fun markSurfaced(id: String, at: Long)

    /** 清理已过期但状态还停在 OPEN 的行。 */
    @Query(
        "UPDATE pending_thought SET status = 'EXPIRED' " +
            "WHERE status = 'OPEN' AND expiresAt IS NOT NULL AND expiresAt <= :now",
    )
    suspend fun expireOverdue(now: Long): Int

    /** 被反复提起仍无反应的牵挂自动放弃，避免一直念叨。 */
    @Query(
        "UPDATE pending_thought SET status = 'DROPPED' " +
            "WHERE status = 'OPEN' AND surfaceCount >= :maxSurface",
    )
    suspend fun dropOverSurfaced(maxSurface: Int): Int

    @Query("DELETE FROM pending_thought WHERE status != 'OPEN' AND createdAt < :before")
    suspend fun deleteClosedBefore(before: Long): Int

    @Query("DELETE FROM pending_thought WHERE id = :id")
    suspend fun delete(id: String)

    /** 备份恢复前清空。 */
    @Query("DELETE FROM pending_thought")
    suspend fun deleteAll()
}
