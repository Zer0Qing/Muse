package io.zer0.memory.fact

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-assistant FactDb 提供者 (v0.22: 每个 Assistant 独立 facts.db)。
 *
 * 按 assistantId 创建/缓存独立的 FactDb 实例：
 *  - assistantId 为空或 "default" → facts.db（向后兼容旧数据）
 *  - assistantId = "abc123" → facts_abc123.db
 *
 * 线程安全：用 ConcurrentHashMap.computeIfAbsent 保证每个 assistantId 只创建一次。
 * FactDb 内部已用 Room 的 WAL 模式，多协程并发读安全。
 *
 * v1.78: 新增 [release] 方法,助手删除时释放 db 句柄,避免长期使用后 fd 泄漏。
 */
class FactDbProvider(
    private val context: Context,
    /** v12: LLM 去重判定器,透传给每个 per-assistant FactStore(同实体模糊候选交给大模型判断)。 */
    private val dedupJudge: FactDedupJudge = NoopFactDedupJudge,
) {
    private val cache = ConcurrentHashMap<String, FactDb>()
    private val storeCache = ConcurrentHashMap<String, FactStore>()

    /** 获取指定 assistant 的 FactDb。assistantId 为空时回退到 "default"。 */
    fun getFactDb(assistantId: String): FactDb {
        val key = if (assistantId.isBlank()) "default" else assistantId
        return cache.computeIfAbsent(key) { id ->
            val dbName = if (id == "default") "facts.db" else "facts_$id.db"
            FactDb.create(context, dbName)
        }
    }

    /** 获取指定 assistant 的 FactStore（便捷方法）。 */
    fun getFactStore(assistantId: String): FactStore {
        val key = if (assistantId.isBlank()) "default" else assistantId
        return storeCache.computeIfAbsent(key) {
            // v1.0.27 P0-1.3: FactStore 现在需要 FactDb 实例以支持 addBatch 事务
            val db = getFactDb(key)
            // v15: new deletion tombstones are stored atomically in this assistant's FactDb.
            // Keep the old shared JSON file read-only as a fail-closed upgrade fallback; active
            // facts in this exact scope/space exempt legacy text markers from suppressing re-added duplicates.
            // v12: 透传 LLM 去重判定器。
            // v13 (T4-1): 修订记录 DAO 从 db 直接取(不注入,避免 Koin 循环依赖:
            //   FactDbProvider → FactRevisionDao → FactDb → FactDbProvider)。
            FactStore(
                db.factDao(),
                db,
                java.io.File(context.filesDir, "fact_tombstones.json"),
                dedupJudge,
                db.factRevisionDao(),
                assistantId = key,
                clearLegacyTombstonesOnReset = key == "default",
            )
        }
    }

    /**
     * v1.78: 释放指定 assistant 的 FactDb 句柄并从缓存移除。
     *
     * 助手删除时调用,避免已删除助手的 db 连接永久占用(SQLite 文件句柄 + WAL)。
     * 注意:调用后若再次 getFactDb(同 id) 会重建新实例。
     */
    fun release(assistantId: String) {
        val key = if (assistantId.isBlank()) "default" else assistantId
        storeCache.remove(key)
        cache.remove(key)?.close()
    }

    /** v1.78: 释放全部缓存的 FactDb(用于 App 退出或记忆重置)。 */
    fun releaseAll() {
        storeCache.clear()
        cache.values.forEach { runCatching { it.close() } }
        cache.clear()
    }

    /**
     * 磁盘上已有分库的助手 id(`facts_<id>.db`),不含主库 `facts.db`。
     *
     * 会话/助手删除要遍历所有分库,但只应打开真实存在的库:已被删除的助手不再有文件,
     * 凭 id 猜一遍会凭空建出空库(并永久留在磁盘上)。
     */
    fun listAssistantDbIds(): List<String> {
        val dir = context.getDatabasePath("facts.db").parentFile ?: return emptyList()
        return dir.listFiles()
            .orEmpty()
            .asSequence()
            .map { it.name }
            .filter { it.startsWith(PER_ASSISTANT_DB_PREFIX) && it.endsWith(PER_ASSISTANT_DB_SUFFIX) }
            .map { it.removePrefix(PER_ASSISTANT_DB_PREFIX).removeSuffix(PER_ASSISTANT_DB_SUFFIX) }
            .filter { it.isNotBlank() && it != DEFAULT_ASSISTANT_KEY }
            .distinct()
            .sorted()
            .toList()
    }

    private companion object {
        /** 主库名与 per-assistant 分库名后缀(与 [getFactDb] 的命名规则一致)。 */
        const val PER_ASSISTANT_DB_PREFIX = "facts_"
        const val PER_ASSISTANT_DB_SUFFIX = ".db"

        /** 主库对应的缓存 key,facts.db 不走 `facts_<id>.db` 命名。 */
        const val DEFAULT_ASSISTANT_KEY = "default"
    }
}
