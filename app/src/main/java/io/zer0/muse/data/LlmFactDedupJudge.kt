package io.zer0.muse.data

import io.zer0.common.Logger
import io.zer0.memory.fact.DedupVerdict
import io.zer0.memory.fact.FactDedupJudge
import io.zer0.memory.llm.MemoryLlmClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * v12: LLM 事实去重判定器 — 算法层(字符相似度/实体键)无法确定的模糊候选对,
 * 交给大模型判断是否为同一事实。
 *
 * 设计:
 *  - 只在 FactStore 的"同实体键有候选但文本判定失败"低频场景被调用,每次 1 次 LLM 调用;
 *  - LLM 输出严格 JSON,解析失败/超时/异常一律降级为"不同一"(宁可不合并,不阻塞写入);
 *  - 判定缓存由 FactStore 侧维护(同事实对只问一次)。
 */
class LlmFactDedupJudge(
    private val llmClient: MemoryLlmClient,
) : FactDedupJudge {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun judge(
        a: String,
        b: String,
        entityKeyA: String?,
        entityKeyB: String?,
    ): DedupVerdict {
        return try {
            val systemPrompt = buildSystemPrompt()
            val userContent = buildUserContent(a, b, entityKeyA, entityKeyB)
            val raw = llmClient.callText(
                systemPrompt = systemPrompt,
                userContent = userContent,
                temperature = 0.0f,
                maxTokens = 200,
                timeoutMs = 15_000L,
            )
            parse(raw)
        } catch (e: Exception) {
            // 超时/网络/解析失败: 保守降级,不阻塞记忆写入主流程
            Logger.w(TAG, "LLM 去重判定失败,降级为不同一: ${e.message}")
            DedupVerdict.NOT_SAME
        }
    }

    private fun buildSystemPrompt(): String = """
        你是记忆系统的去重判定器。给定两条记忆事实,判断它们是否描述同一个事实(同一件事)。
        - 表述措辞不同但语义相同(如"用户养了一只柯基"与"他养了只柯基犬")→ same=true
        - 同一实体下的不同事实(如"喜欢咖啡"与"讨厌香菜")→ same=false
        - 其中 [REDACTED] 代表已被脱敏的敏感信息(人名/电话/地址等),视为同一实体的不同写法
        - 不确定时 same=false(宁可不合并,不可误合并)
        只输出严格 JSON,不要 markdown 围栏,不要额外文字:
        {"same": true/false, "confidence": 0.0~1.0, "reason": "简短理由"}
    """.trimIndent()

    private fun buildUserContent(a: String, b: String, entityKeyA: String?, entityKeyB: String?): String = buildString {
        append("事实 A: ").append(a).append('\n')
        append("事实 B: ").append(b).append('\n')
        if (!entityKeyA.isNullOrBlank() || !entityKeyB.isNullOrBlank()) {
            append("实体键 A: ").append(entityKeyA ?: "null").append('\n')
            append("实体键 B: ").append(entityKeyB ?: "null").append('\n')
            append("(实体键相同通常表示同一实体,但事实内容仍可能不同,请以语义为准)")
        }
    }

    internal fun parse(raw: String): DedupVerdict {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = runCatching { json.decodeFromString<JudgeResponse>(trimmed) }.getOrNull()
            ?: runCatching { json.decodeFromString<JudgeResponse>(extractJsonObject(trimmed)) }.getOrNull()
            ?: return DedupVerdict.NOT_SAME
        return DedupVerdict(
            same = obj.same,
            confidence = obj.confidence.coerceIn(0f, 1f),
            reason = obj.reason.orEmpty().take(80),
        )
    }

    private fun extractJsonObject(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else ""
    }

    @Serializable
    private data class JudgeResponse(
        val same: Boolean = false,
        val confidence: Float = 0f,
        val reason: String? = null,
    )

    private companion object {
        const val TAG = "LlmFactDedupJudge"
    }
}
