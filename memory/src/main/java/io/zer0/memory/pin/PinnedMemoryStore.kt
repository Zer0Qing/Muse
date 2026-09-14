package io.zer0.memory.pin

import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * 置顶记忆存储。
 *
 * 用户主动要求 AI 记住的内容，始终保留在 system prompt 中。
 * 双格式存储：
 *  - JSON 文件（结构化，程序读写）
 *  - Markdown 文件（人类可读，调试/导出）
 *
 * 去重：相同内容不重复添加。
 * 双文件合并：按 id 合并，以 updatedAt 较新的条目为准。
 */
class PinnedMemoryStore(
    private val storageDir: File,
) {

    companion object {
        private const val TAG = "PinnedMemoryStore"
        private const val JSON_FILE = "pinned-memory.json"
        private const val MD_FILE = "pinned.md"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val jsonFile = File(storageDir, JSON_FILE)
    private val mdFile = File(storageDir, MD_FILE)

    // 审查修复 (2.0 C-04): mtime 缓存 — 文件未变化时复用内存结果。
    // SystemPromptAssembler 每次组装 system prompt 都调用 renderForPrompt,
    // 无缓存时每次读双文件(JSON + Markdown 合并),高频对话下无谓磁盘 I/O。
    @Volatile
    private var cacheJsonMtime: Long = -1L
    @Volatile
    private var cacheMdMtime: Long = -1L
    @Volatile
    private var cachedEntries: List<PinnedEntry>? = null

    /**
     * 审查修复 (B-20): 进程级写锁 — add/removeById/removeByKeyword/removeByContent/
     * removeByContentFlexible/replace 的 loadEntries+writeBoth 都是读-改-写,并发调用
     * 会以对方的旧快照覆盖(丢失更新)。所有写方法整体持锁,保证 loadEntries+writeBoth 原子。
     * (与 FactStore 墓碑的锁模式一致,但为实例级;PinnedMemoryStore 无多实例共享文件场景。)
     */
    private val writeLock = Mutex()

    @Serializable
    data class PinnedEntry(
        val id: String,
        val content: String,
        val createdAt: String,
        val updatedAt: String,
    )

    /** 加载全部置顶记忆（JSON 与 Markdown 合并，较新者优先）。 */
    suspend fun getAll(): List<PinnedEntry> = withContext(Dispatchers.IO) {
        loadEntries()
    }

    /** 添加一条置顶记忆。相同内容去重。返回 entry id。 */
    suspend fun add(content: String): String = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val trimmed = content.trim()
            if (trimmed.isEmpty()) return@withLock ""
            val existing = loadEntries()
            // 去重
            if (existing.any { it.content == trimmed }) {
                Logger.d(TAG, "pinned memory dedup: already exists")
                return@withLock existing.first { it.content == trimmed }.id
            }
            val now = Instant.now().toString()
            val entry = PinnedEntry(
                id = UUID.randomUUID().toString(),
                content = trimmed,
                createdAt = now,
                updatedAt = now,
            )
            writeBoth(existing + entry)
            Logger.d(TAG, "pinned memory added: ${entry.id}")
            entry.id
        }
    }

    /** 按 id 删除。返回是否成功。 */
    suspend fun removeById(id: String): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val existing = loadEntries()
            val filtered = existing.filter { it.id != id }
            if (filtered.size == existing.size) return@withLock false
            writeBoth(filtered)
            Logger.d(TAG, "pinned memory removed by id: $id")
            true
        }
    }

    /** 按关键词删除（内容包含关键词的第一条）。返回是否成功。 */
    suspend fun removeByKeyword(keyword: String): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val existing = loadEntries()
            val target = existing.firstOrNull { it.content.contains(keyword, ignoreCase = true) }
            if (target == null) return@withLock false
            val filtered = existing.filter { it.id != target.id }
            writeBoth(filtered)
            Logger.d(TAG, "pinned memory removed by keyword: $keyword")
            true
        }
    }

    /**
     * F-8: 按内容精确删除（去除首尾空白后完全相等的第一条）。
     *
     * 与 [removeByKeyword]（包含式匹配）语义不同，用于记忆页 UI 取消置顶时
     * 精确移除对应内容，避免误删包含该文本的其他置顶条目。
     */
    suspend fun removeByContent(content: String): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val target = loadEntries().firstOrNull { it.content.trim() == content.trim() }
            if (target == null) return@withLock false
            removeByIdLocked(target.id)
        }
    }

    /**
     * 审查修复 (B-18): 保守取消置顶 — 优先精确匹配,精确匹配失败时退化为包含匹配
     * (含 ignoreCase),供 facet 内容被改写过(PinnedMemoryStore 无改写,但外部注入侧
     * 措辞可能变化)的残留清理兜底。绝不跨条目误删回去按原内容首条。
     * 返回是否删除成功。
     */
    suspend fun removeByContentFlexible(content: String): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val trimmed = content.trim()
            val existing = loadEntries()
            val target = existing.firstOrNull { it.content.trim() == trimmed }
                ?: existing.firstOrNull { it.content.contains(trimmed, ignoreCase = true) }
            if (target == null) return@withLock false
            writeBoth(existing.filter { it.id != target.id })
            Logger.d(TAG, "pinned memory removed (flexible): ${target.id}")
            true
        }
    }

    /** 替换指定 id 的内容。返回是否成功。 */
    suspend fun replace(id: String, newContent: String): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val trimmed = newContent.trim()
            if (trimmed.isEmpty()) return@withLock false
            val existing = loadEntries()
            val idx = existing.indexOfFirst { it.id == id }
            if (idx < 0) return@withLock false
            val updated = existing.toMutableList()
            updated[idx] = updated[idx].copy(content = trimmed, updatedAt = Instant.now().toString())
            writeBoth(updated)
            Logger.d(TAG, "pinned memory replaced: $id")
            true
        }
    }

    /** 生成注入 system prompt 的文本（所有置顶记忆拼接）。 */
    suspend fun renderForPrompt(): String = withContext(Dispatchers.IO) {
        val entries = loadEntries()
        if (entries.isEmpty()) return@withContext ""
        buildString {
            appendLine("## Pinned Memories (用户要求始终记住的内容)")
            for (e in entries) {
                appendLine("- ${e.content}")
            }
        }.trimEnd()
    }

    // ─── 内部 I/O ───

    /**
     * 审查修复 (B-20): 调用方必须已持有 [writeLock] 的按 id 删除。
     * removeByContent 已在 withLock 内,不能再次 removeById(会重复拿锁死锁)。
     */
    private fun removeByIdLocked(id: String): Boolean {
        val existing = loadEntries()
        val filtered = existing.filter { it.id != id }
        if (filtered.size == existing.size) return false
        writeBoth(filtered)
        Logger.d(TAG, "pinned memory removed by id: $id")
        return true
    }

    private fun loadEntries(): List<PinnedEntry> {
        // C-04: mtime 未变 → 直接返回缓存
        val jsonMtime = if (jsonFile.exists()) jsonFile.lastModified() else -1L
        val mdMtime = if (mdFile.exists()) mdFile.lastModified() else -1L
        val cached = cachedEntries
        if (cached != null && jsonMtime == cacheJsonMtime && mdMtime == cacheMdMtime) {
            return cached
        }
        val entries = mergeByNewest(readJson(), readMarkdown())
        cachedEntries = entries
        cacheJsonMtime = jsonMtime
        cacheMdMtime = mdMtime
        return entries
    }

    private fun mergeByNewest(primary: List<PinnedEntry>, secondary: List<PinnedEntry>): List<PinnedEntry> {
        val byId = linkedMapOf<String, PinnedEntry>()
        for (entry in primary) byId[entry.id] = entry
        for (entry in secondary) {
            val existing = byId[entry.id]
            if (existing == null || compareTimestamps(entry.updatedAt, existing.updatedAt) > 0) {
                byId[entry.id] = entry
            }
        }
        return byId.values.toList()
    }

    private fun compareTimestamps(a: String, b: String): Int {
        val aTime = runCatching { Instant.parse(a) }.getOrNull()
        val bTime = runCatching { Instant.parse(b) }.getOrNull()
        return when {
            aTime == null && bTime == null -> 0
            aTime == null -> -1
            bTime == null -> 1
            else -> aTime.compareTo(bTime)
        }
    }

    private fun readJson(): List<PinnedEntry> {
        if (!jsonFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(PinnedEntry.serializer()), jsonFile.readText())
        }.getOrElse {
            Logger.w(TAG, "readJson failed: ${it.message}")
            emptyList()
        }
    }

    private fun readMarkdown(): List<PinnedEntry> {
        if (!mdFile.exists()) return emptyList()
        val lines = runCatching { mdFile.readLines() }.getOrElse {
            Logger.w(TAG, "readMarkdown failed: ${it.message}")
            return emptyList()
        }
        val entries = mutableListOf<PinnedEntry>()
        var pendingContent: String? = null
        var pendingMeta: Map<String, String> = emptyMap()
        for (line in lines) {
            val trimmed = line.trim()
            val bullet = Regex("^[-*+]\\s+(.+)$").find(trimmed)
            val meta = Regex("^<!--\\s*id: ([^|]+)\\| created: ([^|]+)(?:\\| updated: ([^|]+))?\\s*-->$").find(trimmed)
            when {
                bullet != null -> {
                    pendingContent?.let { content ->
                        val metaId = pendingMeta["id"] ?: UUID.randomUUID().toString()
                        val created = pendingMeta["created"] ?: Instant.now().toString()
                        val updated = pendingMeta["updated"] ?: created
                        entries.add(PinnedEntry(metaId, content, created, updated))
                    }
                    pendingContent = bullet.groupValues[1].trim()
                    pendingMeta = emptyMap()
                }
                meta != null -> {
                    val updatedValue = meta.groupValues[3].takeIf { it.isNotBlank() }?.trim() ?: meta.groupValues[2].trim()
                    pendingMeta = mapOf(
                        "id" to meta.groupValues[1].trim(),
                        "created" to meta.groupValues[2].trim(),
                        "updated" to updatedValue,
                    )
                }
            }
        }
        pendingContent?.let { content ->
            val metaId = pendingMeta["id"] ?: UUID.randomUUID().toString()
            val created = pendingMeta["created"] ?: Instant.now().toString()
            val updated = pendingMeta["updated"] ?: created
            entries.add(PinnedEntry(metaId, content, created, updated))
        }
        return entries
    }

    private fun writeBoth(entries: List<PinnedEntry>) {
        // C-04: 写入后显式失效缓存(同毫秒写入可能 mtime 不变,不能只靠 mtime)
        cachedEntries = null
        storageDir.mkdirs()
        // 审查修复 (B-20): 临时文件 + rename 原子替换,避免进程被杀/写一半留下半截文件
        // (JSON 解析失败;与 FactStore 墓碑写入模式一致)。
        writeAtomic(jsonFile, json.encodeToString(ListSerializer(PinnedEntry.serializer()), entries))
        // Markdown
        val md = buildString {
            appendLine("# Pinned Memories")
            appendLine()
            for (e in entries) {
                appendLine("- ${e.content}")
                appendLine("  <!-- id: ${e.id} | created: ${e.createdAt} | updated: ${e.updatedAt} -->")
                appendLine()
            }
        }
        writeAtomic(mdFile, md)
    }

    /** 写入目标文件;先写临时文件再 rename 原子替换,rename 失败时回退直写(尽力而为)。 */
    private fun writeAtomic(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            file.writeText(content)
            tmp.delete()
        }
    }
}
