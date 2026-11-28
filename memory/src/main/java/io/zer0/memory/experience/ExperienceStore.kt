package io.zer0.memory.experience

import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/**
 * 按文件存储的经验库。
 *
 * 每目录保存 `experience/<assistantId>/<normalizedCategory>.json`。
 * category 做 NFKC 归一化并拒绝路径穿越；同 category+content 幂等，稳定 id 保留。
 *
 * `revision` 是写入计数器：UI 与 prompt 不需要 Room 的 observeAll 也能在写入后立刻
 * 重新读到最新条目 —— 少了这条信号，统一数据源就会退化成「写完看不见」。
 */
data class ExperienceRecordInput(
    val assistantId: String,
    val category: String,
    val content: String,
    val metadata: ExperienceRecordMetadata = ExperienceRecordMetadata(),
)

data class ExperienceRecordMetadata(
    val title: String = "",
    val tags: List<String> = emptyList(),
    val source: String = ExperienceEntry.SOURCE_MANUAL,
    val sessionId: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

class ExperienceStore(
    private val storageDir: File,
) {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val _revision = MutableStateFlow(0L)

    /** 每次成功写入自增；订阅它即可知道何时需要重读。 */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    suspend fun record(
        assistantId: String,
        category: String,
        content: String,
        title: String = "",
        tags: List<String> = emptyList(),
        source: String = ExperienceEntry.SOURCE_MANUAL,
        sessionId: String? = null,
    ): String = recordAt(
        ExperienceRecordInput(
            assistantId = assistantId,
            category = category,
            content = content,
            metadata = ExperienceRecordMetadata(
                title = title,
                tags = tags,
                source = source,
                sessionId = sessionId,
            ),
        ),
    )

    suspend fun recordAt(input: ExperienceRecordInput): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val safeAssistant = validateSegment(input.assistantId, "assistantId")
            val safeCategory = validateSegment(input.category, "category")
            val trimmed = input.content.trim()
            require(trimmed.isNotBlank()) { "content must not be blank" }
            val entries = readFile(categoryFile(safeAssistant, safeCategory))
            val existing = entries.firstOrNull { it.content == trimmed }
            if (existing != null) return@withLock existing.id

            val now = Instant.now().toString()
            val created = input.metadata.createdAt?.takeIf { it.isNotBlank() } ?: now
            val entry = ExperienceEntry(
                id = UUID.randomUUID().toString(),
                assistantId = safeAssistant,
                category = safeCategory,
                content = trimmed,
                createdAt = created,
                title = input.metadata.title.trim(),
                tags = input.metadata.tags.map { it.trim() }.filter { it.isNotEmpty() },
                source = input.metadata.source,
                sessionId = input.metadata.sessionId,
                updatedAt = input.metadata.updatedAt?.takeIf { it.isNotBlank() } ?: created,
            )
            writeLocked(safeAssistant, safeCategory, entries + entry)
            bumpLocked()
            entry.id
        }
    }

    suspend fun list(assistantId: String, category: String? = null): List<ExperienceEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val safeAssistant = validateSegment(assistantId, "assistantId")
            if (category == null) {
                assistantFiles(safeAssistant).flatMap { readFile(it) }.sortedByDescending { it.updatedAt }
            } else {
                val safeCategory = validateSegment(category, "category")
                readFile(categoryFile(safeAssistant, safeCategory)).sortedByDescending { it.updatedAt }
            }
        }
    }

    /** 全部助手的经验，按更新时间降序：prompt 与记忆页读的是同一份。 */
    suspend fun listAll(): List<ExperienceEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            allFiles().flatMap { readFile(it) }.sortedByDescending { it.updatedAt }
        }
    }

    /**
     * 用新条目替换同 id 的旧条目；category 变化时把条目搬到目标分类文件。
     *
     * 找不到该 id 时返回 false —— 编辑不存在的经验必须让调用方看得见，
     * 不能静默成功（UI 会显示"已保存"但内容没变）。
     */
    suspend fun update(entry: ExperienceEntry): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val safeAssistant = validateSegment(entry.assistantId, "assistantId")
            val safeCategory = validateSegment(entry.category, "category")
            val located = locateLocked(entry.id) ?: return@withLock false
            val updated = entry.copy(assistantId = safeAssistant, category = safeCategory, content = entry.content.trim())
            if (located.assistant == safeAssistant && located.category == safeCategory) {
                val rest = readFile(categoryFile(located.assistant, located.category))
                    .map { if (it.id == updated.id) updated else it }
                writeLocked(safeAssistant, safeCategory, rest)
            } else {
                val remaining = readFile(categoryFile(located.assistant, located.category)).filter { it.id != updated.id }
                writeLocked(located.assistant, located.category, remaining)
                val target = readFile(categoryFile(safeAssistant, safeCategory))
                writeLocked(safeAssistant, safeCategory, target + updated)
            }
            bumpLocked()
            true
        }
    }

    /** 按 id 删除；不存在返回 false（同 update：不静默成功）。 */
    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val located = locateLocked(id) ?: return@withLock false
            val remaining = readFile(categoryFile(located.assistant, located.category)).filter { it.id != id }
            writeLocked(located.assistant, located.category, remaining)
            bumpLocked()
            true
        }
    }

    suspend fun size(): Int = withContext(Dispatchers.IO) {
        mutex.withLock { allFiles().size }
    }

    private data class Located(val assistant: String, val category: String)

    private fun locateLocked(id: String): Located? {
        if (id.isBlank()) return null
        storageDir.listFiles().orEmpty().filter { it.isDirectory }.forEach { assistantDir ->
            assistantDir.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.forEach { file ->
                val category = file.nameWithoutExtension
                if (readFile(file).any { it.id == id }) return Located(assistantDir.name, category)
            }
        }
        return null
    }

    private fun validateSegment(raw: String, name: String): String {
        val value = raw.trim().normalize()
        require(value.isNotBlank()) { "$name must not be blank" }
        require(!value.contains("/") && !value.contains("\\") && value != "." && value != ".." && !value.startsWith("..")) {
            "$name contains unsafe path segment"
        }
        return value
    }

    private fun categoryFile(assistantId: String, category: String): File =
        File(File(storageDir, assistantId), "$category.json")

    private fun assistantFiles(assistantId: String): List<File> =
        File(storageDir, assistantId).listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.toList()

    private fun allFiles(): List<File> = storageDir.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()

    private fun readFile(file: File): List<ExperienceEntry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ExperienceEntry.serializer()), file.readText())
        }.getOrElse {
            Logger.w("ExperienceStore", "readFile failed: ${it.message}")
            emptyList()
        }
    }

    private fun writeLocked(assistantId: String, category: String, entries: List<ExperienceEntry>) {
        val target = categoryFile(assistantId, category)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp-${UUID.randomUUID()}")
        try {
            temp.writeText(json.encodeToString(ListSerializer(ExperienceEntry.serializer()), entries))
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun bumpLocked() {
        _revision.value += 1L
    }

    private fun String.normalize(): String = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFKC)
}
