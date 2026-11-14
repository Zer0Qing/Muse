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
        description = "把一条经验记进经验库。适用情形:用户指出你的错误、表现出不满或反复强调某事;" +
            "你试了多种方法后找到有效做法;用户说「以后就这么做」或「别这么做」。" +
            "每条一句话,写具体。",
        parameters = mapOf(
            "category" to "Required. Category, 2-4 words, e.g. 'tool usage', 'search tips', 'response style'.",
            "content" to "Required. The experience content, concise and direct, one sentence.",
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
