package io.zer0.ai.core

import kotlinx.serialization.Serializable

/**
 * Provider 厂商类型。决定 [ProviderRegistry] 走哪个分支构造 Provider。
 *
 * 把协议类型显式编码到 ProviderType。
 *
 * - [OPENAI]: OpenAI 官方 + 所有 OpenAI 兼容协议(Azure / 中转 / Ollama 等),走 Chat Completions
 *   (协议 `openai-completions`)
 * - [ANTHROPIC]: Anthropic Claude + MiniMax(用 Anthropic Messages 协议),走 Messages API
 *   (协议 `anthropic-messages`)
 * - [GEMINI]: Google Gemini,走 generateContent / streamGenerateContent
 *   (协议 `google-generative-ai`)
 * - [OPENAI_RESPONSES]: OpenAI Responses API(/v1/responses),用于 xAI Grok OAuth 等
 *   (协议 `openai-responses` / `openai-codex-responses`)
 *
 * v1.0.6 新增 [OPENAI_RESPONSES],用于走 Responses API 的供应商(如 xAI Grok OAuth)。
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
