package io.zer0.muse.tools

import io.zer0.muse.data.experience.ExperienceRepository

/**
 * recall_experience 工具。
 *
 * 渐进式披露:不带参数时返回分类索引,
 * 带分类名时返回该分类下的具体经验。
 */
object RecallExperienceTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "recall_experience",
        description = "浏览经验库。不带参数时返回各分类概览;带分类名时返回该分类下的具体经验。" +
            "开始写代码、调研、分析问题这类具体任务前先查一次。闲聊与问答不需要。",
        parameters = mapOf(
            "category" to "Optional. Category name. Omit to get an overview of all categories.",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    suspend fun execute(args: Map<String, String>, repo: ExperienceRepository): String {
        val category = args["category"]?.trim()
        val all = repo.getAll()
        if (all.isEmpty()) return "Experience library is empty. No experiences recorded yet."

        return if (category.isNullOrEmpty()) {
            // 返回索引:按分类分组
            val grouped = all.groupBy { it.category }
            buildString {
                appendLine("Experience Library (${all.size} entries, ${grouped.size} categories):")
                appendLine()
                for ((cat, entries) in grouped) {
                    val snippets = entries.take(3).map { it.content.take(30) }
                    appendLine("# $cat (${entries.size} entries)")
                    appendLine(snippets.joinToString("; "))
                    appendLine()
                }
            }
                .trimEnd()
        } else {
            val matches = all.filter {
                it.category.equals(category, ignoreCase = true) ||
                    it.category.contains(category, ignoreCase = true)
            }
            if (matches.isEmpty()) {
                "No experiences found for category '$category'."
            } else {
                buildString {
                    appendLine("# ${matches.first().category} (${matches.size} entries)")
                    appendLine()
                    for ((i, e) in matches.withIndex()) {
                        appendLine("${i + 1}. ${e.content}")
                    }
                }.trimEnd()
            }
        }
    }
}
