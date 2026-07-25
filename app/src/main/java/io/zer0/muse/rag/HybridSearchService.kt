package io.zer0.muse.rag

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsHit

/**
 * v1.133: 混合检索服务 — BM25(FTS4) + 向量余弦 RRF 融合。
 *
 * 算法:
 *  1. 并行执行 BM25 FTS 检索(top-K1)和向量检索(top-K2)
 *  2. 用 RRF(Reciprocal Rank Fusion)合并:
 *     score = Σ 1/(rank_i + RRF_K)
 *     其中 RRF_K=60 是经验常数
 *  3. 按 RRF 分数降序取 top-K
 *
 * 优势:
 *  - 向量擅长语义相似(同义/上下文)
 *  - BM25 擅长精确匹配(专有名词/代码标识符)
 *  - RRF 不依赖原始分数可比性,鲁棒
 */
class HybridSearchService(
    private val ftsDao: KnowledgeChunkFtsDao,
    private val vectorSearch: VectorSearchService,
) {
    /** 混合检索结果 — 用 RRF 分数替代原始相似度。 */
    data class HybridResult(
        val docId: String,
        val docTitle: String,
        val chunkContent: String,
        val chunkIndex: Int,
        val chunkId: String,
        /** RRF 融合分数(0-1 之间,越高越相关)。 */
        val rrfScore: Float,
        /** 是否同时被 BM25 和向量命中(true = 双路命中,可信度高)。 */
        val bothHit: Boolean,
    )

    /**
     * 执行混合检索。
     *
     * @param query 用户原始查询(分词后用于 FTS MATCH)
     * @param queryVector 查询向量
     * @param topK 最终返回条数
     * @param threshold 向量检索的相似度阈值
     * @param mmrLambda MMR 权重(传给向量检索)
     * @param scopeDocIds 限定检索范围(可选)
     * @param vectorCandidateK 向量候选数(默认 topK×3,扩大 RRF 候选池)
     * @param bm25CandidateK BM25 候选数(默认 topK×3)
     */
    suspend fun hybridSearch(
        query: String,
        queryVector: FloatArray,
        topK: Int,
        threshold: Float,
        mmrLambda: Float,
        scopeDocIds: List<String>? = null,
        vectorCandidateK: Int = topK * 3,
        bm25CandidateK: Int = topK * 3,
    ): List<HybridResult> {
        // 1. 并行检索(协程并发)
        val vectorDeferred = resultOf {
            vectorSearch.search(queryVector, vectorCandidateK, threshold, mmrLambda, scopeDocIds)
        }
        val bm25Deferred = resultOf {
            ftsDao.searchBm25(buildFtsQuery(query), bm25CandidateK)
        }

        val vectorResults = vectorDeferred.getOrNull() ?: emptyList()
        val bm25Hits = bm25Deferred.getOrNull() ?: emptyList()

        Logger.d(
            "HybridSearchService",
            "混合检索:vector=${vectorResults.size}, bm25=${bm25Hits.size}, query=$query",
        )

        if (vectorResults.isEmpty() && bm25Hits.isEmpty()) return emptyList()

        // 2. RRF 融合
        // 向量路径:按 score 降序排(已在 search 内排过,但保险起见再排一次)
        val vectorRanked = vectorResults.sortedByDescending { it.score }
        // BM25 路径:score 升序(SQLite bm25 返回负值,越小越相关),取反作为正向
        val bm25Ranked = bm25Hits.sortedBy { it.score }

        val rrfScores = mutableMapOf<String, Float>()  // chunkId -> rrfScore
        val metaMap = mutableMapOf<String, VectorSearchService.SearchResult>()
        val bm25ChunkIds = mutableSetOf<String>()

        // 向量 RRF 贡献
        vectorRanked.forEachIndexed { rank, r ->
            val contribution = 1.0f / (rank + 1 + RRF_K)
            rrfScores[r.chunkId] = (rrfScores[r.chunkId] ?: 0f) + contribution
            metaMap[r.chunkId] = r
        }

        // BM25 RRF 贡献(只贡献分数,内容仍以向量结果为准;若仅在 BM25 命中则单独构造条目)
        bm25Ranked.forEachIndexed { rank, hit ->
            val contribution = 1.0f / (rank + 1 + RRF_K)
            rrfScores[hit.chunkId] = (rrfScores[hit.chunkId] ?: 0f) + contribution
            bm25ChunkIds.add(hit.chunkId)
        }

        // 3. 构造结果(优先用向量结果的内容,BM25-only 命中无法获取内容则跳过)
        val maxScore = rrfScores.values.maxOrNull() ?: 0f
        return rrfScores.entries
            .filter { metaMap[it.key] != null }  // 只保留有内容的结果(BM25-only 跳过)
            .map { (chunkId, score) ->
                val r = metaMap[chunkId]!!
                HybridResult(
                    docId = r.docId,
                    docTitle = r.docTitle,
                    chunkContent = r.chunkContent,
                    chunkIndex = r.chunkIndex,
                    chunkId = chunkId,
                    rrfScore = if (maxScore > 0) score / maxScore else 0f,
                    bothHit = chunkId in bm25ChunkIds,
                )
            }
            .sortedByDescending { it.rrfScore }
            .take(topK)
    }

    companion object {
        /** RRF 经验常数(标准值 60)。 */
        private const val RRF_K = 60

        /**
         * 把用户原始查询转为 FTS4 MATCH 语法。
         * 简单策略:按空格/中文字符切分单词,用空格连接(FTS4 默认 AND)。
         * 长查询(>10 词)用 OR 避免空命中。
         */
        fun buildFtsQuery(query: String): String {
            // 转义 FTS4 特殊字符
            val cleaned = query.replace(Regex("[\"*]"), " ")
            // 中英文混合切分(中文按字符,英文按空格)
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            for (ch in cleaned) {
                if (ch.isLetterOrDigit()) {
                    current.append(ch)
                } else {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
            }
            if (current.isNotEmpty()) tokens.add(current.toString())

            val filtered = tokens.filter { it.length >= 2 }.distinct().take(10)
            if (filtered.isEmpty()) return "\"${query.take(50)}\""  // fallback 短语
            // 多 token 用空格分隔(FTS4 默认 AND);若 token 太多降级为 OR
            return if (filtered.size <= 5) {
                filtered.joinToString(" ")
            } else {
                filtered.joinToString(" OR ")
            }
        }
    }
}
