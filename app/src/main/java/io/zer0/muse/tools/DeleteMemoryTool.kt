package io.zer0.muse.tools

object DeleteMemoryTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "delete_memory",
        description = "从用户长期记忆中删除一条事实。当用户说某条信息有误、已过时,或要求你忘掉时使用。" +
            "可按精确 id(search_memory 结果里给的)或关键词匹配,删除当前记忆空间中" +
            "最匹配的那一条。要更正事实时,先删除旧的,再用 save_memory 存改正后的版本。",
        parameters = mapOf(
            "id" to "Optional. Exact fact id from search_memory results.",
            "match" to "Optional. Keywords to locate the fact when the id is unknown.",
        ),
        required = emptySet(),
        category = "built-in",
        parameterTypes = mapOf("id" to "integer"),
        riskLevel = ToolRiskLevel.HIGH,
    )
}
