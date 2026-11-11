package io.zer0.muse.knowledge.retrieval

/**
 * R5 多空间检索协调 — 设计 §9.2「多库查询按空间分组，各自编码与召回，再做排名融合」。
 *
 * 不变式(由 KnowledgeMultiSpaceTest 钉住):
 *  - **不跨空间计算余弦**:每个空间各自产生排序,只融合**排名**,不比较不同空间的原始分数;
 *  - 一个空间不可用时报告 [MultiSpaceOutcome.partial] 与 [MultiSpaceOutcome.missingSpaces],
 *    不影响其它空间已授权的词法结果;
 *  - 融合使用 RRF(Reciprocal Rank Fusion),分数字段保留来源空间,不伪造统一"相似度"。
 */
data class ScoredChunk(
    val spaceId: String,
    val chunkId: String,
    /** 本空间内的原始分数;只用于本空间排序,绝不跨空间比较。 */
    val rawScore: Float,
    val rank: Int,
)

/** 单空间检索结果。 */
sealed interface SpaceOutcome {
    data class Found(val results: List<ScoredChunk>) : SpaceOutcome

    /** 空间不可用(模型缺失/维度不匹配/未发布);原因可展示。 */
    data class Unavailable(val reason: String) : SpaceOutcome

    /** 空间检索失败(超时/异常);与「零命中」区分。 */
    data class Failed(val reason: String) : SpaceOutcome
}

data class MultiSpaceOutcome(
    val merged: List<ScoredChunk>,
    val missingSpaces: List<SpaceFailure>,
    /** true = 有空间未完成(不可用或失败),结果不完整。 */
    val partial: Boolean,
) {
    data class SpaceFailure(val spaceId: String, val kind: String, val reason: String)
}

object MultiSpaceCoordinator {

    /** RRF 常数(经验值 60);只融合排名,不比较跨空间分数。 */
    private const val RRF_K = 60.0

    /**
     * 合并多空间结果。
     * @param outcomes spaceId → 该空间结果;None/空结果不产生候选,但不会被伪装成失败。
     * @param finalK 最终返回条数上限。
     */
    fun merge(outcomes: Map<String, SpaceOutcome>, finalK: Int = 10): MultiSpaceOutcome {
        require(finalK > 0) { "finalK 必须为正" }
        val failures = mutableListOf<MultiSpaceOutcome.SpaceFailure>()
        val scores = mutableMapOf<String, Double>()
        val byId = mutableMapOf<String, ScoredChunk>()

        for ((spaceId, outcome) in outcomes) {
            when (outcome) {
                is SpaceOutcome.Found -> {
                    for (item in outcome.results) {
                        // RRF:1/(k+rank),只依赖本空间排名。
                        val rrf = 1.0 / (RRF_K + (item.rank + 1))
                        scores[item.chunkId] = (scores[item.chunkId] ?: 0.0) + rrf
                        if (byId[item.chunkId] == null) byId[item.chunkId] = item
                    }
                }
                is SpaceOutcome.Unavailable -> failures.add(
                    MultiSpaceOutcome.SpaceFailure(spaceId, "unavailable", outcome.reason),
                )
                is SpaceOutcome.Failed -> failures.add(
                    MultiSpaceOutcome.SpaceFailure(spaceId, "failed", outcome.reason),
                )
            }
        }

        val merged = scores.entries
            .sortedByDescending { it.value }
            .take(finalK)
            .mapIndexed { index, entry ->
                val chunk = byId.getValue(entry.key)
                chunk.copy(rank = index)
            }
        return MultiSpaceOutcome(
            merged = merged,
            missingSpaces = failures,
            partial = failures.isNotEmpty(),
        )
    }
}
