package io.zer0.memory.pin

import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
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
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return@withContext ""
        val existing = loadEntries()
        // 去重
        if (existing.any { it.content == trimmed }) {
            Logger.d(TAG, "pinned memory dedup: already exists")
            return@withContext existing.first { it.content == trimmed }.id
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

    /** 按 id 删除。返回是否成功。 */
    suspend fun removeById(id: String): Boolean = withContext(Dispatchers.IO) {
        val existing = loadEntries()
        val filtered = existing.filter { it.id != id }
        if (filtered.size == existing.size) return@withContext false
        writeBoth(filtered)
        Logger.d(TAG, "pinned memory removed by id: $id")
        true
    }

    /** 按关键词删除（内容包含关键词的第一条）。返回是否成功。 */
    suspend fun removeByKeyword(keyword: String): Boolean = withContext(Dispatchers.IO) {
        val existing = loadEntries()
        val target = existing.firstOrNull { it.content.contains(keyword, ignoreCase = true) }
        if (target == null) return@withContext false
        val filtered = existing.filter { it.id != target.id }
        writeBoth(filtered)
        Logger.d(TAG, "pinned memory removed by keyword: $keyword")
        true
    }

    /** 替换指定 id 的内容。返回是否成功。 */
    suspend fun replace(id: String, newContent: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = newContent.trim()
        if (trimmed.isEmpty()) return@withContext false
        val existing = loadEntries()
        val idx = existing.indexOfFirst { it.id == id }
        if (idx < 0) return@withContext false
        val updated = existing.toMutableList()
        updated[idx] = updated[idx].copy(content = trimmed, updatedAt = Instant.now().toString())
        writeBoth(updated)
        Logger.d(TAG, "pinned memory replaced: $id")
        true
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

    private fun loadEntries(): List<PinnedEntry> {
        val jsonEntries = readJson()
        val mdEntries = readMarkdown()
        return mergeByNewest(jsonEntries, mdEntries)
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
        storageDir.mkdirs()
        // JSON
        jsonFile.writeText(json.encodeToString(ListSerializer(PinnedEntry.serializer()), entries))
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
        mdFile.writeText(md)
    }
}
