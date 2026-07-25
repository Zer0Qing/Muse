package io.zer0.ai.core

import kotlinx.serialization.Serializable

/**
 * Provider 厂商类型。决定 [ProviderRegistry] 走哪个分支构造 Provider。
 *
 * 对齐 openhanako 的 `defaultApi` 字段枚举,把协议类型显式编码到 ProviderType。
 *
 * - [OPENAI]: OpenAI 官方 + 所有 OpenAI 兼容协议(Azure / 中转 / Ollama 等),走 Chat Completions
 *   (对应 openhanako `openai-completions`)
 * - [ANTHROPIC]: Anthropic Claude + MiniMax(用 Anthropic Messages 协议),走 Messages API
 *   (对应 openhanako `anthropic-messages`)
 * - [GEMINI]: Google Gemini,走 generateContent / streamGenerateContent
 *   (对应 openhanako `google-generative-ai`)
 * - [OPENAI_RESPONSES]: OpenAI Responses API(/v1/responses),用于 xAI Grok OAuth 等
 *   (对应 openhanako `openai-responses` / `openai-codex-responses`)
 *
 * v1.0.6 新增 [OPENAI_RESPONSES],对齐 openhanako 的 xai-oauth 供应商(走 Responses API)。
 */
@Serializable
enum class ProviderType {
    OPENAI,
    ANTHROPIC,
    GEMINI,
    OPENAI_RESPONSES,
    ;

    companion object {
        /** 默认类型(向后兼容旧配置无 type 字段时)。 */
        val DEFAULT = OPENAI
    }
}
