package io.zer0.muse.data.experience

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 基于文件的体验存储(openhanako experience.ts 移植)。
 *
 * 渐进式披露模式:
 *  - experience.md = index (category + description + path)
 *  - experience/{category}.md = category files (numbered list)
 *
 * 索引由 rebuildIndex() 自动生成,无需手写。
 * 与基于 Room 的 ExperienceRepository 协同工作,实现双重存储。
 */
class ExperienceStore(private val context: Context) {

    companion object {
        private const val TAG = "ExperienceStore"
        private const val EXPERIENCE_DIR = "experience"
        private const val INDEX_FILE = "experience.md"
    }

    private val experienceDir: File get() = File(context.filesDir, EXPERIENCE_DIR).also { it.mkdirs() }
    private val indexPath: File get() = File(context.filesDir, INDEX_FILE)

    /** 扫描 experience 目录重建索引文件。 */
    suspend fun rebuildIndex() = withContext(Dispatchers.IO) {
        val dir = experienceDir
        val docs = listExperienceDocuments(dir)
        if (docs.isEmpty()) {
            indexPath.writeText("")
            return@withContext
        }

        val blocks = docs.mapNotNull { doc ->
            val entries = doc.body.lines()
                .filter { it.trim().matches(Regex("^\\d+\\.\\s.*")) }
                .map { it.replace(Regex("^\\d+\\.\\s*"), "").trim() }
            if (entries.isEmpty()) return@mapNotNull null

            val snippets = entries.map { if (it.length > 20) it.take(20) + "..." else it }
            var desc = snippets.joinToString("; ")
            if (desc.length > 120) desc = desc.take(117) + "..."

            "# ${doc.title} (${entries.size} entries)\n$desc\n-> experience/${doc.file}"
        }

        indexPath.writeText(blocks.joinToString("\n\n") + "\n")
    }

    /** 向分类文件记录条目并重建索引。 */
    suspend fun recordEntry(category: String, content: String): RecordResult = withContext(Dispatchers.IO) {
        val safeCategory = normalizeCategory(category)
            ?: return@withContext RecordResult(false, "invalid category")
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return@withContext RecordResult(false, "empty content")

        val dir = experienceDir
        val existingDoc = findDocument(dir, safeCategory)
        val file = existingDoc?.let { File(dir, it.file) } ?: File(dir, buildFileName(safeCategory))
        val existing = if (file.exists()) file.readText() else ""

        // 去重
        if (existing.contains(trimmed)) {
            return@withContext RecordResult(false, "duplicate")
        }

        val lines = existing.lines().filter { it.trim().matches(Regex("^\\d+\\.\\s.*")) }
        val nextNum = lines.size + 1
        val newLine = "$nextNum. $trimmed"

        val updated = if (existing.trimEnd().isNotEmpty()) {
            existing.trimEnd() + "\n" + newLine + "\n"
        } else {
            newLine + "\n"
        }

        val header = "<!-- experience-title: $safeCategory -->\n"
        file.writeText(header + updated)
        rebuildIndex()
        RecordResult(true, category = safeCategory)
    }

    /** 获取索引内容(供 recall_experience 无参调用使用)。 */
    suspend fun getIndex(): String = withContext(Dispatchers.IO) {
        if (indexPath.exists()) indexPath.readText() else ""
    }

    /** 获取指定分类的内容。 */
    suspend fun getCategory(category: String): CategoryContent? = withContext(Dispatchers.IO) {
        val doc = findDocument(experienceDir, category)
        if (doc != null) CategoryContent(doc.title, doc.body) else null
    }

    data class RecordResult(val added: Boolean, val reason: String? = null, val category: String? = null)
    data class CategoryContent(val title: String, val body: String)
    private data class ExperienceDoc(val file: String, val title: String, val body: String)

    private fun normalizeCategory(raw: String): String? {
        val title = raw.trim()
        if (title.isEmpty()) return null
        if (title.contains('/') || title.contains('\\') || title.contains("..")) return null
        if (title == "." || title == "..") return null
        return title
    }

    private fun buildFileName(category: String): String {
        val stem = category.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
            .take(48)
        val hash = category.hashCode().toString(16).take(8)
        return "${stem.ifEmpty { "experience" }}-$hash.md"
    }

    private fun listExperienceDocuments(dir: File): List<ExperienceDoc> {
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles { f -> f.extension == "md" } ?: return emptyList()
        return files.sortedBy { it.name }.map { file ->
            val fallbackTitle = file.nameWithoutExtension
            parseDocument(file.readText(), fallbackTitle)
        }
    }

    private fun parseDocument(content: String, fallbackTitle: String): ExperienceDoc {
        val lines = content.lines()
        val firstLine = lines.firstOrNull()
        val title = if (firstLine?.startsWith("<!-- experience-title:") == true) {
            firstLine.removePrefix("<!-- experience-title:").removeSuffix("-->").trim()
        } else {
            fallbackTitle
        }
        val body = if (firstLine?.startsWith("<!-- experience-title:") == true) {
            lines.drop(1).joinToString("\n").trimStart().trimEnd()
        } else {
            content.trimEnd()
        }
        return ExperienceDoc(
            file = "${title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-")}.md",
            title = title,
            body = body,
        )
    }

    private fun findDocument(dir: File, category: String): ExperienceDoc? {
        return listExperienceDocuments(dir).find {
            it.title.equals(category, ignoreCase = true)
        }
    }
}
