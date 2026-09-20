package io.zer0.memory.reflection

import io.zer0.common.AppJson
import kotlinx.coroutines.Dispatchers
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

    /** 一对互相矛盾的记忆文本。 */
    @Serializable
    data class ContradictionPair(val a: String, val b: String)

    @Serializable
    private data class Entry(val key: String, val pairs: List<ContradictionPair>)

    @Serializable
    private data class StoreData(val entries: List<Entry> = emptyList())

    /** 覆盖保存指定 scope+space 的矛盾清单。 */
    suspend fun save(scope: String, spaceId: String, pairs: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        val key = keyOf(scope, spaceId)
        val all = readAll()
        val updated = all.entries.filterNot { it.key == key } +
            Entry(key, pairs.map { (a, b) -> ContradictionPair(a, b) })
        write(StoreData(updated))
    }

    /** 读取指定 scope+space 的矛盾清单。 */
    suspend fun list(scope: String, spaceId: String): List<ContradictionPair> = withContext(Dispatchers.IO) {
        readAll().entries.firstOrNull { it.key == keyOf(scope, spaceId) }?.pairs ?: emptyList()
    }

    /** 用户确认后清除其中一对矛盾。 */
    suspend fun removePair(scope: String, spaceId: String, pair: ContradictionPair) = withContext(Dispatchers.IO) {
        val all = readAll()
        val key = keyOf(scope, spaceId)
        val entry = all.entries.firstOrNull { it.key == key } ?: return@withContext
        val remaining = entry.pairs.filterNot { it.a == pair.a && it.b == pair.b }
        val updated = all.entries
            .map { if (it.key == key) Entry(key, remaining) else it }
            .filter { it.pairs.isNotEmpty() }
        write(StoreData(updated))
    }

    private fun keyOf(scope: String, spaceId: String): String = "$scope|${spaceId.ifBlank { "default" }}"

    private fun readAll(): StoreData = runCatching {
        if (!file.exists()) StoreData() else AppJson.decodeFromString(StoreData.serializer(), file.readText())
    }.getOrDefault(StoreData())

    private fun write(data: StoreData) {
        file.parentFile?.mkdirs()
        file.writeText(AppJson.encodeToString(StoreData.serializer(), data))
    }
}