package io.zer0.muse.data.audit

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P2-4 审计日志记录器。
 *
 * 设计要点:
 *  - [log] 是非 suspend 的 fire-and-forget 接口,内部用独立 [scope] 异步写入,
 *    不会阻塞调用方(调用方多为 UI / ViewModel 主线程路径)。
 *  - 环形缓冲:每次 insert 后异步检查 [count],超过 [MAX_LOG_COUNT] 时
 *    一次性删除最旧的 [TRIM_BATCH] 条(避免逐条删除的事务开销)。
 *  - detail Map 用 [AppJson] 序列化为 JSON 字符串,支持 String / Number / Boolean /
 *    嵌套 Map / List 等常见类型,未知类型 fallback 到 toString()。
 *  - [cleanupOldLogs] 在应用启动时调用一次,清理 [RETENTION_DAYS] 天前的日志。
 *
 * @param dao 审计日志 DAO
 */
class AuditLogger(private val dao: AuditLogDao) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 环形缓冲上限:超过此值触发批量清理。 */
    private val maxLogCount = MAX_LOG_COUNT

    /** 触发清理时一次性删除的最旧条数(避免逐条删除的事务开销)。 */
    private val trimBatch = TRIM_BATCH

    /**
     * 记录一条审计日志。
     *
     * 非阻塞:内部 launch 异步写入,调用方无需 await。
     *
     * @param category 分类:"api_call" / "user_action" / "auth" / "system"
     * @param action 动作名,如 "send_message" / "delete_provider" / "login"
     * @param target 操作目标(chatId / providerId 等,可空)
     * @param detail 额外字段(model / tokens / latency_ms 等),会被序列化为 JSON
     * @param success 操作是否成功
     */
    fun log(
        category: String,
        action: String,
        target: String = "",
        detail: Map<String, Any> = emptyMap(),
        success: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        val detailJson = encodeDetail(detail)
        scope.launch {
            try {
                dao.insert(
                    AuditLogEntity(
                        timestamp = now,
                        category = category,
                        action = action,
                        target = target,
                        detail = detailJson,
                        success = success,
                    )
                )
                // 环形缓冲:超上限则批量清理最旧条目
                val total = dao.count()
                if (total > maxLogCount) {
                    dao.deleteOldest(trimBatch)
                }
            } catch (t: Throwable) {
                // 审计日志失败不应影响调用方业务,仅记录到 Logger
                Logger.w("AuditLogger", "写入审计日志失败: $category/$action", t)
            }
        }
    }

    /**
     * 分页查询日志(按时间倒序)。
     *
     * @param limit 每页条数(默认 100)
     * @param offset 偏移量
     */
    suspend fun query(limit: Int = 100, offset: Int = 0): List<AuditLogEntity> {
        return try {
            dao.query(limit, offset)
        } catch (t: Throwable) {
            Logger.w("AuditLogger", "查询审计日志失败", t)
            emptyList()
        }
    }

    /**
     * 清理 [RETENTION_DAYS] 天前的日志。应用启动时调用一次。
     */
    suspend fun cleanupOldLogs() {
        try {
            val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 60L * 60L * 1000L
            dao.deleteOlderThan(cutoff)
        } catch (t: Throwable) {
            Logger.w("AuditLogger", "清理旧审计日志失败", t)
        }
    }

    /**
     * 清空全部审计日志(用户在审计日志页点击"清空"时调用)。
     */
    suspend fun clearAll() {
        try {
            dao.deleteAll()
        } catch (t: Throwable) {
            Logger.w("AuditLogger", "清空审计日志失败", t)
        }
    }

    /**
     * 把 detail Map 序列化为 JSON 字符串。
     *
     * 支持 String / Boolean / Number / Enum / 嵌套 Map / List;
     * 未知类型 fallback 到 [Any.toString]。
     */
    private fun encodeDetail(detail: Map<String, Any>): String {
        if (detail.isEmpty()) return "{}"
        val jsonObject = buildJsonObject {
            detail.forEach { (k, v) -> put(k, toJsonElement(v)) }
        }
        return AppJson.encodeToString(JsonObject.serializer(), jsonObject)
    }

    /** 把任意值转换为 [JsonElement],支持基本类型与容器类型递归转换。 */
    private fun toJsonElement(v: Any): JsonElement = when (v) {
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Float -> JsonPrimitive(v)
        is Double -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v.toString())
        is Enum<*> -> JsonPrimitive(v.name)
        is Map<*, *> -> buildJsonObject {
            v.forEach { (nk, nv) ->
                if (nk is String && nv != null) put(nk, toJsonElement(nv))
            }
        }
        is List<*> -> JsonArray(v.map { it?.let { toJsonElement(it) } ?: JsonNull })
        is Array<*> -> JsonArray(v.map { it?.let { toJsonElement(it) } ?: JsonNull })
        else -> JsonPrimitive(v.toString())
    }

    companion object {
        /** 环形缓冲上限条数。 */
        private const val MAX_LOG_COUNT = 10_000

        /** 触发清理时一次性删除的最旧条数。 */
        private const val TRIM_BATCH = 1_000

        /** 日志保留期(天),超过则启动时清理。 */
        private const val RETENTION_DAYS = 30
    }
}
