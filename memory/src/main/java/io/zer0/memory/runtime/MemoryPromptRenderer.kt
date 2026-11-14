package io.zer0.memory.runtime

/**
 * 把 [MemoryReadResult] 渲染成注入文本。
 *
 * 与数据读取解耦，保证列表、搜索、prompt 看到的是同一份结构化结果；
 * 这里只做确定性文本拼接，不调用模型。
 */
object MemoryPromptRenderer {

    data class RenderedPrompt(
        val text: String,
        val sourceKeys: List<String>,
        val usedTokens: Int,
        val truncated: Boolean,
    )

    fun render(result: MemoryReadResult, locale: String = "zh-CN"): String {
        return renderDetailed(result, locale).text
    }

    fun renderDetailed(result: MemoryReadResult, locale: String = "zh-CN"): RenderedPrompt {
        val isZh = locale.startsWith("zh")
        val parts = mutableListOf<String>()

        result.pinned.takeIf { it.isNotEmpty() }?.let { pinned ->
            parts += if (isZh) "## 置顶记忆（用户要求始终记住的内容）" else "## Pinned Memories"
            pinned.forEach { parts += "- ${it.content}" }
        }

        result.facts.takeIf { it.isNotEmpty() }?.let { facts ->
            parts += if (isZh) "## 重要事实" else "## Key facts"
            facts.forEach { parts += "- ${it.fact}" }
        }

        result.compiled?.sections.orEmpty().entries
            .filter { it.value.isNotBlank() }
            .forEach { (section, content) ->
                if (section == "facts") return@forEach
                parts += when (section) {
                    "today" -> if (isZh) "## 今天" else "## Today"
                    "week" -> if (isZh) "## 本周早些时候" else "## Earlier this week"
                    "longterm" -> if (isZh) "## 长期情况" else "## Long-term context"
                    else -> section
                }
                parts += content
            }

        return RenderedPrompt(
            text = parts.joinToString("\n\n").trim(),
            sourceKeys = result.sources.map { it.key },
            usedTokens = result.usedTokens,
            truncated = result.truncated,
        )
    }
}
