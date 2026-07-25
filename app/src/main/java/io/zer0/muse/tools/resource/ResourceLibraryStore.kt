package io.zer0.muse.tools.resource

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * v1.136: 本地资源库。
 *
 * 用于保存、检索、删除文本资源片段(如常用提示词、参考内容、笔记等),
 * 数据以 JSON 文件形式存储在应用私有目录。
 */
class ResourceLibraryStore(private val context: Context) {

    private val file by lazy { java.io.File(context.filesDir, "resources/library.json") }

    /** 列出资源,支持按关键字过滤。 */
    fun list(keyword: String? = null, limit: Int = 20): List<ResourceItem> {
        var all = readAll()
        if (!keyword.isNullOrBlank()) {
            val kw = keyword.lowercase()
            all = all.filter {
                it.title.lowercase().contains(kw) ||
                    it.content.lowercase().contains(kw) ||
                    it.tags.any { tag -> tag.lowercase().contains(kw) }
            }
        }
        return all.take(limit)
    }

    /** 根据 id 获取单个资源。 */
    fun get(id: String): ResourceItem? {
        return readAll().find { it.id == id }
    }

    /**
     * 添加资源。
     *
     * @return 资源 id
     */
    fun add(title: String, content: String, tags: List<String>): String {
        val now = System.currentTimeMillis()
        val item = ResourceItem(
            id = java.util.UUID.randomUUID().toString(),
            title = title,
            content = content,
            tags = tags,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        val all = readAll().toMutableList().apply { add(item) }
        writeAll(all)
        return item.id
    }

    /** 更新指定资源。 */
    fun update(id: String, title: String?, content: String?, tags: List<String>?): Boolean {
        val all = readAll().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return false
        val old = all[index]
        all[index] = old.copy(
            title = title ?: old.title,
            content = content ?: old.content,
            tags = tags ?: old.tags,
            updatedAtMillis = System.currentTimeMillis(),
        )
        writeAll(all)
        return true
    }

    /** 删除指定资源。 */
    fun remove(id: String): Boolean {
        val all = readAll().toMutableList()
        val removed = all.removeIf { it.id == id }
        if (removed) writeAll(all)
        return removed
    }

    private fun readAll(): List<ResourceItem> {
        return try {
            if (!file.exists()) return emptyList()
            AppJson.decodeFromString(ListSerializer(ResourceItem.serializer()), file.readText())
        } catch (e: Exception) {
            Logger.w(TAG, "读取资源库失败: ${e.message}")
            emptyList()
        }
    }

    private fun writeAll(list: List<ResourceItem>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(AppJson.encodeToString(ListSerializer(ResourceItem.serializer()), list))
        } catch (e: Exception) {
            Logger.w(TAG, "写入资源库失败: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ResourceLibraryStore"
    }
}

@Serializable
data class ResourceItem(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("content") val content: String,
    @SerialName("tags") val tags: List<String>,
    @SerialName("created_at") val createdAtMillis: Long,
    @SerialName("updated_at") val updatedAtMillis: Long,
)
