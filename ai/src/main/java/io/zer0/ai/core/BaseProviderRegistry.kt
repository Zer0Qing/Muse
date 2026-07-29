package io.zer0.ai.core

import io.zer0.common.Logger

/**
 * v1.0.30 (P5-D): Provider 注册中心通用基类。
 *
 * 消除 [io.zer0.ai.image.ImageProviderRegistry] 与 [io.zer0.ai.video.VideoProviderRegistry]
 * 的孪生重复代码(register / get / all / selectFor 共用逻辑)。
 *
 * 子类通过 [hostPatterns] 提供 baseUrl host → providerId 映射,
 * 通过 [fallbackProvider] 指定兜底 Provider。
 *
 * @param T Provider 接口类型
 * @param tag 日志 TAG
 */
abstract class BaseProviderRegistry<T>(
    private val tag: String,
) {
    private val providers = mutableMapOf<String, T>()

    /**
     * 注册一个 Provider(以 [providerId] 为 key,后者覆盖前者)。
     * @param providerId Provider 的唯一标识
     * @param provider Provider 实例
     */
    fun register(providerId: String, provider: T) {
        providers[providerId] = provider
        Logger.i(tag, "registered provider: $providerId")
    }

    /** 按 providerId 取已注册的 Provider。 */
    fun get(providerId: String): T? = providers[providerId]

    /** 所有已注册的 provider。 */
    fun all(): Collection<T> = providers.values

    /**
     * baseUrl host 模式 → providerId 映射(按优先级排序)。
     * 子类重写以提供自家 host 匹配规则。空列表表示不做 host 匹配。
     */
    protected open val hostPatterns: List<Pair<String, String>> = emptyList()

    /**
     * 从 [ProviderConfig] 提取匹配 key:
     * 1. specId(非空优先)
     * 2. id 去掉 "preset_" 前缀
     */
    protected fun resolveConfigKey(config: ProviderConfig): String? {
        return config.specId?.takeIf { it.isNotBlank() }
            ?: config.id.removePrefix("preset_").takeIf { it.isNotBlank() }
    }

    /**
     * 从 baseUrl 提取 host(小写),解析失败返回空串。
     */
    protected fun extractHost(baseUrl: String): String {
        if (baseUrl.isBlank()) return ""
        return runCatching {
            java.net.URI(baseUrl).host?.lowercase() ?: ""
        }.getOrElse {
            val afterScheme = baseUrl.substringAfter("://", baseUrl)
            afterScheme.substringBefore('/').lowercase()
        }
    }

    /**
     * 按 host 模式匹配 Provider。
     * 遍历 [hostPatterns],返回第一个匹配的 provider,未匹配返回 null。
     */
    protected fun matchByHost(host: String): T? {
        if (host.isEmpty()) return null
        for ((hostPattern, providerId) in hostPatterns) {
            if (host.contains(hostPattern, ignoreCase = true)) {
                providers[providerId]?.let { return it }
            }
        }
        return null
    }

    /**
     * 通用 selectFor 逻辑:
     * 1. specId / id 精确匹配
     * 2. baseUrl host 模式匹配
     * 3. 子类自定义兜底(通过 [fallback])
     *
     * @param config Provider 配置
     * @param fallback 兜底逻辑(子类提供)
     */
    protected fun selectForInternal(
        config: ProviderConfig,
        fallback: () -> T?,
    ): T? {
        // 1. specId / id 精确匹配
        val key = resolveConfigKey(config)
        if (key != null) {
            providers[key]?.let { return it }
        }

        // 2. host 模式匹配
        val host = extractHost(config.resolvedBaseUrl())
        matchByHost(host)?.let { return it }

        // 3. 子类兜底
        return fallback()
    }
}
