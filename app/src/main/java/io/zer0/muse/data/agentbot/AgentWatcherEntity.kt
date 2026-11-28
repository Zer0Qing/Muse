package io.zer0.muse.data.agentbot

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * 帮你跟进（Watcher）：一个持续盯住外部变化的托管项。
 *
 * 一行对应一条「有变化才说话」的跟进任务：引擎按 [intervalMinutes] 采集一次，
 * 把结果规范化后取 SHA-256 与 [lastFingerprint] 比对；相同就只更新 [lastCheckedAt]，
 * 不调用模型，也不打扰用户。只有指纹变化时才唤醒主模型生成人话播报。
 *
 * 归属： [botId] 是承接这条跟进的 Bot，[originSessionId] 是创建它的那段对话。
 * 两者都只用于作用域展示与级联清理，不做外键 —— 会话或 Bot 删除时由清理器显式收口，
 * 与中枢其它表的删除语义保持一致。
 */
@Serializable
@Entity(
    tableName = "agent_watchers",
    indices = [
        Index(value = ["botId", "status"]),
        Index(value = ["originSessionId"]),
        Index(value = ["nextCheckAt"]),
    ],
)
data class AgentWatcherEntity(
    @PrimaryKey val watcherId: String,
    /** 承接这条跟进的 Bot；空表示只在本机托管。 */
    @ColumnInfo(defaultValue = "") val botId: String = "",
    /** 创建这条跟进的那段对话。 */
    @ColumnInfo(defaultValue = "") val originSessionId: String = "",
    /** 列表与通知里显示的名字。 */
    @ColumnInfo(defaultValue = "") val title: String = "",
    /** 要持续盯的目标，用自然语言描述；模型据此决定如何采集与播报。 */
    @ColumnInfo(defaultValue = "") val instruction: String = "",
    /** 本机采集脚本；为空表示由模型按 [instruction] 现场采集。 */
    @ColumnInfo(defaultValue = "") val script: String = "",
    /** 采集脚本类型："python" / "node"。 */
    @ColumnInfo(defaultValue = "python") val scriptKind: String = "python",
    /** 托管位置："local" / "cloud"；cloud 表示下沉到在线云电脑 24×7 执行。 */
    @ColumnInfo(defaultValue = "local") val computeTarget: String = "local",
    /** 检查周期（分钟），最小 1。 */
    @ColumnInfo(defaultValue = "30") val intervalMinutes: Int = 30,
    /** 可选的外部渠道回调地址；空表示只写系统通知。 */
    @ColumnInfo(defaultValue = "") val webhookUrl: String = "",
    /** 云端托管时远端 watcher 的 id；本机托管为空。 */
    @ColumnInfo(defaultValue = "") val remoteWatcherId: String = "",
    /** 最近一次采集结果的规范化 SHA-256；空表示尚未成功检查过。 */
    @ColumnInfo(defaultValue = "") val lastFingerprint: String = "",
    /** 最近一次采集结果摘要，供变化播报与列表预览。 */
    @ColumnInfo(defaultValue = "") val lastResult: String = "",
    /** 最近一次检查完成时间。 */
    @ColumnInfo(defaultValue = "0") val lastCheckedAt: Long = 0L,
    /** 下一次计划检查时间（epoch millis）。 */
    @ColumnInfo(defaultValue = "0") val nextCheckAt: Long = 0L,
    /** 见 [AgentWatcherStatus]。 */
    @ColumnInfo(defaultValue = "ACTIVE") val status: String = AgentWatcherStatus.ACTIVE,
    /** 最近一次失败原因；成功后清空。 */
    @ColumnInfo(defaultValue = "") val lastError: String = "",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

/** 跟进项的生命周期状态。值以字符串落库，读取侧按 [ALL] 校验，未知值显式拒绝。 */
object AgentWatcherStatus {
    const val ACTIVE = "ACTIVE"
    const val PAUSED = "PAUSED"
    const val ENDED = "ENDED"
    const val ERROR = "ERROR"
    const val ERROR_REMOTE_REVOCATION_PENDING = "REMOTE_REVOCATION_PENDING"
    const val ERROR_REMOTE_BINDING_UNKNOWN = "REMOTE_BINDING_UNKNOWN"
    const val WATCHER_NOTIFY_PENDING_PREFIX = "WATCHER_NOTIFY_PENDING|"

    val ALL: List<String> = listOf(ACTIVE, PAUSED, ENDED, ERROR)

    fun isValid(value: String): Boolean = value in ALL
}

/**
 * 跟进项读写口。
 *
 * 调度侧只关心「谁到点了」（[listDue]），界面侧只关心「我这条对话有哪些」（[observeBySession]）；
 * 指纹与检查时间的更新走一条窄 UPDATE，避免整行读写把并发状态覆盖掉。
 */
@Dao
interface AgentWatcherDao {

    @Upsert
    suspend fun upsert(watcher: AgentWatcherEntity)

    @Upsert
    suspend fun upsertAll(watchers: List<AgentWatcherEntity>)

    @Query("SELECT * FROM agent_watchers WHERE watcherId = :watcherId LIMIT 1")
    suspend fun getById(watcherId: String): AgentWatcherEntity?

    @Query("SELECT * FROM agent_watchers ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AgentWatcherEntity>>

    @Query("SELECT * FROM agent_watchers WHERE botId = :botId ORDER BY updatedAt DESC")
    fun observeByBot(botId: String): Flow<List<AgentWatcherEntity>>

    @Query("SELECT * FROM agent_watchers WHERE originSessionId = :sessionId ORDER BY updatedAt DESC")
    fun observeBySession(sessionId: String): Flow<List<AgentWatcherEntity>>

    @Query("SELECT * FROM agent_watchers WHERE status = 'ACTIVE' ORDER BY nextCheckAt ASC")
    suspend fun listActive(): List<AgentWatcherEntity>

    @Query("SELECT * FROM agent_watchers WHERE status IN ('PAUSED','ENDED') AND remoteWatcherId != ''")
    suspend fun listPendingRemoteRevocation(): List<AgentWatcherEntity>

    @Query("SELECT * FROM agent_watchers WHERE lastError GLOB 'WATCHER_NOTIFY_PENDING|*'")
    suspend fun listPendingNotifications(): List<AgentWatcherEntity>

    @Query("SELECT * FROM agent_watchers WHERE status = 'ACTIVE' AND nextCheckAt <= :now ORDER BY nextCheckAt ASC")
    suspend fun listDue(now: Long): List<AgentWatcherEntity>

    /**
     * 记录一次检查结果并推进下一次计划时间。
     *
     * 只改这五列：整行 upsert 会把用户同时修改的标题/周期覆盖回旧值。
     */
    @Query(
        "UPDATE agent_watchers SET lastFingerprint = :fingerprint, lastResult = :result, " +
            "lastCheckedAt = :checkedAt, nextCheckAt = :nextCheckAt, lastError = :error, " +
            "updatedAt = :checkedAt WHERE watcherId = :watcherId",
    )
    suspend fun recordCheck(watcherId: String, fingerprint: String, result: String, checkedAt: Long, nextCheckAt: Long, error: String): Int

    @Query("UPDATE agent_watchers SET status = :status, updatedAt = :updatedAt WHERE watcherId = :watcherId")
    suspend fun setStatus(watcherId: String, status: String, updatedAt: Long): Int

    @Query("UPDATE agent_watchers SET lastError = '' WHERE watcherId = :watcherId AND lastError = :expectedMarker")
    suspend fun clearPendingNotification(watcherId: String, expectedMarker: String): Int

    @Query("DELETE FROM agent_watchers WHERE watcherId = :watcherId")
    suspend fun deleteById(watcherId: String)

    @Query("DELETE FROM agent_watchers WHERE originSessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query(
        "DELETE FROM agent_watchers WHERE botId IN " +
            "(SELECT botId FROM bot_bindings WHERE assistantId = :assistantId)",
    )
    suspend fun deleteByAssistant(assistantId: String)

    @Query("SELECT * FROM agent_watchers")
    suspend fun getAll(): List<AgentWatcherEntity>

    @Query("DELETE FROM agent_watchers")
    suspend fun deleteAll()
}
