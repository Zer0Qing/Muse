package io.zer0.muse.data

import io.zer0.ai.core.ProviderConfig

/**
 * P2-1: Provider 冲突(重复配置)检测器。
 *
 * 当用户在不同条目里填入「相同 baseUrl + 相同 API Key 末 4 位」时,
 * 视为可能重复的供应商配置,提示用户合并以避免混淆。
 *
 * 设计要点:
 *  - baseUrl 规范化:去 `http(s)://` 前缀、去尾斜杠、转小写,保留 host+port+path,
 *    使 `https://API.OpenAI.com/v1/` 与 `http://api.openai.com/v1` 视为同源。
 *  - apiKey 仅比较末 4 位:避免在内存/日志中留存完整密钥,保护隐私。
 *    两侧 apiKey 均为空时也视为「末 4 位相同」(都是空串),提示用户清理重复空壳配置。
 *  - 同一 baseUrl 但 apiKey 末 4 位不同 → 不算冲突(可能是同一中转站多账号)。
 *  - 仅对成对比较结果非自身的两条配置产出 [ProviderCollision]。
 *
 * 使用方:[io.zer0.muse.ui.settings.providerListSection] 在列表上方展示警告卡片。
 */
object ProviderCollisionDetector {

    /**
     * 检测 provider 列表中的冲突:相同 baseUrl(规范化后,去尾斜杠+小写)
     * + apiKey 末 4 位视为冲突。
     *
     * @return 冲突列表;空列表表示未检测到重复。每对冲突只返回一次(A<B 时才记录)。
     */
    fun detect(providers: List<ProviderConfig>): List<ProviderCollision> {
        if (providers.size < 2) return emptyList()

        // 预计算每条配置的规范化键,避免在 N² 内层重复计算。
        val normalized = providers.map { it to normalizeKey(it) }
        val collisions = mutableListOf<ProviderCollision>()
        for (i in normalized.indices) {
            for (j in (i + 1) until normalized.size) {
                val (a, keyA) = normalized[i]
                val (b, keyB) = normalized[j]
                if (keyA == keyB) {
                    collisions += ProviderCollision(
                        providerA = a,
                        providerB = b,
                        reason = "相同 baseUrl 与 API Key",
                    )
                }
            }
        }
        return collisions
    }

    /**
     * 规范化 baseUrl:去 `http(s)://` 前缀、去尾斜杠、转小写,保留 host+port+path。
     * 例:`https://API.OpenAI.com/v1/` → `api.openai.com/v1`
     *
     * 与 apiKey 末 4 位拼接为统一比较键;apiKey 均空时以空串占位,
     * 表示「两侧均未配置 Key」也视为冲突(可能是重复空壳配置)。
     */
    private fun normalizeKey(config: ProviderConfig): String {
        val resolved = config.resolvedBaseUrl()
        val stripped = resolved
            .substringAfter("://", missingDelimiterValue = resolved)
            .trimEnd('/')
            .lowercase()
        val keyTail = config.apiKey.takeLast(4)
        return "$stripped|$keyTail"
    }
}

/**
 * 一对冲突的 Provider 配置。
 *
 * @param providerA 冲突双方之一
 * @param providerB 冲突双方之一
 * @param reason 人类可读的冲突原因(如「相同 baseUrl 与 API Key」)
 */
data class ProviderCollision(
    val providerA: ProviderConfig,
    val providerB: ProviderConfig,
    val reason: String,
)
