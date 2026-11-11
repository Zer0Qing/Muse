package io.zer0.muse.knowledge.retrieval

import io.zer0.muse.knowledge.domain.KnowledgeScope
import io.zer0.muse.knowledge.domain.RetrievalPolicy

/**
 * R6 统一检索 — 设计 §10「范围先于候选截断」与计划 R6。
 *
 * 类型化结果(不用 Any / 不把错误伪装成空结果):
 *  - [RetrievalOutcome.Found] 正常有结果;
 *  - [RetrievalOutcome.NoMatch] 范围内零命中(不是失败);
 *  - [RetrievalOutcome.EmptyScope] 范围为空/无效/解析失败 —— **绝不扩大为全库**;
 *  - [RetrievalOutcome.Unavailable] 所有召回路径不可用;
 *  - [RetrievalOutcome.Partial] 部分路径降级(有结果但某路失败/未完成);
 *  - [RetrievalOutcome.Failed] 检索执行失败。
 *
 * 分数字段分开保留(denseScore / lexicalScore / fusionRank / rerankScore),不把融合值
 * 展示成"相似度";不同空间/路径的原始分数不跨路比较(计划 R6 步骤 5)。
 */

/** 检索入口类型 — trace 用,不参与权限判断(权限由 R1 范围合同负责)。 */
enum class KnowledgeEntryType {
    CHAT,
    GROUP_CHAT,
    TOOL,
    DEBUG,
    CHANNEL,
    A2A,
    MINI_PHONE,
    BUILTIN_HELP,
}

data class KnowledgeSearchRequest(
    val requestId: String,
    val query: String,
    val entry: KnowledgeEntryType,
    /** 已解析范围(R1 合同);None/Invalid/Failed 一律返回 EmptyScope。 */
    val scope: KnowledgeScope,
    val policy: RetrievalPolicy,
    val datasetEpoch: Long,
    val deadlineMs: Long,
    /**
     * R6: 本轮查询向量(由调用方按已授权空间编码得到);null = 本轮不启用 dense 路径。
     * 检索器不自行编码 —— 编码涉及外部模型调用与授权,必须在装配层完成。
     */
    val queryVector: FloatArray? = null,
) {
    // FloatArray 是引用类型,data class 默认 equals 会误判;显式关闭值语义。
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** 检索候选 — 分数字段分开,不合并成单一"相似度"。 */
data class EvidenceCandidate(
    val chunkId: String,
    val docId: String,
    val generationId: String,
    val text: String,
    val locator: String,
    val lexicalScore: Float? = null,
    val denseScore: Float? = null,
    val fusionRank: Int? = null,
    val rerankScore: Float? = null,
)

/** 检索追踪 — 默认日志不含查询正文与文件位置(计划 R6 步骤 7)。 */
data class RetrievalTrace(
    val requestId: String,
    val entry: String,
    /** 范围摘要(如 "docs=3"/"all"/"none"),不含正文。 */
    val scopeSummary: String,
    val spaces: List<String>,
    val lexicalCount: Int,
    val denseCount: Int,
    val fusedCount: Int,
    val finalCount: Int,
    val degradedReasons: List<String>,
    val elapsedMs: Long,
)

sealed interface RetrievalOutcome {
    data class Found(
        val candidates: List<EvidenceCandidate>,
        val trace: RetrievalTrace,
        val partial: Boolean = false,
    ) : RetrievalOutcome

    data class NoMatch(val trace: RetrievalTrace) : RetrievalOutcome

    data class EmptyScope(val reason: String, val trace: RetrievalTrace) : RetrievalOutcome

    data class Unavailable(val reason: String, val trace: RetrievalTrace) : RetrievalOutcome

    data class Partial(
        val candidates: List<EvidenceCandidate>,
        val missingPaths: List<String>,
        val trace: RetrievalTrace,
    ) : RetrievalOutcome

    data class Failed(val reason: String, val trace: RetrievalTrace) : RetrievalOutcome
}

/** 词法召回结果。 */
data class LexicalRecall(
    val hits: List<LexicalHit>,
    /** true = 达到候选预算被截断,召回不完整(必须在 trace 记录)。 */
    val incomplete: Boolean = false,
    /** 非空 = 词法路径降级原因(如 FTS 缺表),调用方据此报 Partial。 */
    val degradedReason: String? = null,
)

data class LexicalHit(
    val chunkId: String,
    val docId: String,
    val generationId: String,
    val text: String,
    val locator: String,
    val score: Float,
    val rank: Int,
)

/** 向量召回结果。 */
data class DenseRecall(
    val hits: List<DenseHit>,
    val incomplete: Boolean = false,
    val degradedReason: String? = null,
)

data class DenseHit(
    val chunkId: String,
    val docId: String,
    val generationId: String,
    val text: String,
    val locator: String,
    val score: Float,
    val rank: Int,
)

/** 词法检索后端合同 — R6 的真实实现走 FTS4 MATCH/matchinfo;测试可用内存实现。 */
interface LexicalRetriever {
    suspend fun search(request: KnowledgeSearchRequest, activeGenerationIds: List<String>, limit: Int): LexicalRecall
}

/** 向量检索后端合同 — 同空间 ANN 或数据库扫描。 */
interface DenseRetriever {
    suspend fun search(request: KnowledgeSearchRequest, activeGenerationIds: List<String>, limit: Int): DenseRecall
}

/** 重排合同 — 失败返回 null(调用方保留原序并截断 finalK,不抛给用户)。 */
interface RerankProvider {
    suspend fun rerank(query: String, candidates: List<EvidenceCandidate>, topK: Int): List<EvidenceCandidate>?
}

/** 活动代解析 — 把已解析范围映射为可查询的 generation 列表。范围为空必须返回空。 */
fun interface ActiveGenerationResolver {
    suspend fun resolve(scope: KnowledgeScope): List<String>
}

/**
 * 统一检索实现:范围 → 词法/dense 召回 → RRF 排名融合 → 可选重排 → finalK。
 *
 * 关键规则(由 KnowledgeRetrieverContractTest 钉住):
 *  1. None/Invalid/Failed 范围返回 [RetrievalOutcome.EmptyScope],不做任何召回;
 *  2. 范围在候选配额**之前**生效(后端接收 activeGenerationIds 过滤);
 *  3. 词法与 dense 分路失败互不掩盖:一路可用 → Partial;两路都不可用 → Unavailable;
 *  4. 零命中 ≠ 失败;
 *  5. rerank 失败保留原序,仍截 finalK。
 */
class KnowledgeRetriever(
    private val lexicalRetriever: LexicalRetriever,
    private val denseRetriever: DenseRetriever? = null,
    private val rerankProvider: RerankProvider? = null,
    private val generationResolver: ActiveGenerationResolver,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun search(request: KnowledgeSearchRequest): RetrievalOutcome {
        val startedAt = clock()
        val scope = request.scope
        val traceBase = TraceBuilder(request, startedAt, clock)

        // 1. 范围先于候选:空/无效/失败绝不扩域;可检索范围同时给出 trace 摘要。
        val scopeSummary = when (scope) {
            is KnowledgeScope.None -> return RetrievalOutcome.EmptyScope("空范围", traceBase.build())
            is KnowledgeScope.Invalid -> return RetrievalOutcome.EmptyScope("失效绑定", traceBase.build())
            is KnowledgeScope.Failed ->
                return RetrievalOutcome.EmptyScope("范围解析失败: ${scope.reason}", traceBase.build())
            is KnowledgeScope.Resolved -> "docs=${scope.docIds.size}"
            is KnowledgeScope.AllUserLibraries -> "all"
        }
        traceBase.scopeSummary = scopeSummary

        // 2. 解析活动代(范围过滤在这一步)。
        val activeGenerations = try {
            generationResolver.resolve(scope)
        } catch (e: Exception) {
            return RetrievalOutcome.Failed("活动代解析失败: ${e.message}", traceBase.build())
        }
        if (activeGenerations.isEmpty()) {
            return RetrievalOutcome.NoMatch(traceBase.build())
        }

        val limit = request.policy.topK
        val recall = recallBothPaths(request, activeGenerations, limit)
        val degraded = recall.degradedReasons.toMutableList()
        traceBase.lexicalCount = recall.lexicalHits.size
        traceBase.denseCount = recall.denseHits.size

        // 3. 两路都不可用 -> Unavailable;一路可用 -> 继续(有结果则 Partial)。
        if (recall.bothUnavailable(request.policy.hybridEnabled, denseRetriever == null)) {
            return RetrievalOutcome.Unavailable(recall.unavailableReason(), traceBase.build())
        }

        // 4. 排名融合(RRF):只融合排名,不比较跨路原始分数。
        val fused = fuse(recall.lexicalHits, recall.denseHits)
        traceBase.fusedCount = fused.size
        if (fused.isEmpty()) {
            return if (degraded.isNotEmpty()) {
                RetrievalOutcome.Partial(emptyList(), degraded, traceBase.build())
            } else {
                RetrievalOutcome.NoMatch(traceBase.build())
            }
        }

        // 5. 可选重排(只在给定候选内重排;失败保留原序)。
        val finalCandidates = applyRerank(request, fused, limit, degraded).take(limit)
        traceBase.finalCount = finalCandidates.size
        val trace = traceBase.build()

        return if (degraded.isEmpty()) {
            RetrievalOutcome.Found(finalCandidates, trace)
        } else {
            RetrievalOutcome.Partial(finalCandidates, degraded, trace)
        }
    }


    /** 两路召回结果与降级原因。 */
    private class RecallOutcome(
        val lexicalHits: List<LexicalHit>,
        val denseHits: List<DenseHit>,
        val degradedReasons: List<String>,
        val lexicalError: String?,
        val denseError: String?,
        val lexicalDegraded: String?,
        val denseDegraded: String?,
    ) {
        fun bothUnavailable(hybridEnabled: Boolean, noDenseRetriever: Boolean): Boolean {
            val lexicalAvailable = lexicalError == null && lexicalDegraded == null
            val denseAvailable = denseError == null && denseDegraded == null
            return !lexicalAvailable && !denseAvailable && (hybridEnabled || noDenseRetriever)
        }

        fun unavailableReason(): String =
            listOfNotNull(lexicalError, denseError, lexicalDegraded, denseDegraded)
                .joinToString("; ")
                .ifBlank { "所有检索路径不可用" }
    }

    private suspend fun recallBothPaths(
        request: KnowledgeSearchRequest,
        activeGenerations: List<String>,
        limit: Int,
    ): RecallOutcome {
        var lexical: LexicalRecall? = null
        var lexicalError: String? = null
        try {
            lexical = lexicalRetriever.search(request, activeGenerations, limit)
        } catch (e: Exception) {
            lexicalError = "词法检索异常: ${e.message}"
        }

        var dense: DenseRecall? = null
        var denseError: String? = null
        if (request.policy.hybridEnabled && denseRetriever != null) {
            try {
                dense = denseRetriever.search(request, activeGenerations, limit)
            } catch (e: Exception) {
                denseError = "向量检索异常: ${e.message}"
            }
        }

        val degraded = mutableListOf<String>()
        listOfNotNull(lexical?.degradedReason, lexicalError, dense?.degradedReason, denseError)
            .forEach { degraded.add(it) }
        if (lexical?.incomplete == true) degraded.add("词法召回达到预算被截断")
        if (dense?.incomplete == true) degraded.add("向量召回达到预算被截断")

        return RecallOutcome(
            lexicalHits = lexical?.hits.orEmpty(),
            denseHits = dense?.hits.orEmpty(),
            degradedReasons = degraded,
            lexicalError = lexicalError,
            denseError = denseError,
            lexicalDegraded = lexical?.degradedReason,
            denseDegraded = dense?.degradedReason,
        )
    }

    /** 可选重排:失败保留原序并记录降级,不抛给用户。 */
    private suspend fun applyRerank(
        request: KnowledgeSearchRequest,
        fused: List<EvidenceCandidate>,
        limit: Int,
        degraded: MutableList<String>,
    ): List<EvidenceCandidate> {
        if (!request.policy.rerankEnabled || rerankProvider == null) return fused
        val reranked = try {
            rerankProvider.rerank(request.query, fused, limit)
        } catch (e: Exception) {
            degraded.add("重排失败(保留原序): ${e.message}")
            null
        }
        return when {
            reranked != null -> reranked
            else -> {
                if (degraded.none { it.startsWith("重排失败") }) {
                    degraded.add("重排失败(保留原序)")
                }
                fused
            }
        }
    }

    /** RRF 融合 — 只依赖每路排名。 */
    private fun fuse(lexicalHits: List<LexicalHit>, denseHits: List<DenseHit>): List<EvidenceCandidate> {
        val scores = mutableMapOf<String, Double>()
        val candidates = mutableMapOf<String, EvidenceCandidate>()

        for (hit in lexicalHits) {
            scores[hit.chunkId] = (scores[hit.chunkId] ?: 0.0) + 1.0 / (RRF_K + hit.rank + 1)
            candidates.putIfAbsent(
                hit.chunkId,
                EvidenceCandidate(
                    chunkId = hit.chunkId,
                    docId = hit.docId,
                    generationId = hit.generationId,
                    text = hit.text,
                    locator = hit.locator,
                    lexicalScore = hit.score,
                ),
            )
        }
        for (hit in denseHits) {
            scores[hit.chunkId] = (scores[hit.chunkId] ?: 0.0) + 1.0 / (RRF_K + hit.rank + 1)
            val existing = candidates[hit.chunkId]
            if (existing == null) {
                candidates[hit.chunkId] = EvidenceCandidate(
                    chunkId = hit.chunkId,
                    docId = hit.docId,
                    generationId = hit.generationId,
                    text = hit.text,
                    locator = hit.locator,
                    denseScore = hit.score,
                )
            } else {
                candidates[hit.chunkId] = existing.copy(denseScore = hit.score)
            }
        }

        return scores.entries
            .sortedByDescending { it.value }
            .mapIndexed { index, entry ->
                candidates.getValue(entry.key).copy(fusionRank = index)
            }
    }

    private class TraceBuilder(
        val request: KnowledgeSearchRequest,
        val startedAt: Long,
        val clock: () -> Long,
    ) {
        var scopeSummary: String = "none"
        var lexicalCount: Int = 0
        var denseCount: Int = 0
        var fusedCount: Int = 0
        var finalCount: Int = 0
        val spaces: MutableList<String> = mutableListOf()

        fun build(): RetrievalTrace = RetrievalTrace(
            requestId = request.requestId,
            entry = request.entry.name,
            scopeSummary = scopeSummary,
            spaces = spaces.toList(),
            lexicalCount = lexicalCount,
            denseCount = denseCount,
            fusedCount = fusedCount,
            finalCount = finalCount,
            degradedReasons = emptyList(), // 降级原因随 Partial 单独返回,避免双份真相
            elapsedMs = (clock() - startedAt).coerceAtLeast(0L),
        )
    }

    private companion object {
        const val RRF_K = 60.0
    }
}
