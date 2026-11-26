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
    fun withReasoningHeadroom(visibleMaxTokens: Int, model: Model?): Int {
        if (model == null) return visibleMaxTokens
        // v2.x: 优先用模型能力字段 — 仅按 id 子串判断会漏掉新型推理模型
        // (如 deepseek-flash / v4-pro: 目录 abilities=[TOOL, REASONING] 但 id 不带下列关键词),
        // 导致思考余量未加、正文被 reasoning 挤空(finishReason=length 的最小预算截断)。
        val isReasoning = io.zer0.ai.core.ModelAbility.REASONING in model.abilities ||
            model.id.contains("o1", ignoreCase = true) ||
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

    /** 截断标记:提示模型这段记忆不完整。它自身也占 token,按条记账时必须计入预算。 */
    private const val TRUNCATION_MARKER = "\n…(memory truncated)"

    // ── B-20: 按段裁剪的段落模型 ─────────────────────────────────────────

    /** 组装后的 memory markdown 中的一个 `##` 段。 */
    internal data class Segment(
        val heading: String, // 形如 "## 重要事实"
        val body: String, // 段正文(不含标题)
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
            // B-20: 用 trim('\n') 而非 trimEnd — 标题后的空行分隔符会以"前导 \n"
            // 进入正文,导致 body 带多余换行、render 时出现三连换行。
            bodies.add(Segment(heading.trim(), pending.toString().trim('\n')))
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
    private fun renderSegments(segments: List<Segment>): String = segments.joinToString("\n\n", postfix = "\n") { it.render() }

    /**
     * 统计一段文本的 token 数(失败回退到字符数/4)。纯计数,不截断。
     *
     * 保留原有 1/4 回退与 +1 语义,不动按段裁剪([truncateBySegments])的既有行为;
     * 需要与调用方逐条记账对齐的计数用 [countTokens]。
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
     * 统计一段文本的 token 数(CL100K BPE;编码表不可用时回退到字符类别估算)。
     *
     * 调用方按条扣预算时必须用它,而不是用字符数/4:BPE 与真实分词一致,回退分支
     * 也区分中日韩字符与拉丁字符 —— 同样 400 个字符,英文约 100 token,中文接近
     * 400 token,按 4 折算会让中文记忆多占约 3 倍预算,预算形同虚设。
     */
    fun countTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val enc = loadEncoding()
        if (enc != null) {
            val count = runCatching { enc.countTokens(text) }.getOrNull()
            if (count != null && count >= 0) return count
        }
        return estimateByCharacterClass(text)
    }

    /**
     * 编码表不可用时的估计:中日韩字符、谚文、全角形式与 emoji 按 1 token/个,
     * 其余按 4 字符/token。对中文按 1 token/字符取值,接近 BPE 的实际结果,
     * 不会像字符数/4 那样把中文低估三倍。
     */
    private fun estimateByCharacterClass(text: String): Int {
        var tokens = 0
        var latinChars = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val width = Character.charCount(codePoint)
            if (isWideCharacter(codePoint)) {
                tokens += (latinChars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
                latinChars = 0
                tokens += 1
            } else {
                latinChars += width
            }
            index += width
        }
        return tokens + (latinChars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }

    /** 宽字符判定:CJK 部首~彝文、谚文、CJK 兼容表意、全角形式、emoji 与扩展区表意。 */
    private fun isWideCharacter(codePoint: Int): Boolean =
        codePoint in 0x1100..0x115F ||
            codePoint in 0x2E80..0xA4CF ||
            codePoint in 0xAC00..0xD7A3 ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0xFE30..0xFE6F ||
            codePoint in 0xFF00..0xFF60 ||
            codePoint in 0xFFE0..0xFFE6 ||
            codePoint in 0x1F300..0x1FAFF ||
            codePoint in 0x20000..0x3FFFD

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
                    "(需要 $highCost token >= 预算 $budget), 回退整段截断",
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
                            "(drop $inChars 字符 / ${full.length} 字符)",
                    )
                }
                if (trimmed.isBlank()) {
                    // 连标题都没保住,直接丢弃该段(已记日志)
                    Logger.w(
                        DEFAULT_TAG,
                        "LlmBudget.truncateBySegments: 预算不足,丢弃段 '${segments[idx].heading}'",
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
     * 严格裁剪:返回值(含截断标记)本身不超过 [tokenBudget] token。
     *
     * 与 [truncateToTokenBudget] 的差别只在标记的记账方式:后者先按预算截正文、
     * 再追加标记,标记自成 token 却不计入预算;调用方若接着按返回值扣预算,每裁一条
     * 就多占几个 token,条目一多必然超支。逐条分配同一份预算时必须走本方法。
     *
     * 预算连"正文 + 标记"都放不下时返回空串 —— 返回半截正文会破坏预算承诺,
     * 由调用方把该条视为未保留。[tokenBudget] <= 0 视为不限制,返回原文。
     */
    fun truncateToTokenBudgetStrict(text: String, tokenBudget: Int): String {
        if (tokenBudget <= 0) return text
        if (text.isEmpty()) return text
        if (countTokens(text) <= tokenBudget) return text

        // 正文与标记拼接后 BPE 会在边界重新合并,先留 2 个 token 余量,再按实测超出量回缩。
        var bodyBudget = tokenBudget - countTokens(TRUNCATION_MARKER) - 2
        var candidate: String? = null
        var attempts = 0
        while (bodyBudget > 0 && attempts < 2 && candidate == null) {
            attempts++
            val body = cutToTokenBudget(text, bodyBudget)
            if (body.isNullOrEmpty()) break
            val built = trimAtLineBreak(body) + TRUNCATION_MARKER
            val used = countTokens(built)
            if (used <= tokenBudget) {
                candidate = built
            } else {
                bodyBudget -= (used - tokenBudget).coerceAtLeast(1)
            }
        }
        // 连标记都放不下(或两轮回缩仍超)时不给半截正文:预算承诺优先。
        return candidate.orEmpty()
    }

    /**
     * 把文本硬裁到 [tokenBudget] 个 token:优先 BPE 编码后解码前 N 个 token,
     * 编码失败或编码表不可用时按字符类别估算折半查找。
     */
    private fun cutToTokenBudget(text: String, tokenBudget: Int): String? {
        val enc = loadEncoding()
        if (enc != null) {
            val decoded = runCatching {
                val encoded = enc.encode(text)
                val take = minOf(tokenBudget, encoded.size())
                val truncated = IntArrayList(take)
                for (i in 0 until take) {
                    truncated.add(encoded.get(i))
                }
                enc.decode(truncated)
            }.getOrNull()
            if (decoded != null) return decoded
        }
        return cutByCharacterEstimate(text, tokenBudget)
    }

    /** BPE 不可用时的折半查找:找满足预算的最长字符前缀。 */
    private fun cutByCharacterEstimate(text: String, tokenBudget: Int): String {
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (estimateByCharacterClass(text.substring(0, mid)) <= tokenBudget) low = mid else high = mid - 1
        }
        // 折半可能停在代理对中间,退一格避免返回半个码点
        if (low > 0 && Character.isHighSurrogate(text[low - 1])) low -= 1
        return text.substring(0, low)
    }

    /** 在最近的换行处收尾,避免留下半行;只在丢弃量不超过一半时才收尾。 */
    private fun trimAtLineBreak(text: String): String {
        val lastNewline = text.lastIndexOf('\n')
        return if (lastNewline > text.length / 2) text.substring(0, lastNewline) else text
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
                    return decoded.substring(0, end.coerceIn(0, decoded.length)).trimEnd() + TRUNCATION_MARKER
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
        return text.substring(0, end.coerceIn(0, text.length)).trimEnd() + TRUNCATION_MARKER
    }
}
