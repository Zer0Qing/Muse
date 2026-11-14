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

        /** 语义去重粗筛：长度比超过这个值的不进入候选对。 */
        private const val SEMANTIC_LEN_RATIO = 2.0

        /** 内容规范化用的空白匹配。 */
        private val WHITESPACE_RUN = Regex("\\s+")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
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
        /** null 表示旧版/显式全局置顶;非 null 表示助手私有置顶。 */
        val assistantId: String? = null,
    )

    /** 加载全部置顶记忆（JSON 与 Markdown 合并，较新者优先）。 */
    suspend fun getAll(): List<PinnedEntry> = withContext(Dispatchers.IO) {
        loadEntries()
    }

    /** 添加一条置顶记忆。相同内容去重。返回 entry id。 */
    suspend fun add(content: String, assistantId: String? = null): String = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val trimmed = content.trim()
            if (trimmed.isEmpty()) return@withLock ""
            val scopedAssistantId = assistantId?.trim()?.takeIf { it.isNotEmpty() }
            val existing = loadEntries()
            // 去重
            if (existing.any { it.content == trimmed && it.assistantId == scopedAssistantId }) {
                Logger.d(TAG, "pinned memory dedup: already exists")
                return@withLock existing.first { it.content == trimmed && it.assistantId == scopedAssistantId }.id
            }
            val now = Instant.now().toString()
            val entry = PinnedEntry(
                id = UUID.randomUUID().toString(),
                content = trimmed,
                createdAt = now,
                updatedAt = now,
                assistantId = scopedAssistantId,
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
        renderEntries(loadEntries())
    }

    /**
     * 精确去重：内容去掉首尾空白、压缩内部空白、忽略大小写后相同的条目。
     *
     * 不调模型，纯字符串比较 —— 覆盖「同一条内容存了两遍」这种最常见情况。
     * 同一组内保留最早创建的那条（createdAt 最小），其余删除。
     *
     * @return 被删除的条目数量
     */
    suspend fun dedupExact(): Int = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val existing = loadEntries()
            dedupEntriesLocked(existing, existing)
        }
    }

    /**
     * 在一个助手可见范围内做精确去重。
     *
     * 全局旧条目只有在调用方明确允许时才参与；其它助手的条目保持原样，
     * 避免记忆页或工具在不同助手之间误合并内容。
     */
    suspend fun dedupExactForAssistant(assistantId: String?, includeGlobal: Boolean): Int = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val existing = loadEntries()
            val visible = if (assistantId == null) {
                existing
            } else {
                existing.filter { entry ->
                    entry.assistantId == assistantId || (includeGlobal && entry.assistantId == null)
                }
            }
            dedupEntriesLocked(existing, visible)
        }
    }

    private fun dedupEntriesLocked(existing: List<PinnedEntry>, candidates: List<PinnedEntry>): Int {
        if (candidates.size < 2) return 0
        // 按规范化内容分组；assistantId 不同视为不同条目，不能合并。
        val groups = candidates.groupBy { entry ->
            normalizeForDedup(entry.content) to entry.assistantId
        }
        val keep = mutableListOf<PinnedEntry>()
        var removed = 0
        for ((_, group) in groups) {
            if (group.size == 1) {
                keep.add(group.first())
                continue
            }
            // 同组内保留创建最早的一条；createdAt 相同则保留 id 字典序最小的，保证结果稳定。
            val sorted = group.sortedWith(
                compareBy<PinnedEntry> { it.createdAt }.thenBy { it.id },
            )
            keep.add(sorted.first())
            removed += group.size - 1
        }
        if (removed > 0) {
            // 保持原有顺序：候选范围内按去重结果过滤，范围外条目原样保留。
            val candidateIds = candidates.map { it.id }.toSet()
            val keepIds = keep.map { it.id }.toSet()
            writeBoth(existing.filter { it.id !in candidateIds || it.id in keepIds })
            Logger.i(TAG, "pinned memory dedup (exact): removed $removed")
        }
        return removed
    }

    /**
     * 查找语义重复候选对（供上层调模型判定）。
     *
     * 不做相似度计算，只把「同 assistant scope 下所有两两组合」交给调用方，
     * 由调用方用 [io.zer0.memory.fact.FactDedupJudge] 逐对判定。
     * 条数多时组合数会爆炸，所以先按首字符分桶粗筛：只比较首字符相同或
     * 长度接近的组合，降低调用量。
     */
    suspend fun candidatePairsForSemanticDedup(assistantId: String?, includeGlobal: Boolean): List<Pair<PinnedEntry, PinnedEntry>> =
        withContext(Dispatchers.IO) {
            val entries = if (assistantId == null) {
                loadEntries()
            } else {
                getForAssistant(assistantId, includeGlobal)
            }
            if (entries.size < 2) return@withContext emptyList()
            val pairs = mutableListOf<Pair<PinnedEntry, PinnedEntry>>()
            for (i in entries.indices) {
                for (j in i + 1 until entries.size) {
                    val a = entries[i]
                    val b = entries[j]
                    if (a.assistantId != b.assistantId) continue
                    // 粗筛：长度差异超过 2 倍的不太可能是同一事实的不同措辞
                    val lenA = a.content.length
                    val lenB = b.content.length
                    if (lenA == 0 || lenB == 0) continue
                    if (maxOf(lenA, lenB) > minOf(lenA, lenB) * SEMANTIC_LEN_RATIO) continue
                    pairs.add(a to b)
                }
            }
            pairs
        }

    /** 按 id 批量删除（去重合并用）。返回实际删除数量。 */
    suspend fun removeByIds(ids: Collection<String>): Int = withContext(Dispatchers.IO) {
        writeLock.withLock {
            if (ids.isEmpty()) return@withLock 0
            val existing = loadEntries()
            val idSet = ids.toSet()
            val filtered = existing.filter { it.id !in idSet }
            val removed = existing.size - filtered.size
            if (removed > 0) {
                writeBoth(filtered)
                Logger.i(TAG, "pinned memory removed by ids: $removed")
            }
            removed
        }
    }

    /** 内容规范化：保留普通词间空格，但忽略汉字之间仅用于排版的空白。 */
    private fun normalizeForDedup(content: String): String {
        val collapsed = content.trim().replace(WHITESPACE_RUN, " ").lowercase()
        if (collapsed.isEmpty()) return collapsed
        return buildString(collapsed.length) {
            collapsed.forEachIndexed { index, char ->
                val betweenHan = char == ' ' && index > 0 && index < collapsed.lastIndex &&
                    isHan(collapsed[index - 1]) && isHan(collapsed[index + 1])
                if (!betweenHan) append(char)
            }
        }
    }

    private fun isHan(char: Char): Boolean = char in '\u3400'..'\u4DBF' || char in '\u4E00'..'\u9FFF' || char in '\uF900'..'\uFAFF'

    /** 生成指定助手可见的置顶记忆;旧条目只有在显式允许全局时才返回。 */
    suspend fun renderForAssistant(assistantId: String, includeGlobal: Boolean): String = withContext(Dispatchers.IO) {
        renderEntries(getForAssistant(assistantId, includeGlobal))
    }

    /** 查询指定助手可见的置顶记忆。 */
    suspend fun getForAssistant(assistantId: String, includeGlobal: Boolean): List<PinnedEntry> = withContext(Dispatchers.IO) {
        val normalized = assistantId.trim()
        loadEntries().filter { entry ->
            entry.assistantId == normalized || (includeGlobal && entry.assistantId == null)
        }
    }

    /** 在助手 scope 内按内容取消置顶,避免同名记忆误删其他助手的数据。 */
    suspend fun removeByIdForAssistant(id: String, assistantId: String, includeGlobal: Boolean): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val existing = loadEntries()
            val target = existing.firstOrNull { entry ->
                entry.id == id &&
                    (entry.assistantId == assistantId || (includeGlobal && entry.assistantId == null))
            } ?: return@withLock false
            writeBoth(existing.filter { it.id != target.id })
            Logger.d(TAG, "pinned memory removed for assistant: ${target.id}")
            true
        }
    }

    /** 在助手 scope 内按内容取消置顶,避免同名记忆误删其他助手的数据。 */
    suspend fun removeByContentFlexibleForAssistant(content: String, assistantId: String, includeGlobal: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                val trimmed = content.trim()
                val existing = loadEntries()
                val target = existing.firstOrNull { entry ->
                    entry.assistantId == assistantId ||
                        (includeGlobal && entry.assistantId == null)
                }?.takeIf { entry ->
                    entry.content.trim() == trimmed || entry.content.contains(trimmed, ignoreCase = true)
                }
                if (target == null) return@withLock false
                writeBoth(existing.filter { it.id != target.id })
                Logger.d(TAG, "pinned memory removed for assistant: ${target.id}")
                true
            }
        }

    private fun renderEntries(entries: List<PinnedEntry>): String {
        if (entries.isEmpty()) return ""
        return buildString {
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
            val meta =
                Regex(
                    "^<!--\\s*id: ([^|]+)\\| created: ([^|]+)(?:\\| updated: ([^|]+))?" +
                        "(?:\\| assistant: ([^|]*))?\\s*-->$",
                ).find(trimmed)
            when {
                bullet != null -> {
                    pendingContent?.let { content ->
                        val metaId = pendingMeta["id"] ?: UUID.randomUUID().toString()
                        val created = pendingMeta["created"] ?: Instant.now().toString()
                        val updated = pendingMeta["updated"] ?: created
                        entries.add(
                            PinnedEntry(
                                id = metaId,
                                content = content,
                                createdAt = created,
                                updatedAt = updated,
                                assistantId = pendingMeta["assistant"]?.takeIf { it.isNotBlank() },
                            ),
                        )
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
                        "assistant" to meta.groupValues.getOrNull(4).orEmpty().trim(),
                    )
                }
            }
        }
        pendingContent?.let { content ->
            val metaId = pendingMeta["id"] ?: UUID.randomUUID().toString()
            val created = pendingMeta["created"] ?: Instant.now().toString()
            val updated = pendingMeta["updated"] ?: created
            entries.add(
                PinnedEntry(
                    id = metaId,
                    content = content,
                    createdAt = created,
                    updatedAt = updated,
                    assistantId = pendingMeta["assistant"]?.takeIf { it.isNotBlank() },
                ),
            )
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
                appendLine(
                    "  <!-- id: ${e.id} | created: ${e.createdAt} | updated: ${e.updatedAt} " +
                        "| assistant: ${e.assistantId.orEmpty()} -->",
                )
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
