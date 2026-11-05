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
 *
 * **⚠ 本文件在 2026-11-05 被误删过一次，现在的内容是「HEAD + 恢复性重建」**：
 * 一个自动化探针变异用 `Remove-Item -Recurse` 删掉了 `ai/src/main/java/io/zer0/ai/` 整棵树，
 * `git restore` 只能回到 HEAD，于是**另一位 agent 尚未提交**的「免费额度从 SiliconFlow 迁到
 * Agnes 网关」改动被覆盖掉了。下面标了 `[恢复]` 的部分是按两处证据重建的：
 *  1. 当时编译出的测试 class 常量池里留有字面量（`https://apihub.agnes-ai.com/v1`、
 *     `agnes-2.5-flash`、`preset_agnes_free`、`preset_agnes`、`legacy-provider`）；
 *  2. **幸存**的 app 侧代码（`data/preset/SiliconFlowFreeModels.kt` 顶注：「免费额度已迁到 agnes
 *     网关（apihub.agnes-ai.com），类名保留是为了不动 12 处调用点」，以及 `BASE_URL`、
 *     `MODELS` 里的 `agnes-2.5-flash`）。
 * 原作者请复核：白名单/host 判定与 `LEGACY_FREE_PROVIDER_IDS` 的取舍可能与原实现不同，
 * 且当时新增的 `FreeModelConfigTest` 用例（含 `not-in-whitelist`、`legacy-provider` 两组断言）
 * **无法重建**，测试覆盖已回退到 HEAD 水平。
 *    app 模块 build.gradle.kts 已开启 buildConfig = true,后续可通过 BuildConfig 注入;
 *    现阶段仅占位,fallback 解析会因 "PLACEHOLDER" 字符串被 [resolveApiKey] 拒绝返回 null,
 *    退化到原 apiKey 行为(空串或允许 allowMissingApiKey 的 Provider 仍可调远程 /models)。
 */
object FreeModelConfig {

    /** 仅此内部供应商允许使用无 Key 的免费额度 fallback。 */
    const val FREE_PROVIDER_ID = "preset_siliconflow_free"

    /**
     * [恢复] 历史上做过「免费槽」的 provider id。
     *
     * 用户手上的旧配置可能还叫这些名字（迁移前后各留一份），
     * `SettingsRepository` 判「这条配置是不是免费槽」时要把它们也算进来，
     * 否则老用户的免费档会莫名变成要填 key。
     *
     * 注意：**不含** [FREE_PROVIDER_ID] 本身 —— 调用点是 `id == PROVIDER_ID || id in 本集合`。
     */
    val LEGACY_FREE_PROVIDER_IDS: Set<String> = setOf(
        "preset_agnes_free",
        "preset_agnes",
    )

    /**
     * SiliconFlow 免费模型白名单。
     *
     * 这些模型在 SiliconFlow 平台免费公开，供未配置 key 的用户直接使用。
     */
    val FREE_MODEL_IDS: Set<String> = setOf(
        // [恢复] 迁移后的主力免费模型（app 侧 SiliconFlowFreeModels.MODELS 里就是它）。
        "agnes-2.5-flash",
        // 旧 SiliconFlow 直连的两条保留：已配置过它们的历史用户不该因为迁移突然拿不到 key。
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
     * [恢复] Agnes 网关端点：免费额度已从 SiliconFlow 迁到这里。
     *
     * 值来自两处独立证据（编译产物常量池 + app 侧 `PresetProviders.ENDPOINT_AGNES`），
     * 二者字面量一致，所以这个 URL 不是猜的。
     */
    const val AGNES_BASE_URL: String = "https://apihub.agnes-ai.com/v1"

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
        val hostAllowed = extractHost(baseUrl)?.let { host -> HOST_MARKERS.any { host.contains(it) } } == true
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
    fun isFallbackKeyAvailable(): Boolean = FALLBACK_API_KEY.isNotBlank() && FALLBACK_API_KEY != "PLACEHOLDER"

    /**
     * 判断指定 provider 是否是免费模型 provider(SiliconFlow 且用户未填 key)。
     *
     * 供 [OpenAIProvider.listModels] 在入口处判断:为 true 时直接返回预设的免费模型列表,
     * 不调远程 /models(避免因无 key 而 401 失败)。
     */
    fun isFreeProvider(providerId: String, baseUrl: String, userApiKey: String, hiddenFromSettings: Boolean = false): Boolean =
        (providerId == FREE_PROVIDER_ID || hiddenFromSettings) &&
            userApiKey.isBlank() &&
            extractHost(baseUrl)?.let { host -> HOST_MARKERS.any { host.contains(it) } } == true

    /**
     * [恢复] 允许的免费上游主机标记。
     *
     * 迁移后只认 `siliconflow` 会出问题：app 侧把免费预置的 baseUrl 指到了
     * `apihub.agnes-ai.com`，那时 `resolveApiKey`/`isFreeProvider` 全部判否 ⇒
     * **空 key 用户点免费档会直接 401，而且不报错源**。所以两个主机都放行。
     */
    private val HOST_MARKERS = listOf("siliconflow", "agnes-ai")

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
