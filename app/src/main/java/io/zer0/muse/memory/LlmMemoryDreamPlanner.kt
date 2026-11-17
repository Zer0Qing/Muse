package io.zer0.muse.memory

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.memory.dream.MemoryDreamPlan
import io.zer0.memory.dream.MemoryDreamPlanner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 用现有模型链路生成 Dream 计划。
 *
 * 这里刻意复用 [ChatService] 而不是自建 Provider：模型选择、key 轮换、限流、
 * 代理配置都已经在那一层收敛过，再建一套只会让「用哪个模型整理记忆」变成第二个真相。
 *
 * 输出按固定结构解析；解析不出来就返回空计划，交给 service 判 Empty 并保持现状 ——
 * 绝不把自由文本当计划用，否则一次跑偏就会污染记忆。
 */
class LlmMemoryDreamPlanner(
    private val chatService: ChatService,
) : MemoryDreamPlanner {

    override suspend fun plan(current: Map<String, String>): MemoryDreamPlan {
        if (current.isEmpty()) return MemoryDreamPlan(emptyMap())
        val completion = chatService.completeText(
            messages = listOf(
                UIMessage(
                    role = MessageRole.SYSTEM,
                    content = SYSTEM_PROMPT,
                    createdAt = System.currentTimeMillis(),
                ),
                UIMessage(
                    role = MessageRole.USER,
                    content = buildPrompt(current),
                    createdAt = System.currentTimeMillis(),
                ),
            ),
            temperature = 0.2f,
            // 四段加起来可能上千字符，给足预算；推理型模型还要额外空间，
            // 给小了会出现「推理吃光预算、正文为空」。
            maxTokens = 2048,
            reasoningLevel = ReasoningLevel.OFF,
            mode = ChatRequestMode.UTILITY,
        )
        val text = completion.text.trim()
        if (text.isEmpty()) {
            Logger.w(TAG, "Dream 计划为空响应，保持现有记忆不变")
            return MemoryDreamPlan(emptyMap())
        }
        return MemoryDreamPlan(parseSections(text))
    }

    private fun buildPrompt(current: Map<String, String>): String = buildString {
        appendLine("当前记忆分段：")
        current.entries.sortedBy { it.key }.forEach { (key, value) ->
            appendLine("## $key")
            appendLine(value.ifBlank { "（空）" })
            appendLine()
        }
        appendLine("请输出整理后的分段，只包含需要修改的段。")
    }

    /**
     * 解析模型返回的分段。
     *
     * 只接受 `## key` + 正文的固定形状：形状不对就返回空表，让 service 判 Empty 并保持现状。
     * 不做「尽量猜」的宽松解析 —— 猜错一段比不整理更糟。
     */
    internal fun parseSections(text: String): Map<String, String> {
        val sections = LinkedHashMap<String, String>()
        var key: String? = null
        val buffer = StringBuilder()
        fun flush() {
            val name = key ?: return
            val body = buffer.toString().trim()
            if (body.isNotEmpty()) sections[name] = body
            buffer.clear()
        }
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("## ")) {
                flush()
                key = trimmed.removePrefix("## ").trim().lowercase()
            } else if (key != null) {
                buffer.appendLine(line)
            }
        }
        flush()
        if (sections.isEmpty()) {
            // 模型偶尔会直接回一段 JSON；能解出来就用，解不出仍返回空表。
            parseJsonSections(text)?.let { return it }
        }
        return sections
    }

    /** 兼容模型直接输出 `{"sections":{...}}` 的情形。 */
    private fun parseJsonSections(text: String): Map<String, String>? = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        val sections = (root["sections"] as? JsonObject) ?: root
        sections.entries
            .mapNotNull { (key, value) ->
                val body = (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                if (body == null) null else key to body
            }
            .toMap()
    }.getOrNull()

    private companion object {
        const val TAG = "LlmMemoryDreamPlanner"

        /**
         * 提示词把「只改需要改的段」写死：
         * Dream 的价值是整理既有内容，不是让模型自由重写用户的历史。
         */
        val SYSTEM_PROMPT = """
            你在整理用户的长期记忆分段。规则：
            1. 只输出需要修改的分段，格式为 `## 段名` 后紧跟正文；
            2. 段名只能是 facts、today、week、longterm 之一；
            3. 不要编造未发生的事，不要删除仍然有效的信息；
            4. 每段控制在 800 字以内；
            5. 没有需要改的就输出空内容。
        """.trimIndent()
    }
}
