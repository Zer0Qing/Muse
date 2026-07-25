package io.zer0.ai.core

import kotlinx.serialization.Serializable

/**
 * 供应商分类(用于 UI 分组展示)。
 * - [OFFICIAL]: 厂商官方直连
 * - [RELAY]: 中转站(OpenAI 兼容协议,聚合多家厂商)
 * - [CUSTOM]: 用户自定义
 */
@Serializable
enum class ProviderCategory {
    OFFICIAL,
    RELAY,
    CUSTOM,
}

/**
 * Provider 的连接配置。
 *
 * Phase 3 起支持多 Provider 共存:每个配置带 [type] 字段标识厂商,
 * [ProviderRegistry] 根据 [type] 走对应分支。
 *
 * Phase 8.1: 加 [specific] 字段(ProviderSpecificConfig sealed class),
 * 把 Provider 独有配置(Claude 的 promptCaching / OpenAI 的 useResponseApi /
 * Gemini 的 Vertex AI 等)封装到子类,让编译器保证类型安全。
 * 旧 JSON 数据无 specific 字段时,序列化框架用 [fallbackSpecific] 兜底。
 *
 * [baseUrl] 留空时按 [type] 取默认值:
 *  - OPENAI → [DEFAULT_OPENAI_BASE_URL]
 *  - ANTHROPIC → [DEFAULT_ANTHROPIC_BASE_URL]
 *  - GEMINI → [DEFAULT_GEMINI_BASE_URL]
 *
 * P1-6: [oauthConfig] 非空时,ProviderEditPage 会显示「OAuth 登录」按钮,
 * 通过 Device Flow 或 Authorization Code Flow 自动获取 [apiKey](access_token)。
 *
 * @param enabled 是否启用(默认 true),禁用的 Provider 不出现在选择列表
 * @param builtIn 是否内置(默认 false),内置 Provider 不可删除
 * @param balanceApiPath 余额查询端点路径(可选,如 /dashboard/billing/usage)
 * @param balanceResultPath 余额结果 JSON 路径(如 $.data.total_usage)
 * @param category 供应商分类(默认 OFFICIAL,向后兼容旧数据)
 * @param oauthConfig P1-6 OAuth 登录配置(可选);非空时支持 OAuth 自动获取 apiKey
 * @param allowMissingApiKey v1.0.6: 是否允许空 apiKey(对齐 openhanako authType=none/optional)。
 *   true 时 Provider 不强制要求 apiKey(如 Ollama 本地部署、system-speech 等)。
 *   默认 false(向后兼容,绝大多数供应商需要 apiKey)。
 * @param specId v1.0.7: 内置供应商规格标识(对齐 openhanako BUILTIN_PLUGINS 的 providerId)。
 *   非空时标识该配置源自某个内置供应商规格(如 "openai"/"deepseek"/"anthropic"),
 *   运行时 [io.zer0.ai.core.ProviderSpecMerger] 会把 spec 默认模型列表与用户 overlay 合并:
 *    - spec 默认模型 + 用户自定义模型(同 id 去重,用户字段优先)
 *    - 当内置供应商更新默认模型列表后,已添加的用户自动看到新模型
 *   为 null 时表示纯自定义供应商,不参与合并(向后兼容旧数据)。
 *   数据迁移时给已有 "preset_" 前缀 id 的配置自动推断 specId。
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val displayName: String,
    val type: ProviderType = ProviderType.DEFAULT,
    val baseUrl: String = "",
    val apiKey: String = "",
    val models: List<Model> = emptyList(),
    val specific: ProviderSpecificConfig? = null,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val balanceApiPath: String = "",
    val balanceResultPath: String = "",
    val category: ProviderCategory = ProviderCategory.OFFICIAL,
    val oauthConfig: OAuthConfig? = null,
    val allowMissingApiKey: Boolean = false,
    val specId: String? = null,
) {
    /**
     * v1.80 (H-CORE1): 自定义 toString 防止 apiKey 明文泄露到日志/调试输出。
     * data class 自动生成的 toString 会暴露 apiKey,此处掩码处理。
     *
     * v1.80 (M-CORE1): 短 key(<=12 位)泄露比例过高(原 take(4)+takeLast(4)
     * 对 8~12 位 key 几乎不掩码),改为统一掩码为 "****",只露末 2 位用于辨识。
     */
    override fun toString(): String {
        val maskedKey = when {
            apiKey.length <= 12 && apiKey.isNotEmpty() -> "****" + apiKey.takeLast(2)
            apiKey.length > 12 -> apiKey.take(4) + "***" + apiKey.takeLast(4)
            else -> ""
        }
        return "ProviderConfig(id=$id, displayName=$displayName, type=$type, baseUrl=$baseUrl, " +
            "apiKey=$maskedKey, models=${models.size}个, specific=$specific, enabled=$enabled, " +
            "builtIn=$builtIn, category=$category, oauthConfig=${
                if (oauthConfig != null) "OAuthConfig(clientId=${oauthConfig.clientId})" else "null"
            })"
    }

    /**
     * 按 type 解析实际 baseUrl(空则取默认)。
     *
     * v1.80 (L-CORE13): 对非 https 的 baseUrl 记录 Logger.w 警告,
     * 提醒用户明文 HTTP 传输 apiKey 风险(默认值均已是 https,仅自定义 baseUrl 可能违规)。
     */
    fun resolvedBaseUrl(): String {
        val resolved = baseUrl.trimEnd('/').ifBlank {
            when (type) {
                ProviderType.OPENAI -> DEFAULT_OPENAI_BASE_URL
                ProviderType.ANTHROPIC -> DEFAULT_ANTHROPIC_BASE_URL
                ProviderType.GEMINI -> DEFAULT_GEMINI_BASE_URL
                ProviderType.OPENAI_RESPONSES -> DEFAULT_OPENAI_RESPONSES_BASE_URL
            }
        }
        if (resolved.isNotEmpty() && !resolved.startsWith("https://", ignoreCase = true)) {
            io.zer0.common.Logger.w(
                "ProviderConfig",
                "baseUrl 非 https(id=$id, type=$type),apiKey 可能明文传输: $resolved",
            )
        }
        return resolved
    }

    /**
     * 取特定配置,specific 为 null 时按 [type] 兜底(向后兼容旧数据)。
     * 这样调用方永远拿到非 null 的 [ProviderSpecificConfig],
     * 可以安全 `as ProviderSpecificConfig.Anthropic` 转换。
     */
    fun resolvedSpecific(): ProviderSpecificConfig =
        specific ?: ProviderSpecificConfig.defaultFor(type)

    /**
     * 派生 Provider 能力矩阵(compat)。
     *
     * 调用 [ProviderCompatRules.resolve] 按 type + baseUrl + (可选) modelId 三层匹配:
     *  1. type 决定协议级基础规则(OPENAI 全 true / ANTHROPIC / GEMINI)
     *  2. baseUrl host 细化厂商 OpenAI 兼容层差异
     *  3. modelId 细化具体模型支持情况
     *
     * @param modelId 当前请求的模型 id(可选)。同一 Provider 不同模型可能有不同 compat,
     *   传入时按 model 进一步细化(如 deepseek-reasoner 关闭 tool calling);
     *   不传时仅按 type + baseUrl 派生。
     */
    fun resolvedCompat(modelId: String? = null): ProviderCompat =
        ProviderCompatRules.resolve(type, baseUrl, modelId)

    companion object {
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1"
        const val DEFAULT_GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_OPENAI_RESPONSES_BASE_URL = "https://api.openai.com/v1"
    }
}
