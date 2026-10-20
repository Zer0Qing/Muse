package io.zer0.muse.data.stats

import io.zer0.common.Logger

/**
 * 用量流水写入器。
 *
 * 单一入口，保证每次模型请求的记录口径一致；调用方无需关心去重与异常。
 * 写入失败只记日志、不向上抛——统计是旁路能力，绝不能因为它中断对话。
 */
class UsageRecorder(private val dao: UsageRecordDao) {

    /**
     * 记录一次请求。
     *
     * @param source 来源，见 [UsageSource]
     * @param promptTokens 输入 token
     * @param completionTokens 输出 token（已含 reasoning，避免重复计）
     * @param reasoningTokens 推理 token（仅展示）
     * @param cachedTokens 缓存命中 token（仅展示）
     * @param firstTokenMs 首字延迟；0 表示未记录（非流式）
     * @param durationMs 总耗时；0 表示未记录
     */
    suspend fun record(
        source: String,
        modelId: String = "",
        providerId: String = "",
        promptTokens: Int = 0,
        completionTokens: Int = 0,
        reasoningTokens: Int = 0,
        cachedTokens: Int = 0,
        firstTokenMs: Long = 0,
        durationMs: Long = 0,
    ) {
        // 全零记录没有统计价值，只会在表里堆垃圾（如本地工具轮、缓存命中回放）。
        if (promptTokens == 0 && completionTokens == 0) return
        runCatching {
            dao.insert(
                UsageRecordEntity(
                    createdAt = System.currentTimeMillis(),
                    source = source,
                    modelId = modelId,
                    providerId = providerId,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    reasoningTokens = reasoningTokens,
                    cachedTokens = cachedTokens,
                    firstTokenMs = firstTokenMs.coerceAtLeast(0),
                    durationMs = durationMs.coerceAtLeast(0),
                ),
            )
        }.onFailure {
            Logger.w("UsageRecorder", "写入用量记录失败(source=$source): ${it.message}")
        }
    }

    /**
     * 清理过期记录。
     *
     * 用量表只增不减，长期使用会持续增长；保留 [retentionDays] 天足够看趋势。
     */
    suspend fun pruneOlderThan(retentionDays: Int = 180) {
        if (retentionDays <= 0) return
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
        runCatching { dao.deleteBefore(cutoff) }
            .onFailure { Logger.w("UsageRecorder", "清理用量记录失败: ${it.message}") }
    }
}
