package io.zer0.ai.core

import io.zer0.common.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.132: 上游模型列表缓存 — 避免反复进入 Provider 编辑页都打一次网络。
 *
 * 设计要点(参考 openhanako 的文件级缓存 + kelivo/rikkahub 的"无缓存"反例):
 *  - 内存缓存(进程级单例),按 [CacheKey] 索引
 *  - 5 分钟 TTL,过期后下次拉取强制刷新
 *  - 命中且未过期时直接返回,跳过网络请求
 *  - [forceFresh] 参数允许 UI 手动刷新绕过缓存
 *  - [invalidate] 在 Provider 配置变更(改 apiKey/baseUrl/删除)时调用
 *  - 缓存键指纹:providerId + baseUrl + apiKey 末 4 位 + type(任一变化即失效)
 *
 * 缓存对象 [CachedModels] 持有 [models] 和 [fetchedAt](ms 时间戳)。
 * 容量上限 32,超限淘汰最旧条目(防止异常累积)。
 *
 * 注意:本缓存仅缓存"上游原始返回"的模型列表;
 * 调用方仍需对结果做 [ModelRegistry.enrich] 以补全能力/模态/上下文窗口。
 */
object ModelListCache {

    private const val TAG = "ModelListCache"

    /** 缓存 TTL(毫秒)。5 分钟内重复拉取直接返回缓存。 */
    private const val CACHE_TTL_MS = 5L * 60 * 1000

    /** 最大缓存条目数,超限淘汰最旧(防止异常累积)。 */
    private const val MAX_ENTRIES = 32

    private data class CacheKey(
        val providerId: String,
        val type: String,
        val baseUrl: String,
        val apiKeyTail: String,
    )

    private data class CachedModels(
        val models: List<Model>,
        val fetchedAt: Long,
    )

    private val cache = ConcurrentHashMap<CacheKey, CachedModels>()

    /**
     * 生成 [CacheKey] — baseUrl/apiKey 变化即视为新条目,自动失效旧缓存。
     * apiKey 只取末 4 位作为指纹(避免完整 key 留存内存)。
     */
    private fun keyOf(config: ProviderConfig): CacheKey {
        val base = config.resolvedBaseUrl()
        val tail = if (config.apiKey.length > 4) config.apiKey.takeLast(4) else config.apiKey
        return CacheKey(
            providerId = config.id,
            type = config.type.name,
            baseUrl = base,
            apiKeyTail = tail,
        )
    }

    /**
     * 查询缓存。命中且未过期返回 [List];否则返回 null。
     *
     * @param forceFresh true 时强制视为未命中(UI 手动刷新场景)
     */
    fun get(config: ProviderConfig, forceFresh: Boolean = false): List<Model>? {
        if (forceFresh) return null
        val key = keyOf(config)
        val cached = cache[key] ?: return null
        val age = System.currentTimeMillis() - cached.fetchedAt
        if (age > CACHE_TTL_MS) {
            Logger.i(TAG, "cache expired: ${key.providerId} age=${age}ms")
            return null
        }
        Logger.i(TAG, "cache hit: ${key.providerId} models=${cached.models.size} age=${age}ms")
        return cached.models
    }

    /**
     * 写入缓存。容量超 [MAX_ENTRIES] 时淘汰最旧条目。
     */
    fun put(config: ProviderConfig, models: List<Model>) {
        val key = keyOf(config)
        cache[key] = CachedModels(models = models, fetchedAt = System.currentTimeMillis())
        // 容量淘汰:超过上限时删除最旧条目
        if (cache.size > MAX_ENTRIES) {
            cache.entries
                .sortedBy { it.value.fetchedAt }
                .take(cache.size - MAX_ENTRIES)
                .forEach { cache.remove(it.key) }
        }
    }

    /**
     * 失效指定 Provider 的缓存(配置变更/删除时调用)。
     */
    fun invalidate(providerId: String) {
        val keysToRemove = cache.keys.filter { it.providerId == providerId }
        keysToRemove.forEach { cache.remove(it) }
        if (keysToRemove.isNotEmpty()) {
            Logger.i(TAG, "invalidated ${keysToRemove.size} entries for provider=$providerId")
        }
    }

    /**
     * 清空全部缓存(全局重置时调用,如退出登录/切换账号)。
     */
    fun clear() {
        val n = cache.size
        cache.clear()
        Logger.i(TAG, "cleared $n entries")
    }
}
