package io.zer0.memory.reflection

import io.zer0.common.AppJson
import io.zer0.common.Logger
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
            val all = readAllForWrite()
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
            val all = readAllForWrite()
            val prefix = "$key|"
            val remaining = all.entries.filterNot { it.key.startsWith(prefix) }
            val removed = all.entries.size - remaining.size
            if (removed > 0) write(StoreData(remaining))
            removed
        }
    }

    /**
     * 清空整份清单（记忆重置用）。
     *
     * 重置的预期是「回到刚装好的状态」，所以直接写一份空表：不读旧内容、不逐条删，
     * 旧文件已损坏也不该让重置失败。写失败只记日志 —— 清单是派生视图，
     * 不能因为它拖住同一次重置里的其它环节。
     *
     * 损坏文件的归档/拒写语义只属于 [readAllForWrite]，这里不读、不走那条路。
     *
     * @return true 表示空表已落盘
     */
    // 重置是收尾动作:清不掉只该记日志,不能把同一次重置的其它环节拖下水。
    @Suppress("TooGenericExceptionCaught")
    suspend fun clearAll(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                write(StoreData())
                true
            } catch (e: Exception) {
                Logger.w(TAG, "矛盾清单清空失败（原文件保留原状）: ${e.message}", e)
                false
            }
        }
    }

    /**
     * Space 合并:把每个 scope 下 `scope|source` 的矛盾清单并进 `scope|target`,同一对只留一份。
     *
     * 与 [save] 一样在 mutex 下走 [readAllForWrite] → 一次 [write]:这是「读改写」,
     * 分两次写会让并发保存的其它 key 丢掉一半。
     *
     * @return 真正新并入的矛盾对数（目标里已存在的同一对不重复计数）
     */
    suspend fun mergeSpaceInto(sourceSpaceId: String, targetSpaceId: String): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            val source = sourceSpaceId.trim()
            val target = targetSpaceId.trim()
            if (source.isEmpty() || target.isEmpty() || source == target) {
                Logger.w(TAG, "mergeSpaceInto: 空间 id 不合法(source=$sourceSpaceId, target=$targetSpaceId)")
                return@withContext 0
            }
            val all = readAllForWrite()
            val sourceSuffix = "|$source"
            val movingByTarget = all.entries
                .filter { entry -> entry.key.endsWith(sourceSuffix) }
                .associate { entry ->
                    val scope = entry.key.removeSuffix(sourceSuffix)
                    "$scope|$target" to entry.pairs
                }
            if (movingByTarget.isEmpty()) return@withContext 0

            var merged = 0
            val rebuilt = mutableListOf<Entry>()
            all.entries.forEach { entry ->
                val movingPairs = movingByTarget[entry.key]
                when {
                    // 源条目本身丢弃（它的内容已经在 movingByTarget 里）
                    entry.key.endsWith(sourceSuffix) -> Unit

                    movingPairs != null -> {
                        val seen = entry.pairs.map { pair -> pair.a to pair.b }.toMutableSet()
                        val additions = movingPairs.filter { pair -> seen.add(pair.a to pair.b) }
                        merged += additions.size
                        rebuilt += Entry(entry.key, entry.pairs + additions)
                    }

                    else -> rebuilt += entry
                }
            }
            // 目标 key 原本不存在:直接落一份（空对不必造一个空 key）。
            for ((key, pairs) in movingByTarget) {
                if (pairs.isEmpty() || rebuilt.any { it.key == key }) continue
                merged += pairs.size
                rebuilt += Entry(key, pairs)
            }
            write(StoreData(rebuilt))
            merged
        }
    }

    /** 用户确认后清除其中一对矛盾。 */
    suspend fun removePair(scope: String, spaceId: String, pair: ContradictionPair) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val all = readAllForWrite()
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

    /**
     * 只读路径：坏文件按空表展示，但**不动文件**，也留一句日志。
     *
     * 读不能改状态；真要重建，走 [readAllForWrite]。
     */
    private fun readAll(): StoreData {
        if (!file.exists()) return StoreData()
        val text = runCatching { file.readText() }.getOrNull()
        val decoded = text?.let { body -> decode(body) }
        if (decoded == null) {
            Logger.w(TAG, "矛盾清单读不出，本次按空表展示（文件保留不动）: ${file.name}")
            return StoreData()
        }
        return decoded
    }

    /**
     * 写路径必须先分清「没有文件」与「文件坏了」。
     *
     * 这是此前那条静默数据丢失的入口：坏文件被当成空表，于是任何一次 `save`
     * 都会用「只含本次 key」的内容覆盖整份文件 —— 其它助手 / 空间的矛盾清单被抹掉，
     * 不留任何痕迹（本类自己的注释就把这件事写作"数据丢失"）。
     *
     * 按 [io.zer0.memory.fact.FactDb] 的既有做法：先改名归档留下证据，再从空表重建；
     * **归档不了就拒绝这次写**，宁可停在原状也不能覆盖掉唯一一份数据。
     */
    private fun readAllForWrite(): StoreData {
        if (!file.exists()) return StoreData()
        val text = runCatching { file.readText() }.getOrElse {
            quarantineUndecodable("read_failed")
            return StoreData()
        }
        return decode(text) ?: run {
            quarantineUndecodable("undecodable")
            StoreData()
        }
    }

    private fun decode(body: String): StoreData? = runCatching {
        AppJson.decodeFromString(StoreData.serializer(), body)
    }.getOrNull()

    private fun quarantineUndecodable(reason: String) {
        if (!file.exists()) return
        val backup = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
        val moved = runCatching { file.renameTo(backup) }.getOrDefault(false)
        check(moved) { "矛盾清单已损坏且无法归档，拒绝覆盖写以保住原文件: ${file.absolutePath}" }
        Logger.w(TAG, "矛盾清单损坏($reason)，已归档为 ${backup.name}，从空表重建")
    }

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

    private companion object {
        private const val TAG: String = "MemoryContradictionStore"
    }
}
