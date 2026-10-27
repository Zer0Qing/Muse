package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.memory.fact.FactStore
import kotlinx.coroutines.CancellationException

/**
 * v2.x: save_memory 工具 — 助手显式写入一条长期记忆(事实)。
 *
 * 与自动提取(对话结束后的 MemoryExtract)不同:这是模型主动"记下来"的通道,
 * 用户说"记住…"时即时落库(source=user_explicit、confidence=1.0)。
 * 作用域/空间由宿主注入的 [ToolExecutionContext] 决定,模型参数不可伪造;
 * 写入仍走 FactStore.add 的 PII 脱敏与相似合并。
 */
object SaveMemoryTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "save_memory",
        description = "把一条事实存入用户长期记忆。当用户明确要求你记住某事,或陈述了重要的长期事实" +
            "(偏好、身份、目标、健康、重要日期)时使用。每次一句话;" +
            "闲聊与一次性任务细节不要存。",
        parameters = mapOf(
            "content" to "Required. The fact to remember — concise, one sentence, user's language.",
            "tags" to "Optional. Comma-separated keywords for later retrieval.",
            "importance" to "Optional. 0=normal, 1=important, 2=critical (health/safety). Default 0.",
            "category" to "Optional. preference/identity/event/relationship/goal/medical/other.",
        ),
        required = setOf("content"),
        category = "built-in",
        parameterTypes = mapOf("importance" to "integer"),
        // 与 pin_memory 同口径:长期记忆写入属隐私敏感,归 HIGH
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(args: Map<String, String>, factStore: FactStore, executionContext: ToolExecutionContext): String {
        val content = args["content"]?.trim().orEmpty()
        if (content.isEmpty()) return "Error: content parameter is required."
        val tags = args["tags"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val importance = (args["importance"]?.trim()?.toIntOrNull() ?: 0).coerceIn(0, 2)
        val category = args["category"]?.trim()?.takeIf { it.isNotEmpty() } ?: "general"
        val id =
            try {
                factStore.add(
                    FactStore.Fact(
                        fact = content,
                        tags = tags,
                        importance = importance,
                        category = category,
                        source = "user_explicit",
                        confidence = 1.0f,
                    ),
                    scope = executionContext.scope,
                    spaceId = executionContext.spaceId,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Logger.e("SaveMemoryTool", "save_memory 写入失败: ${error.message}", error)
                return "Error: failed to save memory: ${error.message ?: "storage error"}"
            }
        return if (id > 0) {
            "Saved to long-term memory (id: $id)."
        } else {
            "Error: failed to save memory."
        }
    }
}
