package io.zer0.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Provider 特定配置(sealed class)。
 *
 * Phase 8.1 引入: 把 Provider 独有的配置字段封装到 sealed class 子类,
 * 让编译器保证类型安全(when 分支穷尽匹配)。
 *
 * 序列化策略: 用 kotlinx.serialization 的 sealed class 多态序列化,
 * 默认带 "type" 区分字段(OpenAI/Anthropic/Gemini)。
 * 旧 [ProviderConfig] JSON 数据无此字段时,[ProviderConfig.fallbackSpecific]
 * 会按 [ProviderType] 创建默认配置,确保向后兼容。
 *
 * 独立编写。
 */
@Serializable
sealed class ProviderSpecificConfig {

    /**
     * OpenAI 兼容 Provider 特定配置。
     *
     * @param chatCompletionsPath 聊天补全端点路径(默认 /chat/completions);
     *   Azure 等中转可能需改 /openai/deployments/{id}/chat/completions
     * @param useResponseApi 是否走新版 Responses API(/v1/responses);
     *   false=走 Chat Completions(默认,兼容性最好)
     * @param responsesPath Responses API 端点路径(默认 /responses);
     *   v1.0.7 新增。Codex OAuth 等需改 /codex/responses
     * @param includeHistoryReasoning 历史消息是否包含推理内容;
     *   true=把上轮 reasoning 作为 assistant 消息 content 一部分回传(默认 true)
     * @param embeddingsPath Embeddings 端点路径(默认 /embeddings)
     * @param imagesPath 图片生成端点路径(默认 /images/generations)
     */
    @Serializable
    @SerialName("OpenAI")
    data class OpenAI(
        val chatCompletionsPath: String = "/chat/completions",
        val useResponseApi: Boolean = false,
        /**
         * v1.0.7: Responses API 端点路径。
         *
         * - 默认 "/responses":OpenAI 官方 / xAI Grok OAuth / 其他 OpenAI 兼容
         * - "/codex/responses":OpenAI Codex OAuth(走 chatgpt.com/backend-api)
         * - 其他自定义路径:由用户配置
         *
         * 仅当 [useResponseApi]=true 时生效。
         */
        val responsesPath: String = "/responses",
        val includeHistoryReasoning: Boolean = true,
        val embeddingsPath: String = "/embeddings",
        val imagesPath: String = "/images/generations",
        /**
         * v1.136: 默认绘图模型 ID。
         *
         * 当调用方未显式传入 model 时,ImageService 会优先使用本字段,
         * 其次回退到 ImageModelCatalog 的默认模型(如 dall-e-3)。
         * 这对自定义/中转 OpenAI 兼容供应商友好,避免请求体缺少 model 导致 404。
         */
        val imageModel: String = "",
        /**
         * v1.136: 视频生成端点路径(默认 /videos/generations)。
         *
         * 通用 OpenAI 兼容视频生成 Provider 使用;Kling 等专用 Provider 不走此字段。
         */
        val videoGenerationsPath: String = "/videos/generations",
        /**
         * 模型 ID 前缀剥离。
         * 部分中转/聚合平台在本地模型列表中使用 provider 前缀(如 opencode-go/glm-5.2),
         * 但实际 API 请求只接受 glm-5.2。配置此项后 OpenAIProvider 会在发送请求前
         * 从 model.id 中移除该前缀。留空则不剥离。
         */
        val stripModelPrefix: String = "",
        /**
         * v1.0.6: 是否为 Coding Plan 供应商(对齐 openhanako 的 *-coding 系列预设)。
         *
         * Coding Plan 供应商特点:
         *  - baseUrl 走专属 coding 端点(如 https://api.kimi.com/coding/v1)
         *  - apiKey 通常带专属前缀(如 sk-sp- / sk-coding-)
         *  - 模型列表聚焦编程(如 qwen3-coder-plus / kimi-for-coding)
         *  - 部分需要运行时补全 headers/reasoning(openhanako 的 RUNTIME_ENRICHED_PROVIDERS)
         *
         * 当前仅作为标记字段,具体行为差异由 ProviderCompat / ProviderPayloadNormalizer 消费。
         */
        val codingPlan: Boolean = false,
    ) : ProviderSpecificConfig()

    /**
     * Anthropic Claude Provider 特定配置。
     *
     * Phase 8.1 (M9): 加入 [promptCaching] + [promptCacheTtl] 字段,
     * 启用 Claude 的 Prompt Caching 以节省重复请求成本。
     *
     * @param promptCaching 是否启用 Prompt Caching(默认 false,向后兼容)
     * @param promptCacheTtl 缓存 TTL: "5m"(5 分钟,默认)或 "1h"(1 小时,需企业版)。
     *   v1.80 (L-CORE18): Anthropic 官方仅接受 "5m" / "1h" 两个值,
     *   非法值在 init 块中回退到 "5m" 并记录警告(避免发请求被服务端 400)。
     * @param messagesPath Messages API 端点路径(默认 /messages)
     * @param modelsPath 模型列表端点路径(默认 /models)
     */
    @Serializable
    @SerialName("Anthropic")
    data class Anthropic(
        val promptCaching: Boolean = false,
        val promptCacheTtl: String = "5m",  // "5m" / "1h"
        val messagesPath: String = "/messages",
        val modelsPath: String = "/models",
    ) : ProviderSpecificConfig() {
        init {
            // L-CORE18: 校验 promptCacheTtl 仅接受官方文档值,非法值回退到 "5m"
            if (promptCacheTtl != "5m" && promptCacheTtl != "1h") {
                io.zer0.common.Logger.w(
                    "ProviderSpecificConfig",
                    "Anthropic.promptCacheTtl 非法值 '$promptCacheTtl',应为 '5m' 或 '1h'。" +
                        "反序列化时无法改值(immutable),调用方应自行修正。",
                )
            }
        }
    }

    /**
     * Google Gemini Provider 特定配置。
     *
     * Phase 8.10 (M10) 预留: [useServiceAccount] 等字段用于 Vertex AI,
     * 当前 Phase 8.1 暂未实现 Vertex AI,字段先占位。
     *
     * @param useVertexAI 是否走 Vertex AI 端点(默认 false,走 generativelanguage API)
     * @param useServiceAccount 是否用服务账号认证(默认 false,用 API Key)
     * @param serviceAccountEmail 服务账号邮箱(Vertex AI 用)
     * @param privateKey 服务账号私钥(Vertex AI 用,PEM 格式)
     * @param location Vertex AI 区域(如 us-central1)
     * @param projectId GCP 项目 id(Vertex AI 用)
     * @param streamPath 流式端点路径(默认 :streamGenerateContent)
     * @param generatePath 非流式端点路径(默认 :generateContent)
     */
    @Serializable
    @SerialName("Gemini")
    data class Gemini(
        val useVertexAI: Boolean = false,
        val useServiceAccount: Boolean = false,
        val serviceAccountEmail: String = "",
        val privateKey: String = "",
        val location: String = "us-central1",
        val projectId: String = "",
        val streamPath: String = ":streamGenerateContent",
        val generatePath: String = ":generateContent",
    ) : ProviderSpecificConfig() {
        /**
         * v1.80 (H-CORE2): 自定义 toString 防止 privateKey 明文泄露。
         * 服务账号私钥泄露 = GCP 项目完全沦陷,比 API Key 危害更大。
         */
        override fun toString(): String {
            val maskedKey = if (privateKey.isNotEmpty()) "***(${privateKey.length} chars)" else ""
            return "Gemini(useVertexAI=$useVertexAI, useServiceAccount=$useServiceAccount, " +
                "serviceAccountEmail=$serviceAccountEmail, privateKey=$maskedKey, " +
                "location=$location, projectId=$projectId)"
        }
    }

    /**
     * 自定义 Provider 特定配置(用于"custom"供应商选项 / 中转站)。
     *
     * 约束: "Provider addition must include a 'custom' option to use the original
     * blank form"。中转站(preset 中的 relay 类别)及用户自定义供应商使用此配置,
     * 允许用户覆盖 chatCompletionsPath、附加自定义请求头与请求体字段。
     *
     * 注:ai 模块的 [io.zer0.ai.openai.OpenAIProvider] 通过 `resolvedSpecific()
     * as? OpenAI ?: OpenAI()` 兜底,Custom 的字段当前不直接驱动 Provider 行为
     * (由 app 层在调用前按需映射);保留此类型是为了让 app 层 UI(ProviderEditDialog)
     * 与 PresetProviders 能持久化这些自定义参数。
     *
     * v1.134 P2-2: 新增 [requestTemplate] / [responsePath] / [streamResponsePath] 字段,
     * 由 [io.zer0.ai.plugin.ProviderPluginRegistry.toProviderConfig] 从插件 JSON 透传过来。
     * 当前 ai 模块的 Provider 实现尚未消费这些字段,后续可扩展 OpenAIProvider 支持
     * 自定义请求模板与响应路径。
     *
     * @param chatCompletionsPath 聊天补全端点路径(默认 /chat/completions)
     * @param customHeaders 附加 HTTP 请求头(key→value)
     * @param customBody 附加请求体字段(key→JsonElement,合并到请求 JSON 顶层)
     * @param requestTemplate 完整请求体 JSON 模板(占位符 {{prompt}} {{model}} 等);
     *   留空时走默认 OpenAI Chat Completions 模板
     * @param responsePath 非流式响应 JSON 提取路径(如 $.choices[0].message.content);
     *   留空时走默认路径
     * @param streamResponsePath 流式响应 JSON 提取路径(如 $.choices[0].delta.content);
     *   留空时走默认路径
     */
    @Serializable
    @SerialName("Custom")
    data class Custom(
        val chatCompletionsPath: String = "/chat/completions",
        val customHeaders: Map<String, String> = emptyMap(),
        val customBody: Map<String, JsonElement> = emptyMap(),
        val requestTemplate: String = "",
        val responsePath: String = "",
        val streamResponsePath: String = "",
    ) : ProviderSpecificConfig()

    companion object {
        /** 按 [ProviderType] 取默认特定配置。 */
        fun defaultFor(type: ProviderType): ProviderSpecificConfig = when (type) {
            ProviderType.OPENAI -> OpenAI()
            ProviderType.ANTHROPIC -> Anthropic()
            ProviderType.GEMINI -> Gemini()
            ProviderType.OPENAI_RESPONSES -> OpenAI(useResponseApi = true)
        }
    }
}
