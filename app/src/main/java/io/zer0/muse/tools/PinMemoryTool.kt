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
        description = "Pin an important piece of information so it is always visible in the conversation. " +
            "Use when the user explicitly asks to remember something permanently, " +
            "or when a critical fact should never be forgotten.",
        parameters = mapOf(
            "content" to "Required. The content to pin (concise, one sentence).",
        ),
        required = setOf("content"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    suspend fun execute(args: Map<String, String>, store: PinnedMemoryStore): String {
        val content = args["content"]?.trim()
            ?: return "Error: content parameter is required."
        if (content.isEmpty()) return "Error: content cannot be empty."
        val id = store.add(content)
        return if (id.isEmpty()) {
            "Error: failed to pin content."
        } else {
            "Pinned successfully (id: ${id.take(8)}...). The information will remain visible in all future conversations."
        }
    }
}
