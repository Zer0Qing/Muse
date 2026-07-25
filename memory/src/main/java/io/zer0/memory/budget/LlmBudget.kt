package io.zer0.memory.budget

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingType
import com.knuddels.jtokkit.api.IntArrayList
import io.zer0.ai.core.Model

/**
 * LLM token 预算工具 (openhanako llm-budget.ts 移植)。
 *
 * reasoning 模型(带 thinking / reasoning 字段)需要在可见 maxTokens 之外
 * 额外预留思考 token,否则模型会把预算花在思考上,实际输出被截断。
 *
 * v1.55: truncateToTokenBudget 改用 jtokkit BPE 精确编码(替代字符数/4 启发式),
 * 初始化失败时回退到原启发式。
 */
object LlmBudget {

    /** reasoning 模型的默认缓冲 token 数。 */
    const val DEFAULT_REASONING_BUFFER_TOKENS = 1024

    @Volatile
    private var encoding: Encoding? = null

    private fun getEncoding(): Encoding? {
        if (encoding != null) return encoding
        return runCatching {
            val e = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE)
            encoding = e
            e
        }.getOrNull()
    }

    /**
     * 给可见 maxTokens 加上 reasoning 缓冲,再 clamp 到模型上限。
     */
    fun withMemoryReasoningBuffer(
        visibleMaxTokens: Int,
        model: Model?,
    ): Int {
        if (model == null) return visibleMaxTokens
        val isReasoning = model.id.contains("o1", ignoreCase = true) ||
            model.id.contains("o3", ignoreCase = true) ||
            model.id.contains("o4", ignoreCase = true) ||
            model.id.contains("-thinking", ignoreCase = true) ||
            model.id.contains("reasoning", ignoreCase = true) ||
            model.id.contains("deepseek-r", ignoreCase = true)

        if (!isReasoning) return visibleMaxTokens

        val buffered = visibleMaxTokens + DEFAULT_REASONING_BUFFER_TOKENS
        val cap = model.maxOutputTokens?.takeIf { it > 0 } ?: Int.MAX_VALUE
        return buffered.coerceAtMost(cap)
    }

    /**
     * v1.55 启发式回退系数(英文 ~4 字符/token,中文偏高但取近似值)。
     * 仅在 jtokkit 初始化失败时使用。
     */
    private const val CHARS_PER_TOKEN = 4

    /** v1.78: tokenBudget 上限,防止 Int.MAX_VALUE 导致 maxChars 溢出为负数。 */
    private const val MAX_TOKEN_BUDGET = 1_000_000

    /**
     * 把记忆 markdown 软裁剪到 [tokenBudget] token 以内。
     *
     * v1.55: 使用 jtokkit BPE 精确计数(失败回退到字符数/4)。
     * 超预算时尽量在最近一个换行处收尾,避免半行残留。
     * budget <= 0 视为不限制(返回原文)。
     */
    fun truncateToTokenBudget(text: String, tokenBudget: Int): String {
        if (tokenBudget <= 0) return text
        if (text.isEmpty()) return text
        // v1.78: 防止超大 tokenBudget 导致后续 maxChars = tokenBudget * 4 溢出为负
        val budget = tokenBudget.coerceAtMost(MAX_TOKEN_BUDGET)

        val enc = getEncoding()
        if (enc != null) {
            val count = runCatching { enc.countTokens(text) }.getOrDefault(-1)
            if (count >= 0 && count <= budget) return text
            if (count > budget) {
                // 精确截断:encode → 取前 budget 个 token → decode
                val decoded = runCatching {
                    val encoded = enc.encode(text)
                    val truncated = IntArrayList(budget)
                    for (i in 0 until budget) {
                        truncated.add(encoded.get(i))
                    }
                    enc.decode(truncated)
                }.getOrNull()
                if (decoded != null) {
                    val lastNl = decoded.lastIndexOf('\n')
                    val end = if (lastNl > decoded.length / 2) lastNl else decoded.length
                    return decoded.substring(0, end.coerceIn(0, decoded.length)).trimEnd() + "\n…(memory truncated)"
                }
            }
        }

        // 回退:字符数启发式 — v1.78 用 Long 计算防溢出
        val maxChars = budget.toLong() * CHARS_PER_TOKEN
        if (text.length <= maxChars) return text
        val cutEnd = maxChars.coerceAtMost(text.length.toLong()).toInt()
        val cut = text.substring(0, cutEnd)
        val lastNl = cut.lastIndexOf('\n')
        val end = if (lastNl > cutEnd / 2) lastNl else cutEnd
        return text.substring(0, end.coerceIn(0, text.length)).trimEnd() + "\n…(memory truncated)"
    }
}
