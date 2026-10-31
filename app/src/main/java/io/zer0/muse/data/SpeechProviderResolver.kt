package io.zer0.muse.data

import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.asr.AsrProviderType

/**
 * 语音能力的凭据来源。
 *
 * - [MANUAL]: 使用 TTS/ASR 设置页里手填的 endpoint / apiKey / model
 * - [PROVIDER]: 使用供应商配置里的 baseUrl / apiKey,模型取跟随模式下手选的模型 id
 */
enum class SpeechEndpointSource {
    PROVIDER,
    MANUAL,
}

/**
 * 一次语音请求最终生效的连接参数。
 *
 * @param baseUrl 实际生效的接口地址(手动模式下可能是空串,由各客户端内置默认值兜底)
 * @param apiKey 实际生效的 apiKey(已解密)
 * @param model 实际生效的模型名
 * @param source 参数来源
 * @param fallbackReason 开关已开但回退到手动配置时的原因;
 *   null 表示未发生回退(开关关闭不算回退)
 */
data class ResolvedSpeechEndpoint(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val source: SpeechEndpointSource,
    val fallbackReason: String? = null,
) {
    /** 是否是因为跟随失败而回退(调用方据此打 WARN)。 */
    val isProviderFallback: Boolean
        get() = source == SpeechEndpointSource.MANUAL && fallbackReason != null
}

/**
 * TTS / ASR 的「跟随供应商配置」解析器。
 *
 * 纯函数:输入当前配置 + 供应商列表,输出实际生效的地址/凭据/模型,不做 IO、不抛异常。
 * 调用方负责在 [ResolvedSpeechEndpoint.fallbackReason] 非空时记录日志,便于排查
 * 「开关开着却仍走手动配置」这类静默回退。
 *
 * 规则优先级从高到低:
 *  1. 开关关闭 → 一律手动配置([SpeechEndpointSource.MANUAL]);
 *  2. 开关开启 + 供应商可解析且启用 + 模型已选 → 供应商配置([SpeechEndpointSource.PROVIDER]);
 *  3. 开关开启但供应商缺失/已删除、供应商被禁用、模型未选 → 回退手动配置并带原因。
 */
object SpeechProviderResolver {
    /** 跟随模式指向的供应商已不存在(或被删除)。 */
    const val REASON_PROVIDER_MISSING = "provider_missing"

    /** 跟随模式指向的供应商存在但已停用。 */
    const val REASON_PROVIDER_DISABLED = "provider_disabled"

    /** 跟随模式未选择模型。 */
    const val REASON_MODEL_MISSING = "model_missing"

    /** 解析 TTS(云端引擎)的实际连接参数。 */
    fun resolveMedia(config: MediaConfig, providers: List<ProviderConfig>): ResolvedSpeechEndpoint =
        resolve(
            follow = config.ttsFollowProvider,
            providerId = config.ttsProviderId,
            modelId = config.ttsModelId,
            manualBaseUrl = config.ttsEndpoint,
            manualApiKey = config.ttsApiKey,
            manualModel = config.ttsModel,
            providers = providers,
        )

    /**
     * 解析 ASR 的实际连接参数。
     *
     * DashScope 实时识别走内置 wss 端点([AsrConfig.asrEndpoint]),供应商 baseUrl 不参与,
     * 因此该引擎下直接忽略跟随开关(视为手动配置,且不带回退原因):
     * 用户先在别的引擎下开了跟随、再切到 DashScope 时,既不会误用供应商地址,
     * 也不会因为供应商被删/停用而冒出一条假回退告警。
     */
    fun resolveAsr(config: AsrConfig, providers: List<ProviderConfig>): ResolvedSpeechEndpoint {
        if (config.provider == AsrProviderType.DASHSCOPE) {
            return manual(config.baseUrl, config.apiKey, config.model)
        }
        return resolve(
            follow = config.followProvider,
            providerId = config.providerId,
            modelId = config.modelId,
            manualBaseUrl = config.baseUrl,
            manualApiKey = config.apiKey,
            manualModel = config.model,
            providers = providers,
        )
    }

    private fun manual(baseUrl: String, apiKey: String, model: String): ResolvedSpeechEndpoint =
        ResolvedSpeechEndpoint(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            source = SpeechEndpointSource.MANUAL,
        )

    private fun resolve(
        follow: Boolean,
        providerId: String,
        modelId: String,
        manualBaseUrl: String,
        manualApiKey: String,
        manualModel: String,
        providers: List<ProviderConfig>,
    ): ResolvedSpeechEndpoint {
        val manualResult = manual(manualBaseUrl, manualApiKey, manualModel)
        // 规则 1:开关关闭时开关优先级最高,供应商列表完全不参与
        if (!follow) return manualResult

        val provider = providers.firstOrNull { it.id == providerId }
        val reason = if (providerId.isBlank() || provider == null) {
            REASON_PROVIDER_MISSING
        } else if (!provider.enabled) {
            REASON_PROVIDER_DISABLED
        } else if (modelId.isBlank()) {
            REASON_MODEL_MISSING
        } else {
            null
        }
        if (reason != null) return manualResult.copy(fallbackReason = reason)
        val resolvedProvider = provider ?: return manualResult.copy(fallbackReason = REASON_PROVIDER_MISSING)
        return ResolvedSpeechEndpoint(
            baseUrl = resolvedProvider.resolvedBaseUrl(),
            apiKey = resolvedProvider.apiKey,
            model = modelId,
            source = SpeechEndpointSource.PROVIDER,
        )
    }
}

/**
 * 把解析结果落到 ASR 配置上,供各流式 Controller 直接读取。
 *
 * 手动模式原样返回(保证手填值不被覆盖);只有确定走供应商时才替换
 * apiKey / baseUrl / model,其余字段(采样率、VAD、热词等)保持不变。
 */
fun AsrConfig.withResolvedEndpoint(resolved: ResolvedSpeechEndpoint): AsrConfig = when (resolved.source) {
    SpeechEndpointSource.MANUAL -> this
    SpeechEndpointSource.PROVIDER -> copy(
        apiKey = resolved.apiKey,
        baseUrl = resolved.baseUrl,
        model = resolved.model,
    )
}
