package io.zer0.muse.tools

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
        riskLevel = ToolRiskLevel.HIGH,
    )
}
