package io.zer0.muse.data.stats

import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.muse.data.session.MessageDao
import io.zer0.muse.data.session.SessionDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * v1.107 统计聚合缓存管理器。
 *
 * 将 [MessageDao] / [SessionDao] 中开销较大的全表 GROUP BY 聚合结果序列化为 JSON,
 * 写入 [stats_cache][StatsCacheEntity] 表,避免每次进入统计页都触发全表扫描。
 *
 * 刷新时机由调用方决定(消息发送/删除后延迟刷新、WorkManager 每日全量刷新、
 * 统计页打开时按 updatedAt 判断是否过期)。本类只负责「聚合 -> 序列化 -> 落库」
 * 与「读取/观察/失效」。
 *
 * @param statsCacheDao 统计缓存 DAO
 * @param messageDao 消息 DAO(提供聚合查询)
 * @param sessionDao 会话 DAO(提供会话总数)
 */
class StatsCacheManager(
    private val statsCacheDao: StatsCacheDao,
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
) {

    companion object {
        private const val TAG = "StatsCacheManager"

        /** 缓存键: 各角色消息数(List<RoleCountItem>)。 */
        const val KEY_COUNT_BY_ROLE = "countByRole"

        /** 缓存键: 按 modelId 分组的 ASSISTANT 消息数(List<ModelCountItem>)。 */
        const val KEY_COUNT_BY_MODEL = "countByModel"

        /** 缓存键: 按 assistantId 分组的消息数(List<AssistantCountItem>)。 */
        const val KEY_COUNT_BY_ASSISTANT = "countByAssistant"

        /** 缓存键: 按小时分组的消息数(List<HourCountItem>,24 根柱状图)。 */
        const val KEY_COUNT_BY_HOUR = "countByHour"

        /** 缓存键: 消息数最多的前 N 个会话(List<SessionMessageCountItem>)。 */
        const val KEY_TOP_SESSIONS = "topSessions"

        /** 缓存键: 消息总数(TotalCount)。 */
        const val KEY_TOTAL_MESSAGES = "totalMessages"

        /** 缓存键: 会话总数(TotalCount)。 */
        const val KEY_TOTAL_SESSIONS = "totalSessions"

        /** Top 会话列表保留的条数。 */
        private const val TOP_SESSIONS_LIMIT = 10

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    /** 可序列化的各角色消息计数。 */
    @Serializable
    private data class RoleCountItem(val role: String, val cnt: Int)

    /** 可序列化的按 modelId 分组计数。 */
    @Serializable
    private data class ModelCountItem(val modelId: String, val cnt: Int)

    /** 可序列化的按 assistantId 分组计数。 */
    @Serializable
    private data class AssistantCountItem(val assistantId: String, val cnt: Int)

    /** 可序列化的按小时分组计数。 */
    @Serializable
    private data class HourCountItem(val hour: String, val cnt: Int)

    /** 可序列化的单个会话消息数投影。 */
    @Serializable
    private data class SessionMessageCountItem(val sessionId: String, val cnt: Int)

    /** 可序列化的总数统计。 */
    @Serializable
    private data class TotalCount(val total: Int)

    /**
     * 全量刷新所有聚合缓存。
     *
     * 依次调用各聚合查询,将结果 JSON 序列化后写入 stats_cache 表(以 REPLACE 策略覆盖)。
     * 在 [AppDispatchers.io] 上执行。单项计算失败不中断后续项,仅记录警告。
     */
    suspend fun refreshAll() = withContext(AppDispatchers.io) {
        Logger.i(TAG, "refreshAll: 开始刷新统计缓存")
        val now = System.currentTimeMillis()

        safePut(KEY_COUNT_BY_ROLE, now) {
            val rows = messageDao.countByRole().map { RoleCountItem(it.role, it.cnt) }
            json.encodeToString(rows)
        }

        safePut(KEY_COUNT_BY_MODEL, now) {
            val rows = messageDao.countByModel().map { ModelCountItem(it.modelId, it.cnt) }
            json.encodeToString(rows)
        }

        safePut(KEY_COUNT_BY_ASSISTANT, now) {
            val rows = messageDao.countByAssistant().map { AssistantCountItem(it.assistantId, it.cnt) }
            json.encodeToString(rows)
        }

        safePut(KEY_COUNT_BY_HOUR, now) {
            val rows = messageDao.countByHour().map { HourCountItem(it.hour, it.cnt) }
            json.encodeToString(rows)
        }

        safePut(KEY_TOP_SESSIONS, now) {
            val rows = messageDao.topSessionsByMessageCount(TOP_SESSIONS_LIMIT)
                .map { SessionMessageCountItem(it.sessionId, it.cnt) }
            json.encodeToString(rows)
        }

        safePut(KEY_TOTAL_MESSAGES, now) {
            json.encodeToString(TotalCount(messageDao.countMessages()))
        }

        safePut(KEY_TOTAL_SESSIONS, now) {
            json.encodeToString(TotalCount(sessionDao.count()))
        }

        Logger.i(TAG, "refreshAll: 刷新完成")
    }

    /**
     * 读取指定键的缓存值(JSON 字符串),不存在时返回 null。
     *
     * @param key 缓存键(建议使用 [companion object][Companion] 中定义的 KEY_* 常量)
     * @return JSON 字符串;无缓存时返回 null
     */
    suspend fun get(key: String): String? = withContext(AppDispatchers.io) {
        statsCacheDao.get(key)?.value
    }

    /**
     * 观察指定键的缓存值变化。缓存被刷新或失效时自动推送新值。
     *
     * @param key 缓存键
     * @return 推送当前缓存值(无缓存时为 null)的 Flow
     */
    fun observe(key: String): Flow<String?> =
        statsCacheDao.observe(key).map { it?.value }

    /**
     * 清空全部统计缓存。
     *
     * 在消息发送/删除后调用,使统计页下次读取时拿到空缓存并触发延迟刷新
     * (延迟刷新由调用方/WorkManager 调度,避免高频写入时反复全表聚合)。
     */
    suspend fun invalidateAll() = withContext(AppDispatchers.io) {
        Logger.i(TAG, "invalidateAll: 清空全部统计缓存")
        statsCacheDao.deleteAll()
    }

    /**
     * 计算并写入单个缓存项;计算异常时记录警告并跳过,不影响其他项。
     *
     * @param key 缓存键
     * @param now 落库时间戳
     * @param block 计算缓存值的逻辑(返回 JSON 字符串),在 [AppDispatchers.io] 上执行
     */
    private suspend fun safePut(key: String, now: Long, block: suspend () -> String) {
        val value = try {
            block()
        } catch (e: Exception) {
            Logger.w(TAG, "refreshAll: 计算 $key 失败,跳过: ${e.message}", e)
            return
        }
        statsCacheDao.upsert(StatsCacheEntity(key = key, value = value, updatedAt = now))
    }
}
