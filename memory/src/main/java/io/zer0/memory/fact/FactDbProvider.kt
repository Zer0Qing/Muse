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
class FactDbProvider(private val context: Context) {
    private val cache = ConcurrentHashMap<String, FactDb>()

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
        return FactStore(getFactDb(assistantId).factDao())
    }

    /**
     * v1.78: 释放指定 assistant 的 FactDb 句柄并从缓存移除。
     *
     * 助手删除时调用,避免已删除助手的 db 连接永久占用(SQLite 文件句柄 + WAL)。
     * 注意:调用后若再次 getFactDb(同 id) 会重建新实例。
     */
    fun release(assistantId: String) {
        val key = if (assistantId.isBlank()) "default" else assistantId
        cache.remove(key)?.close()
    }

    /** v1.78: 释放全部缓存的 FactDb(用于 App 退出或记忆重置)。 */
    fun releaseAll() {
        cache.values.forEach { runCatching { it.close() } }
        cache.clear()
    }
}
