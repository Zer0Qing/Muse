package io.zer0.ai.core

/**
 * v1.0.18: 免费模型配置(Kelivo 式机制)。
 *
 * 用户未填 API Key 时,使用内置 SiliconFlow 免费额度访问指定模型。
 *
 * 参考 QingTian kelivo-master 的实现(`lib/core/services/api/chat_api_service.dart`
 * 与 `lib/core/providers/model_provider.dart`):
 *  - host 含 siliconflow + modelId 在白名单 + 用户 key 为空 → 用 fallback key
 *  - 用户一旦填了自己的 key 就走用户 key,fallback 不再生效
 *
 * 工程说明:
 *  - 本对象放在 ai 模块而非 app/data/preset,因为 [OpenAIProvider] 在 ai 模块
 *    需要直接调用 [resolveApiKey];ai 模块不依赖 app 模块,反向依赖会破坏分层。
 *  - [FALLBACK_API_KEY] 用 "PLACEHOLDER" 占位,实际发布时由 CI/CD 注入真实 key
 *    (参考 Kelivo `.github/workflows/build.yml` 中 `lib/secrets/fallback.dart` 注入方式)。
 *    app 模块 build.gradle.kts 已开启 buildConfig = true,后续可通过 BuildConfig 注入;
 *    现阶段仅占位,fallback 解析会因 "PLACEHOLDER" 字符串被 [resolveApiKey] 拒绝返回 null,
 *    退化到原 apiKey 行为(空串或允许 allowMissingApiKey 的 Provider 仍可调远程 /models)。
 */
object FreeModelConfig {

    /**
     * SiliconFlow 免费模型白名单。
     *
     * 这两款模型在 SiliconFlow 平台是免费公开的(对齐 Kelivo `_defaultModels`
     * 中 SiliconFlow 预填清单 + `model_provider.dart` 第 432-444 行白名单)。
     */
    val FREE_MODEL_IDS: Set<String> = setOf(
        "THUDM/GLM-4-9B-0414",
        "Qwen/Qwen3-8B",
    )

    /**
     * 内置 fallback API key。
     *
     * 注入路径(ai/build.gradle.kts 已配置 buildConfigField):
     *  1. 命令行 -PFREE_MODEL_KEY=xxx
     *  2. local.properties 的 FREE_MODEL_KEY 字段(已被 .gitignore 忽略)
     *  3. 环境变量 FREE_MODEL_KEY
     *  4. 均未设置时回退 "PLACEHOLDER"(fallback 机制不生效)
     */
    val FALLBACK_API_KEY: String = getFallbackKey()

    /**
     * SiliconFlow base url(对齐 [io.zer0.muse.data.preset.SiliconFlowFreeModels.BASE_URL])。
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
     * @param baseUrl 供应商 base url
     * @param modelId 模型 id
     * @param userApiKey 用户配置的 API key(空串表示未配置)
     * @return 如果符合条件返回 fallback key,否则返回 null
     */
    fun resolveApiKey(baseUrl: String, modelId: String, userApiKey: String): String? {
        // 用户已配置自己的 key,完全跳过 fallback(走用户 key)
        if (userApiKey.isNotBlank()) return null
        val host = try {
            java.net.URL(baseUrl).host?.lowercase() ?: return null
        } catch (_: Exception) {
            return null
        }
        if (!host.contains("siliconflow")) return null
        // host + modelId 双重白名单校验(对齐 Kelivo `chat_api_service.dart` `_resolveApiKey`)
        val allowed = FREE_MODEL_IDS.any { it.equals(modelId, ignoreCase = true) }
        if (!allowed) return null
        // 占位符视为未注入,返回 null(让调用方走原 apiKey 逻辑)
        return FALLBACK_API_KEY.takeIf { it.isNotBlank() && it != "PLACEHOLDER" }
    }

    /**
     * 判断指定 provider 是否是免费模型 provider(SiliconFlow 且用户未填 key)。
     *
     * 供 [OpenAIProvider.listModels] 在入口处判断:为 true 时直接返回预设的免费模型列表,
     * 不调远程 /models(避免因无 key 而 401 失败)。
     */
    fun isFreeProvider(baseUrl: String, userApiKey: String): Boolean {
        if (userApiKey.isNotBlank()) return false
        val host = try {
            java.net.URL(baseUrl).host?.lowercase() ?: return false
        } catch (_: Exception) {
            return false
        }
        return host.contains("siliconflow")
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
