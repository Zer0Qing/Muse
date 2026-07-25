package io.zer0.ai.core

import kotlinx.serialization.Serializable

/**
 * 推理等级,对应 OpenAI o1 系列 / Anthropic thinking / Gemini thinking 等可调推理强度。
 *
 * Phase 8.1 扩展: 从 5 级(AUTO/NONE/LOW/MEDIUM/HIGH)扩展到 6 级 + budgetTokens。
 * 独立编写(OFF/AUTO/LOW/MEDIUM/HIGH/XHIGH + budgetTokens)。
 *
 * - [OFF]: 关闭推理(对应 Anthropic thinking=disabled / OpenAI reasoning.effort=minimal)
 * - [AUTO]: 自动(让服务端决定,不加 budgetTokens)
 * - [LOW]: 轻量推理(~1000 tokens)
 * - [MEDIUM]: 中等推理(~2000 tokens)
 * - [HIGH]: 深度推理(~8000 tokens)
 * - [XHIGH]: 极深推理(~16000 tokens,仅顶级模型支持)
 *
 * @property budgetTokens 对应的思考预算 token 数;
 *   null 表示不指定(AUTO / OFF),由服务端决定
 * @property effort OpenAI o1 系列的 effort 字段(low/medium/high);
 *   null 表示不传该字段
 * @property anthropicLevel Anthropic thinking 的 level(disabled/low/medium/high);
 *   null 表示不传该字段
 */
@Serializable
enum class ReasoningLevel(
    val budgetTokens: Int?,
    val effort: String?,
    val anthropicLevel: String?,
) {
    OFF(budgetTokens = 0, effort = "minimal", anthropicLevel = "disabled"),
    AUTO(budgetTokens = null, effort = null, anthropicLevel = null),
    LOW(budgetTokens = 1000, effort = "low", anthropicLevel = "low"),
    MEDIUM(budgetTokens = 2000, effort = "medium", anthropicLevel = "medium"),
    HIGH(budgetTokens = 8000, effort = "high", anthropicLevel = "high"),
    /**
     * v1.80 (M-CORE8): XHIGH 补全 effort/anthropicLevel。
     * 原先为 null 导致 OpenAI/Anthropic 不发送推理参数,用户选"极深推理"实际未生效。
     * OpenAI 无 "xhigh" 值,降级为 "high";Anthropic 同理用 "high"。
     */
    XHIGH(budgetTokens = 16000, effort = "high", anthropicLevel = "high");

    companion object {
        /** 默认推理等级(向后兼容旧配置无字段时)。 */
        val DEFAULT = AUTO
    }
}
