package io.zer0.memory.budget

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingType
import com.knuddels.jtokkit.api.IntArrayList
import io.zer0.ai.core.Model
import io.zer0.common.Logger

/**
 * LLM token 预算工具。
 *
 * reasoning 模型（带 thinking / reasoning 字段）需要在可见 maxTokens 之外
 * 额外预留思考 token，否则模型会把预算花在思考上，实际输出被截断。
 *
 * v1.55: 截断改用 jtokkit BPE 精确编码（替代字符数/4 启发式），
 * 初始化失败时回退到原启发式。
 *
 * v1.79 (B-20): 对组装后的 memory markdown 增加**按段优先级裁剪**。
 * assembly 顺序为 facts → today → week → longterm（见
 * MemoryCompiler.assembleCompiledMarkdown），而 trunctrate 从尾部截断时
 * 最先丢弃的是 longterm（最长期也是最有价值的记忆）。现在超预算时按段分割，
 * 优先完整保留首段 facts 与末段 longterm，仅裁剪中间的 today/week，并记日志。
 */
object LlmBudget {

    /** reasoning 模型的默认思考缓冲 token 数。 */
    const val DEFAULT_REASONING_HEADROOM_TOKENS = 1024

    private const val DEFAULT_TAG = "LlmBudget"

    @Volatile
    private var encoding: Encoding? = null

    private fun loadEncoding(): Encoding? {
        if (encoding != null) return encoding
        return runCatching {
            val e = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE)
            encoding = e
            e
        }.getOrNull()
    }

    /**
     * 给可见 maxTokens 加上 reasoning 缓冲，再 clamp 到模型上限。
     */
    fun withReasoningHeadroom(
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

        val buffered = visibleMaxTokens + DEFAULT_REASONING_HEADROOM_TOKENS
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

    // ── B-20: 按段裁剪的段落模型 ─────────────────────────────────────────

    /** 组装后的 memory markdown 中的一个 `##` 段。 */
    internal data class Segment(
        val heading: String,   // 形如 "## 重要事实"
        val body: String,      // 段正文(不含标题)
    ) {
        fun render(): String = if (body.isEmpty()) heading else "$heading\n\n$body"
    }

    /**
     * 从组装后的 memory markdown 中按 `## ` 标题切出顶层段落。
     *
     * 只依赖"每段以行首 `## ` 起"的结构,不解析标题文字 —— 因此对
     * 中/英不同 locale 的标题（重要事实/Key facts…）均稳健，位置顺序
     * 保持 assembly 顺序(facts → today → week → longterm)。
     * 若非段落化文本(无 `## ` 或仅一段)返回空列表,由调用方回退到整段截断。
     */
    internal fun splitSegments(markdown: String): List<Segment> {
        val normalized = markdown.replace("\r\n", "\n")
        val lines = normalized.split('\n')
        var currentHeading: String? = null
        val bodies = mutableListOf<Segment>()
        val pending = StringBuilder()

        fun flush() {
            val heading = currentHeading ?: return
            bodies.add(Segment(heading.trim(), pending.toString().trimEnd('\n')))
            pending.setLength(0)
        }

        for (line in lines) {
            if (line.startsWith("## ")) {
                flush()
                currentHeading = line
            } else {
                pending.append(line).append('\n')
            }
        }
        flush()
        if (bodies.size < 2) return emptyList()
        return bodies
    }

    /**
     * 把段落裁剪结果序列化回 markdown(段间空一行,整体以换行收尾),格式与
     * MemoryCompiler.assembleCompiledMarkdown 保持一致。
     */
    private fun renderSegments(segments: List<Segment>): String =
        segments.joinToString("\n\n", postfix = "\n") { it.render() }

    /**
     * 统计一段文本的 token 数(失败回退到字符数/4)。纯计数,不截断。
     */
    private fun tokensOf(text: String): Int {
        val enc = loadEncoding()
        if (enc != null) {
            val count = runCatching { enc.countTokens(text) }.getOrNull()
            if (count != null && count >= 0) return count
        }
        return (text.length / CHARS_PER_TOKEN) + 1 // 防 0 导致空串误判为超预算
    }

    /**
     * B-20: 按段优先级裁剪 memory markdown。
     *
     * 优先级:首段(facts)与末段(longterm)为高优先级,完整保留;中间的
     * today/week 为低优先级,预算不足时先丢弃/收紧 today/week,且绝不静默
     * —— 每丢弃/收紧一段都记 [Logger.w]。
     *
     * 预算分配:先完整保留高优先级段;剩余预算在所有低优先级段之间均分,
     * 每段用 [truncateToTokenBudgetWhole] 从尾部收紧。若某段连标题都放不下
     * (均分预算过小),则整段丢弃并记日志。
     *
     * 返回空/null 哨兵信号会回退整段截断,因此返回类型固定 List<Segment>,
     * 通过"返回原列表"让调用方走整段截断路径。
     *
     * [budget] <= 0 视为不限制。
     */
    internal fun truncateBySegments(segments: List<Segment>, budget: Int): List<Segment> {
        if (budget <= 0) return segments
        if (segments.size < 3) return segments // 仅两组段,无"中间可牺牲",交给整段截断

        val highIdx = listOf(0, segments.size - 1) // facts, longterm
        val lowIdx = (1 until segments.size - 1).toList()

        // 高优先级段整体成本(序列化 + 段间空行)
        val highCost = tokensOf(highIdx.joinToString("\n\n") { segments[it].render() })

        // 高优先级段本身已超预算:无法按段保全,返回原列表让调用方走整段截断
        if (highCost >= budget) {
            Logger.w(
                DEFAULT_TAG,
                "LlmBudget.truncateBySegments: 高优先级段(facts+longterm)本身超预算" +
                    "(需要 $highCost token >= 预算 $budget), 回退整段截断"
            )
            return segments
        }

        // 剩余预算均分给低优先级段
        val remaining = budget - highCost
        val perSection = (remaining / (lowIdx.size + 1)).coerceAtLeast(1)

        val result = mutableListOf<Segment>()
        for (idx in 0 until segments.size) {
            if (idx in highIdx) {
                // 高优先级:原样保留
                result.add(segments[idx])
            } else {
                // 低优先级:均分预算内从尾部收紧
                val full = segments[idx].render()
                val trimmed = truncateToTokenBudgetWhole(full, perSection)
                if (trimmed != full) {
                    val inChars = full.length - trimmed.length
                    Logger.w(
                        DEFAULT_TAG,
                        "LlmBudget.truncateBySegments: 预算不足,裁剪段 '${segments[idx].heading}' " +
                            "(drop $inChars 字符 / ${full.length} 字符)"
                    )
                }
                if (trimmed.isBlank()) {
                    // 连标题都没保住,直接丢弃该段(已记日志)
                    Logger.w(
                        DEFAULT_TAG,
                        "LlmBudget.truncateBySegments: 预算不足,丢弃段 '${segments[idx].heading}'"
                    )
                    continue
                }
                result.add(parseSingleSegment(trimmed, segments[idx].heading))
            }
        }
        return result
    }

    /**
     * 把单段(可能已被裁剪)拆回 [Segment]。因 [truncateToTokenBudgetWhole] 保留
     * 头部,只要原文以 [originalHeading] 开头就能直接剥离标题;否则按通用规则
     * 拆首个 `## ` 行。无法拆成段落时整体当作正文(不与上游段落结构冲突)。
     */
    private fun parseSingleSegment(block: String, originalHeading: String): Segment {
        val trimmed = block.trim()
        if (trimmed == originalHeading) return Segment(originalHeading, "")
        if (trimmed.startsWith(originalHeading)) {
            val body = trimmed.removePrefix(originalHeading).removePrefix("\n\n").trimStart('\n')
            return Segment(originalHeading, body)
        }
        // 通用拆分行首 `## `
        val nl = trimmed.indexOf('\n')
        if (trimmed.startsWith("## ") && nl > 0) {
            val heading = trimmed.substring(0, nl).trim()
            val body = trimmed.substring(nl).trimStart('\n')
            return Segment(heading, body)
        }
        return Segment(originalHeading, trimmed)
    }

    /**
     * 把 memory markdown 软裁剪到 [tokenBudget] token 以内。
     *
     * v1.55: 使用 jtokkit BPE 精确计数(失败回退到字符数/4)。
     * 超预算时尽量在最近一个换行处收尾,避免半行残留。
     * budget <= 0 视为不限制(返回原文)。
     *
     * v1.79 (B-20): 若文本是组装后的多段 memory markdown,优先按段优先级
     * 裁剪(保留首段 facts 与末段 longterm,裁剪 today/week);否则维持
     * 原有整体从尾部截断行为。对调用方无签名/行为破坏(非段落文本行为不变)。
     */
    fun truncateToTokenBudget(text: String, tokenBudget: Int): String {
        if (tokenBudget <= 0) return text
        if (text.isEmpty()) return text

        val segments = splitSegments(text)
        if (segments.size >= 3) {
            val result = truncateBySegments(segments, tokenBudget)
            if (result != segments) {
                return renderSegments(result)
            }
        }

        return truncateToTokenBudgetWhole(text, tokenBudget)
    }

    /**
     * v1.79: 原整体截断逻辑,独立成私有方法以供"按段裁剪([truncateBySegments])"
     * 与整段回退([truncateToTokenBudget])共用。保留 v1.55 BPE 精确截断与
     * v1.78 回退启发式,行为不变。
     */
    private fun truncateToTokenBudgetWhole(text: String, tokenBudget: Int): String {
        // v1.78: 防止超大 tokenBudget 导致后续 maxChars = tokenBudget * 4 溢出为负
        val budget = tokenBudget.coerceAtMost(MAX_TOKEN_BUDGET)

        val enc = loadEncoding()
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
