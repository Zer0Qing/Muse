package io.zer0.ai.core

import io.zer0.ai.util.KeyRoulette

/**
 * T1.2: 可复用的多 Key 轮换逻辑。
 *
 * 与 [ProviderHttpSupport] 内嵌的同名方法逻辑一致,但独立成类,
 * 供不继承 [ProviderHttpSupport] 的 Provider(如 ImageProvider 家族)复用,
 * 使 effectiveApiKey / switchToNextKey / markKeyFailed 可在测试中直接构造验证。
 *
 * 每个实例持有独立的 [KeyRoulette],避免跨 Provider 状态串扰。
 *
 * @param config 供应商配置(含可能逗号/换行分隔的多 key apiKey)
 * @param keyRoulette 可选注入,默认自建(测试可注入共享实例)
 */
open class ProviderKeyRotation(
    val config: ProviderConfig,
    private val keyRoulette: KeyRoulette = KeyRoulette(),
) {

    @Volatile
    var currentApiKey: String = config.apiKey
        private set

    /**
     * 取当前应使用的 API key。
     *
     * 单 key 场景(config.apiKey 不含逗号/换行)直接返回 trim 后的原 key,跳过 LRU 逻辑。
     *
     * T1.2 修复:多 key 场景每次调用都重新走 LRU pick,不再命中"缓存"分支直接返回
     * [currentApiKey] —— 否则会锁死在 [switchToNextKey] / [markKeyFailed] 轮换出的单个
     * key 上,失去 LRU 轮换与黑名单跳过的意义。
     */
    open fun effectiveApiKey(): String {
        if (!hasMultipleKeys()) {
            return config.apiKey.trim()
        }
        currentApiKey = keyRoulette.pick(config.id, config.apiKey)
        return currentApiKey
    }

    /**
     * 429 限流时切换到下一个 key。
     *
     * 把当前 key 加入软黑名单(60s),然后选取下一个 key。
     * 如果只有一个 key,返回 false(Provider 应走指数退避重试)。
     *
     * @return true 表示成功切换到新 key(应立即重试);
     *         false 表示只有一个 key 或切换后仍是同一 key
     */
    open fun switchToNextKey(): Boolean {
        if (!hasMultipleKeys()) {
            return false
        }
        val previous = currentApiKey
        currentApiKey = keyRoulette.pickNext(config.id, config.apiKey, previous)
        return currentApiKey != previous
    }

    /**
     * 标记当前 key 失败(如 401 鉴权失败),5 分钟内完全排除该 key,并选下一个。
     *
     * @return true 表示还有其他 key 可用(切换后重试);
     *         false 表示只有一个 key 或所有 key 都已失败
     */
    fun markKeyFailed(hardBlock: Boolean = true): Boolean {
        if (!hasMultipleKeys()) {
            return false
        }
        val failedKey = currentApiKey
        keyRoulette.markFailed(config.id, failedKey, hardBlock = hardBlock)
        currentApiKey = ""
        val newKey = effectiveApiKey()
        return newKey != failedKey
    }

    private fun hasMultipleKeys(): Boolean =
        config.apiKey.contains(',') || config.apiKey.contains('\n')
}
