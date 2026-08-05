package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.R
import io.zer0.muse.tools.resource.ResourceItem
import io.zer0.muse.tools.resource.ResourceLibraryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P1-3b 拆域：资源库工具实现。
 * 由 ResourceToolsRegistrar 注册到 ToolRegistry。
 */
class ResourceToolsImpl(
    private val context: Context,
    private val resourceLibrary: ResourceLibraryStore,
) {
    suspend fun add(args: Map<String, String>): String {
        val title = args["title"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_resource_missing_title)
        val content = args["content"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_resource_missing_content)
        val tags = args["tags"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val id = resourceLibrary.add(title, content, tags)
        return context.getString(R.string.tool_resource_added, id, title)
    }

    suspend fun list(args: Map<String, String>): String {
        val keyword = args["keyword"]
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        return formatList(resourceLibrary.list(keyword, limit))
    }

    suspend fun get(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_resource_missing_id)
        val item = resourceLibrary.get(id)
            ?: return context.getString(R.string.tool_resource_not_found, id)
        return formatItem(item)
    }

    suspend fun delete(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_resource_missing_id)
        return if (resourceLibrary.remove(id)) {
            context.getString(R.string.tool_resource_deleted, id)
        } else {
            context.getString(R.string.tool_resource_not_found, id)
        }
    }

    private fun formatList(list: List<ResourceItem>): String {
        if (list.isEmpty()) return context.getString(R.string.tool_resource_list_empty)
        val sb = StringBuilder(context.getString(R.string.tool_resource_list_header, list.size))
        list.forEach {
            sb.appendLine(context.getString(R.string.tool_resource_list_item, it.id, it.title, it.tags.joinToString(",")))
        }
        return sb.toString().trimEnd()
    }

    private fun formatItem(item: ResourceItem): String {
        return context.getString(
            R.string.tool_resource_item_detail,
            item.id,
            item.title,
            item.tags.joinToString(","),
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAtMillis)),
            item.content,
        )
    }
}
