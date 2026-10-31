package io.zer0.memory.reflection

import io.zer0.common.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * P2-32: 矛盾记忆清单持久化(按 scope+spaceId 分 key 的 JSON 文件)。
 *
 * [MemoryReflectionRunner] 每日检测到矛盾断言后写入,记忆中心读取展示并允许
 * 用户逐条确认/清除 — 解决此前"矛盾检测只打日志"的空转问题。
 */
class MemoryContradictionStore(private val file: File) {

    /**
     * v2.6.2（记忆审计）: 读写互斥锁。
     *
     * 旧实现是“读改写”且无锁、无原子替换 —— 每日反思写入与 UI 清除并发时后写者
     * 覆盖前写者；进程中断还可能留下半截 JSON（随后 readAll 静默回退空列表，数据丢失）。
     */
    private val mutex = kotlinx.coroutines.sync.Mutex()

    /** 一对互相矛盾的记忆文本。 */
    @Serializable
    data class ContradictionPair(val a: String, val b: String)

    @Serializable
    private data class Entry(val key: String, val pairs: List<ContradictionPair>)

    @Serializable
    private data class StoreData(val entries: List<Entry> = emptyList())

    /** 覆盖保存指定 scope+space 的矛盾清单。 */
    suspend fun save(scope: String, spaceId: String, pairs: List<Pair<String, String>>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val key = keyOf(scope, spaceId)
            val all = readAll()
            val updated = all.entries.filterNot { it.key == key } +
                Entry(key, pairs.map { (a, b) -> ContradictionPair(a, b) })
            write(StoreData(updated))
        }
    }

    /** 读取指定 scope+space 的矛盾清单。 */
    suspend fun list(scope: String, spaceId: String): List<ContradictionPair> = withContext(Dispatchers.IO) {
        readAll().entries.firstOrNull { it.key == keyOf(scope, spaceId) }?.pairs ?: emptyList()
    }

    /**
     * 清掉某 scope 在全部 space 下的矛盾清单(助手删除时用)。
     *
     * key 形如 `scope|space`,按 `scope|` 前缀精确匹配 scope 段,不会误删名字前缀相同的其他 scope。
     *
     * @return 被移除的条目数
     */
    suspend fun clearScope(scope: String): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            val key = scope.trim()
            if (key.isEmpty()) return@withContext 0
            val all = readAll()
            val prefix = "$key|"
            val remaining = all.entries.filterNot { it.key.startsWith(prefix) }
            val removed = all.entries.size - remaining.size
            if (removed > 0) write(StoreData(remaining))
            removed
        }
    }

    /** 用户确认后清除其中一对矛盾。 */
    suspend fun removePair(scope: String, spaceId: String, pair: ContradictionPair) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val all = readAll()
            val key = keyOf(scope, spaceId)
            val entry = all.entries.firstOrNull { it.key == key } ?: return@withContext
            val remaining = entry.pairs.filterNot { it.a == pair.a && it.b == pair.b }
            val updated = all.entries
                .map { if (it.key == key) Entry(key, remaining) else it }
                .filter { it.pairs.isNotEmpty() }
            write(StoreData(updated))
        }
    }

    private fun keyOf(scope: String, spaceId: String): String = "$scope|${spaceId.ifBlank { "default" }}"

    private fun readAll(): StoreData = runCatching {
        if (!file.exists()) StoreData() else AppJson.decodeFromString(StoreData.serializer(), file.readText())
    }.getOrDefault(StoreData())

    private fun write(data: StoreData) {
        file.parentFile?.mkdirs()
        // v2.6.2: 原子写入 — 先写临时文件再 rename，避免进程中断留下半截 JSON。
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(AppJson.encodeToString(StoreData.serializer(), data))
        if (!tmp.renameTo(file)) {
            // rename 失败（目标已存在/跨卷）→ 回退直接写，并清理临时文件
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
