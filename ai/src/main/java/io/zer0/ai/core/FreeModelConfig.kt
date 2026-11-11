package io.zer0.ai.core

import java.io.File

/**
 * 免费模型配置（对外门面）。
 *
 * 用户未填 API Key 时，免费档使用**服务端不定期轮换**的接口地址、密钥与模型。
 * 客户端不再把某一家供应商写死为唯一真源：每次取用都经 [FreeModelConfigSource]
 * 按「服务端派发 → 本地缓存 → 内置默认」三层解析。
 *
 * 策略：
 *  - 只有 [FREE_PROVIDER_ID]（或历史上迁移过来的免费槽）允许使用内置免费额度；
 *  - 用户一旦填了自己的 key，就完全走用户 key，免费派发不再生效；
 *  - 派发失败静默回退，不阻塞对话（但回退原因会记日志，不含密钥）。
 *
 * 工程说明：
 *  - 本对象放在 ai 模块，因为 [OpenAIProvider] 需要直接解析 key；ai 不依赖 app。
 *  - 内置默认值里的密钥是**服务端下发的轮换凭据**，属于免费档可用性数据，
 *    不是用户资产；仍不得写入日志、诊断导出或崩溃报告。
 */
object FreeModelConfig {

    /** 仅此内部供应商允许使用无 Key 的免费额度 fallback。 */
    const val FREE_PROVIDER_ID = "preset_siliconflow_free"

    /**
     * 历史上做过「免费槽」的 provider id。
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
        // 更早的 SiliconFlow 直连免费槽：用户配置里可能仍存在这个 id。
        "preset_siliconflow_free_legacy",
    )

    /**
     * 内置默认配置 —— 完全离线、派发端点不可达时的最后兜底。
     *
     * 这里的值是**本轮轮换到的有效凭据**；下一轮由服务端派发覆盖，不需要发版。
     */
    /**
     * 内置默认配置 —— **只保留端点与模型形态，不含任何密钥**。
     *
     * 为什么不再内置密钥：免费档的密钥轮换很频繁（可能几天就换一次），写死在安装包里
     * 很快就会失效，而且 APK 可被反编译取出。现在密钥**只经服务端派发**下发，
     * 客户端从服务端或本地缓存取得；两者都没有时免费档不可用，界面提示用户填写自己的 Key。
     *
     * [apiKey] 留空使 [FreeModelRemoteConfig.isUsable] 为 false ——
     * 这正是「没有派发配置时不要假装可用」的诚实表达，避免拿一个空 key 去打上游换回 401。
     */
    val BUILT_IN_DEFAULT: FreeModelRemoteConfig = FreeModelRemoteConfig(
        version = 0,
        baseUrl = "https://apihub.agnes-ai.com/v1",
        model = "agnes-2.5-flash",
        apiKey = "",
        displayName = "",
        contextWindow = 512_000,
        maxOutputTokens = 65_536,
        supportsVision = true,
        supportsTools = true,
        supportsReasoning = true,
        rotationNote = "no built-in key: server dispatch only",
    )

    /**
     * 服务端派发端点。
     *
     * 可用构建注入覆盖（`FREE_MODEL_DISPATCH_URL`），便于灰度与自建部署；
     * 未注入时使用官方默认端点。空串 = 关闭派发，只用缓存与内置默认。
     */
    val DISPATCH_URL: String = readDispatchUrl()

    /** 本地缓存文件（由 app 侧注入 filesDir 路径；null = 不落盘）。 */
    @Volatile
    private var cacheFile: File? = null

    /** 配置源；[installCacheDirectory] 之前用无缓存实例。 */
    @Volatile
    private var source: FreeModelConfigSource = newSource(null)

    /**
     * 注入缓存目录（app 启动时调用一次）。
     *
     * 目的：派发结果跨进程重启仍然有效，避免每次冷启动都先走一遍内置默认值。
     */
    fun installCacheDirectory(dir: File?) {
        cacheFile = dir?.let { File(it, "free_model_config.json") }
        source = newSource(cacheFile)
    }

    private fun newSource(file: File?) = FreeModelConfigSource(
        dispatchUrl = DISPATCH_URL,
        cacheFile = file,
        builtInDefault = BUILT_IN_DEFAULT,
    )

    /** 当前生效的免费配置。 */
    fun current(): FreeModelRemoteConfig = source.current()

    /**
     * 向服务端拉取最新配置（失败静默回退）。
     *
     * 调用时机：请求前（由 Provider 侧节流），或 app 启动/回到前台。
     */
    fun refresh(force: Boolean = false): FreeModelConfigSource.FetchOutcome = source.refresh(force)

    /** 当前免费模型 id（随轮换变化）。 */
    fun currentModelId(): String = current().model

    /** 当前免费端点。 */
    fun currentBaseUrl(): String = current().baseUrl

    /** 当前派发密钥；不可用时返回空串。 */
    fun currentApiKey(): String = current().let { if (it.isUsable) it.apiKey else "" }

    /**
     * 白名单判定：当前生效模型 + 历史兼容模型。
     *
     * 历史模型保留是为了「已配置过旧模型的用户不因轮换突然拿不到 key」。
     */
    fun allowedModelIds(): Set<String> = setOf(current().model) + LEGACY_FREE_MODEL_IDS

    /**
     * 检查指定 provider + model 是否符合免费模型条件。
     *
     * @param providerId 仅 [FREE_PROVIDER_ID] 或已迁移的隐藏旧供应商允许使用内置免费额度
     * @param baseUrl 供应商 base url
     * @param modelId 模型 id
     * @param userApiKey 用户配置的 API key(空串表示未配置)
     * @param hiddenFromSettings 旧版本复制出的免费预置也可通过迁移标记保留能力
     * @return 如果符合条件返回派发 key，否则返回 null
     */
    fun resolveApiKey(
        providerId: String,
        baseUrl: String,
        modelId: String,
        userApiKey: String,
        hiddenFromSettings: Boolean = false,
    ): String? {
        val providerAllowed = providerId == FREE_PROVIDER_ID || hiddenFromSettings
        val modelAllowed = allowedModelIds().any { it.equals(modelId, ignoreCase = true) }
        val config = current()
        // 主机判定用**当前派发端点**，不再写死某一家供应商域名：
        // 轮换到新网关时无需改代码即可放行。
        val hostAllowed = isHostOf(config.baseUrl, baseUrl)
        return config.apiKey.takeIf {
            providerAllowed &&
                userApiKey.isBlank() &&
                hostAllowed &&
                modelAllowed &&
                isFallbackKeyAvailable()
        }
    }

    /** fallback key 是否可用(非空)。 */
    fun isFallbackKeyAvailable(): Boolean = current().isUsable

    /**
     * 判断指定 provider 是否是免费模型 provider（免费槽 + 用户未填 key）。
     *
     * 供 [OpenAIProvider.listModels] 在入口处判断：为 true 时直接返回预设免费模型列表，
     * 不调远程 /models（避免无 key 时 401）。
     */
    fun isFreeProvider(
        providerId: String,
        baseUrl: String,
        userApiKey: String,
        hiddenFromSettings: Boolean = false,
    ): Boolean =
        (providerId == FREE_PROVIDER_ID || hiddenFromSettings) &&
            userApiKey.isBlank() &&
            isHostOf(current().baseUrl, baseUrl)

    /** 两个 URL 是否指向同一主机（忽略协议与端口差异，避免 http/https 切换误判）。 */
    private fun isHostOf(expectedBaseUrl: String, actualBaseUrl: String): Boolean {
        val expected = extractHost(expectedBaseUrl) ?: return false
        val actual = extractHost(actualBaseUrl) ?: return false
        return expected == actual
    }

    private fun extractHost(baseUrl: String): String? = try {
        java.net.URL(baseUrl).host?.lowercase()
    } catch (_: Exception) {
        null
    }

    private fun readDispatchUrl(): String = try {
        io.zer0.ai.BuildConfig.FREE_MODEL_DISPATCH_URL
    } catch (_: Throwable) {
        DEFAULT_DISPATCH_URL
    }

    /** 官方默认派发端点；构建注入可覆盖。 */
    const val DEFAULT_DISPATCH_URL: String = "https://museai.ltd/api/free-model.json"

    /**
     * 历史兼容模型 id —— 已配置过它们的用户不该因轮换突然拿不到 key。
     */
    private val LEGACY_FREE_MODEL_IDS: Set<String> = setOf(
        "THUDM/GLM-4-9B-0414",
        "Qwen/Qwen3-8B",
    )
}
