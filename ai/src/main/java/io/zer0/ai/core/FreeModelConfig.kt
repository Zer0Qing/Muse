package io.zer0.ai.core

/**
 * v1.0.18: 免费模型配置。
 *
 * 用户未填 API Key 时,使用内置 SiliconFlow 免费额度访问指定模型。
 *
 * 策略：
 *  - host 含 siliconflow 且 modelId 在白名单且用户 key 为空 → 用 fallback key
 *  - 用户一旦填了自己的 key 就走用户 key,fallback 不再生效
 *
 * 工程说明：
 *  - 本对象放在 ai 模块而非 app/data/preset,因为 [OpenAIProvider] 在 ai 模块
 *    需要直接调用 [resolveApiKey];ai 模块不依赖 app 模块,反向依赖会破坏分层。
 *  - [FALLBACK_API_KEY] 用 "PLACEHOLDER" 占位,实际发布时由 CI/CD 注入真实 key。
 *    app 模块 build.gradle.kts 已开启 buildConfig = true,后续可通过 BuildConfig 注入;
 *    现阶段仅占位,fallback 解析会因 "PLACEHOLDER" 字符串被 [resolveApiKey] 拒绝返回 null,
 *    退化到原 apiKey 行为(空串或允许 allowMissingApiKey 的 Provider 仍可调远程 /models)。
 */
object FreeModelConfig {

    /** 仅此内部供应商允许使用无 Key 的免费额度 fallback。 */
    const val FREE_PROVIDER_ID = "preset_siliconflow_free"

    /**
     * SiliconFlow 免费模型白名单。
     *
     * 这些模型在 SiliconFlow 平台免费公开，供未配置 key 的用户直接使用。
     */
    val FREE_MODEL_IDS: Set<String> = setOf(
        "THUDM/GLM-4-9B-0414",
        "Qwen/Qwen3-8B",
    )

    /**
     * 内置 fallback API key。
     *
     * 注入路径(ai/build.gradle.kts 已配置 buildConfigField)：
     *  1. 命令行 -PFREE_MODEL_KEY=xxx
     *  2. local.properties 的 FREE_MODEL_KEY 字段(已被 .gitignore 忽略)
     *  3. 环境变量 FREE_MODEL_KEY
     *  4. 均未设置时回退 "PLACEHOLDER"(fallback 机制不生效)
     */
    val FALLBACK_API_KEY: String = getFallbackKey()

    /**
     * SiliconFlow base url(与 [io.zer0.muse.data.preset.SiliconFlowFreeModels.BASE_URL] 一致)。
     */
    const val SILICONFLOW_BASE_URL: String = "https://api.siliconflow.cn/v1"

    /**
     * UI 引导提示文案:当用户选择 SiliconFlow 供应商且未填 key 时展示。
     */
    const val FREE_PROVIDER_HINT: String =
        "免登录可用免费模型(GLM-4-9B / Qwen3-8B),填写 API Key 解锁更多模型"

    /**
     * 检查指定的 provider + model 是否符合免费模型条件。
     *
     * @param providerId 仅 [FREE_PROVIDER_ID] 或已迁移的隐藏旧供应商允许使用内置免费额度
     * @param baseUrl 供应商 base url
     * @param modelId 模型 id
     * @param userApiKey 用户配置的 API key(空串表示未配置)
     * @param hiddenFromSettings 旧版本复制出的免费预置也可通过迁移标记保留能力
     * @return 如果符合条件返回 fallback key,否则返回 null
     */
    fun resolveApiKey(
        providerId: String,
        baseUrl: String,
        modelId: String,
        userApiKey: String,
        hiddenFromSettings: Boolean = false,
    ): String? {
        val providerAllowed = providerId == FREE_PROVIDER_ID || hiddenFromSettings
        val hostAllowed = extractHost(baseUrl)?.contains(HOST_MARKER) == true
        val modelAllowed = FREE_MODEL_IDS.any { it.equals(modelId, ignoreCase = true) }
        // 用户已配置自己的 key 时完全跳过 fallback,走用户 key。
        return FALLBACK_API_KEY.takeIf {
            providerAllowed &&
                userApiKey.isBlank() &&
                hostAllowed &&
                modelAllowed &&
                isFallbackKeyAvailable()
        }
    }

    /**
     * R-SEC-07: fallback key 是否可用(非空且非占位符)。
     */
    fun isFallbackKeyAvailable(): Boolean =
        FALLBACK_API_KEY.isNotBlank() && FALLBACK_API_KEY != "PLACEHOLDER"

    /**
     * 判断指定 provider 是否是免费模型 provider(SiliconFlow 且用户未填 key)。
     *
     * 供 [OpenAIProvider.listModels] 在入口处判断:为 true 时直接返回预设的免费模型列表,
     * 不调远程 /models(避免因无 key 而 401 失败)。
     */
    fun isFreeProvider(
        providerId: String,
        baseUrl: String,
        userApiKey: String,
        hiddenFromSettings: Boolean = false,
    ): Boolean =
        (providerId == FREE_PROVIDER_ID || hiddenFromSettings) &&
            userApiKey.isBlank() &&
            extractHost(baseUrl)?.contains(HOST_MARKER) == true

    private const val HOST_MARKER = "siliconflow"

    private fun extractHost(baseUrl: String): String? = try {
        java.net.URL(baseUrl).host?.lowercase()
    } catch (_: Exception) {
        null
    }

    /**
     * 从 BuildConfig 读取 fallback key,未注入时回退 "PLACEHOLDER"。
     *
     * ai 模块 build.gradle.kts 已开启 buildConfig 并通过 buildConfigField
     * 注入 FREE_MODEL_KEY(优先级: -P 参数 > local.properties > 环境变量 > PLACEHOLDER)。
     */
    private fun getFallbackKey(): String {
        return try {
            io.zer0.ai.BuildConfig.FREE_MODEL_KEY
        } catch (_: Throwable) {
            "PLACEHOLDER"
        }
    }
}
