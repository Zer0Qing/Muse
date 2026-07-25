package io.zer0.muse.tools

import io.zer0.muse.data.experience.ExperienceEntity
import io.zer0.muse.data.experience.ExperienceRepository
import kotlinx.coroutines.runBlocking
import java.util.UUID


/**
 * record_experience 工具(openhanako experience.ts 移植)。
 *
 * 将学到的经验记录到经验库中。
 */
object RecordExperienceTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "record_experience",
        description = "Record a lesson learned to the experience library. Use when: " +
            "the user points out a mistake, the user shows frustration or repeatedly emphasizes something, " +
            "you discover an effective method after trying multiple approaches, " +
            "or the user explicitly says 'from now on do/don't do this'. " +
            "Each entry should be concise and direct, one sentence.",
        parameters = mapOf(
            "category" to "Required. Category for the experience, 2-4 words, e.g. 'tool usage', 'search tips', 'response style'.",
            "content" to "Required. The specific experience content, concise and direct, one sentence.",
        ),
        required = setOf("category", "content"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    fun execute(args: Map<String, String>, repo: ExperienceRepository): String {
        val category = args["category"]?.trim()?.replace(Regex("^#+\\s*"), "")
            ?: return "Error: category parameter is required."
        val content = args["content"]?.trim()
            ?: return "Error: content parameter is required."
        if (category.isEmpty() || content.isEmpty()) {
            return "Error: category and content cannot be empty."
        }
        return runBlocking {
            // 去重检查
            val existing = repo.getAll()
            if (existing.any { it.content == content && it.category.equals(category, ignoreCase = true) }) {
                return@runBlocking "Duplicate: this experience is already recorded."
            }
            val title = content.take(50) + if (content.length > 50) "..." else ""
            val entity = ExperienceEntity(
                id = UUID.randomUUID().toString(),
                title = title,
                content = content,
                category = category,
                source = "llm",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            repo.upsert(entity)
            "Experience recorded under '$category': $content"
        }
    }
}
