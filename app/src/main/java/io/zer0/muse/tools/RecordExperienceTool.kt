package io.zer0.muse.tools

import io.zer0.memory.experience.ExperienceRepository

/**
 * record_experience 工具。
 *
 * 将学到的经验记录到经验库中。
 */
object RecordExperienceTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "record_experience",
        description = "把一条可复用的工具使用或排错经验记进经验库（仅记录参数坑点、有效调用路径、多步排查后找到的解法）。" +
            "用户个人事实请用 save_memory，用户长期行为/回答风格偏好（如「以后别…」「始终用…」）请用 remember_preference，勿写入经验库。" +
            "每条一句话,写具体。",
        parameters = mapOf(
            "category" to "Required. Category, 2-4 words, e.g. 'tool usage', 'debugging', 'search tips'.",
            "content" to "Required. The reusable tool/debugging lesson, concise and direct, one sentence.",
        ),
        required = setOf("category", "content"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    suspend fun execute(args: Map<String, String>, repo: ExperienceRepository, executionContext: ToolExecutionContext): String {
        if (!canWrite(executionContext)) {
            return "Error: untrusted context cannot record experience."
        }
        val category = args["category"]?.trim()?.replace(Regex("^#+\\s*"), "")
            ?: return "Error: category parameter is required."
        val content = args["content"]?.trim()
            ?: return "Error: content parameter is required."
        if (category.isEmpty() || content.isEmpty()) {
            return "Error: category and content cannot be empty."
        }
        val id = repo.record(
            assistantId = executionContext.assistantId?.takeIf { it.isNotBlank() } ?: "default",
            category = category,
            content = content,
        )
        return "Experience recorded under '$category' (id: ${id.take(8)}...)."
    }

    internal fun canWrite(executionContext: ToolExecutionContext): Boolean = !executionContext.isSubagentContext
}
