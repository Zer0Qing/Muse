package io.zer0.muse.tools

import io.zer0.memory.pin.PinnedMemoryStore

/**
 * pin_memory 工具(既有实现 pinned-memory-store.ts 实现)。
 *
 * 允许 AI 置顶重要信息,使其保留在系统提示词中。
 * 通过 AgentToolsRegistrar 动态注册到 ToolRegistry。
 */
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
        // P0-3: 与 ToolPermissionResolver 显式表一致(HIGH)— 长期记忆写入,隐私敏感
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(args: Map<String, String>, store: PinnedMemoryStore, assistantId: String? = null): String {
        val content = args["content"]?.trim()
            ?: return "Error: content parameter is required."
        if (content.isEmpty()) return "Error: content cannot be empty."
        val id = store.add(content, assistantId = assistantId)
        return if (id.isEmpty()) {
            "Error: failed to pin content."
        } else {
            "Pinned successfully (id: ${id.take(8)}...). The information will remain visible in future conversations with this assistant."
        }
    }
}
