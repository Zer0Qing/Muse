package io.zer0.muse.tools

object PinMemoryTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "pin_memory",
        description = "把重要信息置顶,使其始终出现在对话中。" +
            "当用户明确要求永久记住某事,或某个关键事实绝不能被遗忘时使用。",
        parameters = mapOf(
            "content" to "Required. The content to pin (concise, one sentence).",
        ),
        required = setOf("content"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )
}
