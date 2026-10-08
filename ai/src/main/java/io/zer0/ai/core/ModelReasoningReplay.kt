package io.zer0.ai.core

/**
 * v2.5.3 (P2-1): 推理回放策略的「模型级声明」。
 *
 * 背景：2026 年中起，Kimi K3 / GLM 4.7+ / DeepSeek v4 等一批「保留思考」模型陆续上线，
 * 它们要求历史 assistant 消息必须原样回传 reasoning，否则静默降质，甚至直接 400。
 *
 * 现状问题：回放契约此前只挂在 [ProviderCompat]（按 baseUrl host 猜），
 * 同一个模型在不同 host 下（官方直连 vs 聚合站/中转）会得出不同结论，
 * 且新增模型时容易漏配。这里把「回放策略」提升为一等公民的**模型级声明**：
 * 先按模型 id 声明，ProviderCompat 的 host 值作为兜底覆盖。
 *
 * 与 [ReasoningCarrier] / [ReasoningReplayPolicy] 的关系：
 *  - 本对象描述「该模型需要怎么回放」的声明；
 *  - ProviderCompat.reasoningReplayContract 描述「当前这条链路实际能怎么回放」；
 *  - 解析顺序：ProviderCompat 显式声明 > 模型级声明 > 默认（不回放）。
 */

/**
 * v2.5.3: 模型级回放声明。
 *
 * @param carrier 回放载体（wire 字段位置），见 [ReasoningCarrier]
 * @param policy 回放策略，见 [ReasoningReplayPolicy]
 * @param requiresNonEmptyReasoning 是否要求「即使没有思考内容也必须发字段」。
 *   部分协议（Kimi/DeepSeek 思考模式）在带 tools 的请求里，历史 assistant 消息缺该字段会直接 400，
 *   因此需要发空串占位；其余协议缺字段即省略。
 */
data class ModelReasoningDeclaration(
    val carrier: ReasoningCarrier,
    val policy: ReasoningReplayPolicy,
    val requiresNonEmptyReasoning: Boolean = false,
)

/**
 * v2.5.3 (P2-1): 按模型 id 推断推理回放声明。
 *
 * 规则（对齐参考实现的 ModelDefaultsGuesser 取向）：新系「保留思考」模型默认全量回放，
 * 仅工具轮回放的按协议区分，老模型/未知模型不声明（走 ProviderCompat 或默认不回放）。
 *
 * 重要：本解析器**只声明策略，不注入任何厂商扩展字段**，因此对未知中转站是安全的
 * （不像 thinkingFormat 注入会触发 400）。
 */
object ModelReasoningReplayResolver {
    /** 模型 id 归一化：去聚合站/厂商前缀，转小写。 */
    private fun normalize(modelId: String): String {
        val raw = modelId.trim().lowercase()
        val prefixes =
            listOf(
                "openrouter/", "opencode-go/", "siliconflow/", "dashscope/",
                "deepseek-ai/", "zhipu/", "qwen/", "openai/", "anthropic/",
                "moonshotai/", "google/", "vertex/",
            )
        val stripped = prefixes.firstOrNull { raw.startsWith(it) }?.let(raw::removePrefix) ?: raw
        // 剥离聚合前缀后可能仍带厂商段(如 "deepseek/deepseek-v4")，统一取最后一段。
        return stripped.substringAfterLast('/').takeIf { it.isNotBlank() } ?: stripped
    }

    /**
     * 推断模型级回放声明；无声明时返回 null（由 ProviderCompat 或默认行为接管）。
     */
    fun declare(modelId: String?): ModelReasoningDeclaration? {
        val normalized = modelId?.let(::normalize)
        return if (normalized.isNullOrBlank()) null else declarationFor(normalized)
    }

    private fun declarationFor(id: String): ModelReasoningDeclaration? =
        when (matchFamily(id)) {
            ReasoningFamily.OPENAI_RESPONSES -> preserveDeclaration(ReasoningCarrier.REASONING_ITEMS)
            ReasoningFamily.ANTHROPIC -> toolTurnDeclaration(ReasoningCarrier.THINKING_BLOCKS)
            ReasoningFamily.GEMINI -> toolTurnDeclaration(ReasoningCarrier.THOUGHT_SIGNATURE)
            ReasoningFamily.OPENROUTER -> preserveDeclaration(ReasoningCarrier.REASONING_DETAILS)
            ReasoningFamily.REASONING_CONTENT_STRICT -> reasoningContentDeclaration(requireNonEmpty = true)
            ReasoningFamily.REASONING_CONTENT_LENIENT -> reasoningContentDeclaration(requireNonEmpty = false)
            ReasoningFamily.NONE -> null
        }

    /** 回放家族：把“模型名 → 声明”从一堆谓词收敛成单次匹配。 */
    private enum class ReasoningFamily {
        OPENAI_RESPONSES,
        ANTHROPIC,
        GEMINI,
        OPENROUTER,
        REASONING_CONTENT_STRICT,
        REASONING_CONTENT_LENIENT,
        NONE,
    }

    private fun matchFamily(id: String): ReasoningFamily {
        carrierOnlyFamily(id)?.let { return it }
        return contentFamily(id)
    }

    /** 有专属载体的厂商（Responses/Anthropic/Gemini/OpenRouter）。 */
    private fun carrierOnlyFamily(id: String): ReasoningFamily? =
        when {
            id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") || id.startsWith("gpt-5") ->
                ReasoningFamily.OPENAI_RESPONSES
            id.startsWith("claude") -> ReasoningFamily.ANTHROPIC
            id.startsWith("gemini") -> ReasoningFamily.GEMINI
            id.startsWith("openrouter") || id.contains("reasoning") -> ReasoningFamily.OPENROUTER
            else -> null
        }

    /** reasoning_content 载体的思考系模型，按字段是否可省分两组。 */
    private fun contentFamily(id: String): ReasoningFamily =
        when {
            STRICT_CONTENT_PREFIXES.any { id.startsWith(it) } || (id.contains("deepseek") && id.contains("reason")) ->
                ReasoningFamily.REASONING_CONTENT_STRICT
            LENIENT_CONTENT_PREFIXES.any { id.startsWith(it) } -> ReasoningFamily.REASONING_CONTENT_LENIENT
            id.contains("glm-4.7") || (id.startsWith("glm-4") && id.contains("thinking")) ->
                ReasoningFamily.REASONING_CONTENT_STRICT
            id.startsWith("doubao") && id.contains("thinking") -> ReasoningFamily.REASONING_CONTENT_LENIENT
            else -> ReasoningFamily.NONE
        }

    private fun preserveDeclaration(carrier: ReasoningCarrier) =
        ModelReasoningDeclaration(carrier = carrier, policy = ReasoningReplayPolicy.PRESERVE)

    private fun toolTurnDeclaration(carrier: ReasoningCarrier) =
        ModelReasoningDeclaration(carrier = carrier, policy = ReasoningReplayPolicy.REQUIRE_TOOL_CALL)

    private fun reasoningContentDeclaration(requireNonEmpty: Boolean) =
        ModelReasoningDeclaration(
            carrier = ReasoningCarrier.REASONING_CONTENT,
            policy = ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
            requiresNonEmptyReasoning = requireNonEmpty,
        )
}

/** 字段不能省的思考系前缀（缺字段上游直接 400）。 */
private val STRICT_CONTENT_PREFIXES =
    listOf("deepseek-v4", "deepseek-v3", "deepseek-r1", "deepseek-reasoner", "kimi-k", "kimi-thinking", "glm-z1", "glm-5")

/** 字段可省的思考系前缀（内容为空时省略字段，节省 token）。 */
private val LENIENT_CONTENT_PREFIXES =
    listOf("qwen3", "qwq-", "qwen-vl", "mimo", "longcat")

