package io.zer0.muse.tools

import io.zer0.memory.pin.PinnedMemoryStore
import kotlinx.coroutines.runBlocking

/**
 * unpin_memory 工具(openhanako pinned-memory-store.ts 移植)。
 *
 * 允许 AI 按关键词或 id 移除已置顶的记忆。
 */
object UnpinMemoryTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "unpin_memory",
        description = "Remove a pinned memory. Use when the user asks to forget something previously pinned, " +
            "or when pinned information is no longer accurate.",
        parameters = mapOf(
            "keyword" to "Optional. Remove the first pinned memory whose content contains this keyword.",
            "id" to "Optional. Remove by exact memory id prefix.",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    fun execute(args: Map<String, String>, store: PinnedMemoryStore): String {
        val keyword = args["keyword"]?.trim()
        val id = args["id"]?.trim()
        if (keyword.isNullOrEmpty() && id.isNullOrEmpty()) {
            return "Error: provide either 'keyword' or 'id' to identify the memory to unpin."
        }
        return runBlocking {
            val removed = if (!id.isNullOrEmpty()) {
                // 尝试精确前缀匹配
                val all = store.getAll()
                val target = all.firstOrNull { it.id.startsWith(id) }
                if (target != null) store.removeById(target.id) else false
            } else {
                // v1.131: keyword 此处非空(上面已 guard),用 requireNotNull 替代 !! 让契约更显式,
                // 误用会抛 IllegalArgumentException 而非 NPE,定位更清晰。
                requireNotNull(keyword) { "keyword must be non-null when id is empty" }
                store.removeByKeyword(keyword)
            }
            if (removed) "Unpinned successfully."
            else "No matching pinned memory found."
        }
    }
}
