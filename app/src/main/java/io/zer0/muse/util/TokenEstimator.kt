package io.zer0.muse.util

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingRegistry
import com.knuddels.jtokkit.api.EncodingType
import io.zer0.ai.core.UIMessage
import io.zer0.common.resultOf

/**
 * v1.55: 真实 token 估算器(基于 jtokkit BPE 编码)。
 *
 * 使用 cl100k_base 编码(GPT-4/GPT-3.5 通用),对其他模型(DeepSeek/Claude/Gemini)
 * 为近似值,但远优于字符数启发式估算。
 *
 * EncodingRegistry 全局单例,懒加载(首次调用时初始化,约 50-100ms)。
 * 初始化失败时回退到启发式估算(中文 1.5 字/token,英文 4 字符/token)。
 */
object TokenEstimator {

    @Volatile
    private var registry: EncodingRegistry? = null

    @Volatile
    private var encoding: Encoding? = null

    /** 视觉图片 token 估算(每张):Gemini ~258、OpenAI high-detail ~1105;取 1000 偏保守。 */
    private const val IMAGE_TOKEN_ESTIMATE_PER_IMAGE = 1000

    private fun getEncoding(): Encoding? {
        if (encoding != null) return encoding
        // v1.80 (L-TE1): 改用 resultOf,正确重抛 CancellationException(runCatching 会吞掉)
        return resultOf {
            val r = Encodings.newDefaultEncodingRegistry()
            registry = r
            val e = r.getEncoding(EncodingType.CL100K_BASE)
            encoding = e
            e
        }.getOrNull()
    }

    /**
     * 精确计算单段文本的 token 数(BPE 编码)。
     * jtokkit 初始化失败时回退到启发式估算。
     */
    fun estimate(text: String): Int {
        if (text.isBlank()) return 0
        val enc = getEncoding()
        if (enc != null) {
            // v1.80 (L-TE1): 改用 resultOf + getOrNull 兜底(自定义 Result 无 getOrDefault)
            return resultOf { enc.countTokens(text) }.getOrNull() ?: fallbackEstimate(text)
        }
        return fallbackEstimate(text)
    }

    /**
     * 估算一批工具定义的总 token 数。
     *
     * 每次请求都要把完整 schema 发出去，工具越多这块越大。几十个工具就能吃掉上万 token，
     * 而它和对话历史抢的是同一个窗口 —— 不扣它，压缩永远触发得太晚。
     *
     * 结果按内容指纹缓存：同一批工具在一次会话里会被估算很多次，
     * 每次都跑 BPE 编码是纯浪费。
     */
    fun estimateToolDefinitions(definitions: List<io.zer0.ai.core.ToolDefinition>): Int {
        if (definitions.isEmpty()) return 0
        val fingerprint = definitions.fold(17) { acc, def ->
            acc * 31 + def.name.hashCode() + def.description.hashCode() + def.parametersJsonSchema.hashCode()
        }
        val hit = cached?.takeIf { it.first == fingerprint }?.second
        return hit ?: computeToolDefinitionTokens(definitions).also { cached = fingerprint to it }
    }

    /** 按 API 实际发送的形状拼：名字 + 描述 + 参数 schema，再补每个定义的固定外框开销。 */
    private fun computeToolDefinitionTokens(definitions: List<io.zer0.ai.core.ToolDefinition>): Int {
        val text = buildString {
            definitions.forEach { def ->
                append(def.name).append('\n')
                append(def.description).append('\n')
                append(def.parametersJsonSchema).append('\n')
            }
        }
        return estimate(text) + definitions.size * TOOL_DEFINITION_OVERHEAD_TOKENS
    }

    /** 单个工具定义的外层包装开销（name/description/parameters 之外的固定字段）。 */
    private const val TOOL_DEFINITION_OVERHEAD_TOKENS = 8

    @Volatile
    private var cached: Pair<Int, Int>? = null

    /**
     * 估算消息列表 + system prompt 的总 token 数。
     *
     * @param messages 对话历史(含 user/assistant/system/tool)
     * @param systemPrompt 系统提示文本(SystemPromptAssembler 组装后),默认空
     * @return token 数(BPE 精确或启发式回退)
     */
    fun estimate(messages: List<UIMessage>, systemPrompt: String = ""): Int {
        var total = estimate(systemPrompt)
        messages.forEach { msg ->
            total += estimate(msg.content)
            // 计入 assistant 的思考过程 / MOOD 腹稿 / 反思块(都会占用上下文)
            msg.reasoning?.let { total += estimate(it) }
            msg.mood?.let { total += estimate(it) }
            msg.reflection?.let { total += estimate(it) }
            // toolCalls 的 arguments 也算入(JSON 字符串)
            msg.toolCalls?.forEach { tc ->
                total += estimate(tc.arguments)
            }
            // 视觉图片(base64 / url)同样占用上下文;此前完全未计,导致视觉对话
            // 预算系统性偏低 → AUTO_COMPRESS/占用圈失真,含图超长上下文可能撞 400。
            val imageCount = msg.imageBase64List.size + msg.imageUrls.size
            if (imageCount > 0) {
                total += imageCount * IMAGE_TOKEN_ESTIMATE_PER_IMAGE
            }
        }
        // M6: 每条消息约 4 tokens 开销(OpenAI 协议每条消息的固定开销:role 标记 + 分隔符)
        val perMessageOverhead = 4
        total += messages.size * perMessageOverhead
        return total
    }

    /**
     * 启发式回退(中文 1.5 字/token,英文 4 字符/token)。
     * 仅在 jtokkit 初始化失败时使用。
     */
    private fun fallbackEstimate(text: String): Int {
        var chineseCount = 0
        var otherCount = 0
        text.forEach { ch ->
            val code = ch.code
            if (code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0x3000..0x303F) {
                chineseCount++
            } else if (!ch.isWhitespace()) {
                otherCount++
            }
        }
        val cnTokens = (chineseCount / 1.5).toInt()
        val enTokens = otherCount / 4
        return cnTokens + enTokens
    }
}
