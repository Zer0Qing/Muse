package io.zer0.muse.tools

import io.zer0.memory.pin.PinnedMemoryStore

/**
 * unpin_memory 工具。
 *
 * 允许 AI 按关键词或 id 移除已置顶的记忆。
 */
object UnpinMemoryTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "unpin_memory",
        description = "取消一条置顶记忆。用户要求忘掉此前置顶的内容," +
            "或置顶信息已不再准确时使用。",
        parameters = mapOf(
            "keyword" to "Optional. Remove the first pinned memory whose content contains this keyword.",
            "id" to "Optional. Remove by exact memory id prefix.",
        ),
        required = emptySet(),
        category = "built-in",
        // P0-3: 与 ToolPermissionResolver 显式表一致(HIGH)— 长期记忆变更,隐私敏感
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(args: Map<String, String>, store: PinnedMemoryStore, assistantId: String? = null): String {
        val keyword = args["keyword"]?.trim()
        val id = args["id"]?.trim()
        if (keyword.isNullOrEmpty() && id.isNullOrEmpty()) {
            return "Error: provide either 'keyword' or 'id' to identify the memory to unpin."
        }
        val scopedAssistantId = assistantId?.takeIf { it.isNotBlank() }
        val includeGlobal = scopedAssistantId == "default"
        val removed = if (!id.isNullOrEmpty()) {
            // 尝试精确前缀匹配
            val all = if (scopedAssistantId == null) {
                store.getAll()
            } else {
                store.getForAssistant(scopedAssistantId, includeGlobal)
            }
            val target = all.firstOrNull { it.id.startsWith(id) }
            if (target == null) {
                false
            } else if (scopedAssistantId == null) {
                store.removeById(target.id)
            } else {
                store.removeByIdForAssistant(target.id, scopedAssistantId, includeGlobal)
            }
        } else {
            // v1.131: keyword 此处非空(上面已 guard),用 requireNotNull 替代 !! 让契约更显式,
            // 误用会抛 IllegalArgumentException 而非 NPE,定位更清晰。
            requireNotNull(keyword) { "keyword must be non-null when id is empty" }
            if (scopedAssistantId == null) {
                store.removeByKeyword(keyword)
            } else {
                store.removeByContentFlexibleForAssistant(keyword, scopedAssistantId, includeGlobal)
            }
        }
        return if (removed) "Unpinned successfully." else "No matching pinned memory found."
    }
}
