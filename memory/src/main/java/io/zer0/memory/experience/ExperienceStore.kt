package io.zer0.memory.experience

import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
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
 */
class ExperienceStore(
    private val storageDir: File,
) {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    suspend fun record(assistantId: String, category: String, content: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val safeAssistant = validateSegment(assistantId, "assistantId")
            val safeCategory = validateSegment(category, "category")
            val trimmed = content.trim()
            require(trimmed.isNotBlank()) { "content must not be blank" }
            val entries = loadLocked(safeAssistant, safeCategory)
            val existing = entries.firstOrNull { it.content == trimmed }
            if (existing != null) return@withLock existing.id

            val now = Instant.now().toString()
            val entry = ExperienceEntry(
                id = UUID.randomUUID().toString(),
                assistantId = safeAssistant,
                category = safeCategory,
                content = trimmed,
                createdAt = now,
            )
            writeLocked(safeAssistant, safeCategory, entries + entry)
            entry.id
        }
    }

    suspend fun list(assistantId: String, category: String? = null): List<ExperienceEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val safeAssistant = validateSegment(assistantId, "assistantId")
            if (category == null) {
                fileFor(safeAssistant).listFiles().orEmpty()
                    .filter { it.isFile && it.extension == "json" }
                    .flatMap { readFile(it) }
                    .sortedBy { it.createdAt }
            } else {
                val safeCategory = validateSegment(category, "category")
                loadLocked(safeAssistant, safeCategory).sortedBy { it.createdAt }
            }
        }
    }

    suspend fun size(): Int = withContext(Dispatchers.IO) {
        storageDir.walkTopDown().filter { it.isFile && it.extension == "json" }.toList().size
    }

    private fun validateSegment(raw: String, name: String): String {
        val value = raw.trim().normalize()
        require(value.isNotBlank()) { "$name must not be blank" }
        require(!value.contains("/") && !value.contains("\\") && value != "." && value != ".." && !value.startsWith("..")) {
            "$name contains unsafe path segment"
        }
        return value
    }

    private fun fileFor(assistantId: String): File = File(storageDir, assistantId)

    private fun categoryFile(assistantId: String, category: String): File = File(fileFor(assistantId), "$category.json")

    private fun loadLocked(assistantId: String, category: String): List<ExperienceEntry> = readFile(categoryFile(assistantId, category))

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

    private fun String.normalize(): String = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFKC)
}
