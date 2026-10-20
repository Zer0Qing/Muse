package io.zer0.muse.rag

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.session.MessageFtsManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
    /**
     * P2-31: BM25-only 命中的内容解析(chunkId → 元数据)。
     * FTS 命中行只带 chunkId+score,此前 BM25-only 命中因取不到内容被整体丢弃;
     * 注入 DAO 解析后这些命中也能返回。null = 测试/未注入时保持旧行为(跳过)。
     */
    private val bm25MetaResolver: (suspend (List<String>) -> Map<String, ChunkMeta>)? = null,
) {
    /** P2-31: BM25 命中补齐内容/过滤判定所需的元数据。 */
    data class ChunkMeta(
        val docId: String,
        val docTitle: String,
        val content: String,
        val chunkIndex: Int,
        /** B4-03: 分块元数据 JSON(来源/tag 等过滤判定用)。 */
        val metadataJson: String = "",
        /** B4-03: 分块创建时间戳(时间范围过滤判定用)。 */
        val createdAt: Long = 0L,
    )

    /** 混合检索结果 — 用 RRF 分数替代原始相似度。 */
    data class HybridResult(
        val docId: String,
        val docTitle: String,
        val chunkContent: String,
        val chunkIndex: Int,
        val chunkId: String,
        /** RRF 融合分数(0-1 之间,越高越相关)。注意：这是**相对最高分**的比值，不是相似度。 */
        val rrfScore: Float,
        /**
         * P1-6: 原始分数（向量余弦相似度）。BM25-only 命中为 0。
         *
         * UI 展示「相似度」时应使用本字段，而不是 [rrfScore] —— 后者最高一律为 1.0，
         * 不是真实相似度。
         */
        val rawScore: Float = 0f,
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
     * @param bm25Weight BM25 路 RRF 权重(默认 1.0)
     * @param vectorWeight 向量路 RRF 权重(默认 1.0)
     * @param scopeDocIds 限定检索范围(可选)
     * @param metadataFilter 元数据过滤条件(可选,null/空 = 不过滤)。docIds/标题/来源/tag/时间
     *   同时作用于向量与 BM25 两条链路:向量侧由 [VectorSearchService.search] 过滤,
     *   BM25 侧在本方法内按 [bm25MetaResolver] 解析出的文档元数据过滤,保证两边结果一致。
     * @param vectorCandidateK 向量候选数(默认 topK×3,扩大 RRF 候选池)
     * @param bm25CandidateK BM25 候选数(默认 topK×3)
     */
    suspend fun hybridSearch(
        query: String,
        queryVector: FloatArray,
        topK: Int,
        threshold: Float,
        mmrLambda: Float,
        bm25Weight: Float = 1f,
        vectorWeight: Float = 1f,
        scopeDocIds: List<String>? = null,
        metadataFilter: VectorSearchService.MetadataFilter? = null,
        vectorCandidateK: Int = topK * 3,
        bm25CandidateK: Int = topK * 3,
    ): List<HybridResult> {
        // 1. 并行检索（真正的协程并发）
        //
        // P1-4 修复（审计报告）: 旧注释写着“并行检索(协程并发)”，但 resultOf 只是
        // try/catch 包装（见 common/Result.kt），**没有任何 async**，两行是顺序执行的。
        // 大库时向量检索耗时与 BM25 耗时全额叠加。
        val (vectorResults, bm25Hits) = coroutineScope {
            val vectorDeferred = async {
                resultOf {
                    vectorSearch.search(queryVector, vectorCandidateK, threshold, mmrLambda, scopeDocIds, metadataFilter)
                }
            }
            val bm25Deferred = async {
                resultOf { ftsDao.searchBm25(buildFtsQuery(query), bm25CandidateK) }
            }
            vectorDeferred.await().getOrNull().orEmpty() to bm25Deferred.await().getOrNull().orEmpty()
        }

        Logger.d(
            "HybridSearchService",
            "混合检索:vector=${vectorResults.size}, bm25=${bm25Hits.size}, query=$query",
        )

        if (vectorResults.isEmpty() && bm25Hits.isEmpty()) return emptyList()

        // B4-03: 元数据过滤只在非空时启用(null / 空 filter = 不过滤,向后兼容)。
        val filter = metadataFilter?.takeIf { !it.isEmpty() }

        // 2. RRF 融合
        // 向量路径:按 score 降序排(已在 search 内排过,但保险起见再排一次)
        //   向量侧过滤已由 VectorSearchService.search(metadataFilter) 在链路内完成。
        val vectorRanked = vectorResults.sortedByDescending { it.score }
        val vectorChunkIds = vectorRanked.map { it.chunkId }.toHashSet()
        // BM25 路径:score 升序(SQLite bm25 返回负值,越小越相关),取反作为正向
        val bm25Sorted = bm25Hits.sortedBy { it.score }

        // P2-31/B4-03: 解析 BM25 命中的文档元数据(resolver 注入时)。
        //  - 无过滤:仅补齐 bm25-only 命中的内容(旧行为);
        //  - 有过滤:全部 BM25 命中都要拿到 docId/标题/元数据,才能与向量链路同样被过滤。
        val bm25MetaById: Map<String, ChunkMeta> = if (bm25MetaResolver != null) {
            val needIds = if (filter != null) {
                bm25Sorted.map { it.chunkId }
            } else {
                bm25Sorted.map { it.chunkId }.filter { it !in vectorChunkIds }
            }.distinct()
            if (needIds.isEmpty()) {
                emptyMap()
            } else {
                runCatching { bm25MetaResolver(needIds) }.getOrNull().orEmpty()
            }
        } else {
            emptyMap()
        }

        // B4-03: BM25 过滤必须在 RRF 融合前完成 — 被过滤掉的命中不得贡献 RRF 分数。
        // 无法解析元数据的命中在过滤开启时按「不通过」处理,避免放行未校验文档(安全侧)。
        val bm25Ranked = if (filter == null) {
            bm25Sorted
        } else {
            bm25Sorted.filter { hit ->
                bm25MetaById[hit.chunkId]?.let { m ->
                    filter.matches(m.docId, m.docTitle, m.metadataJson, m.createdAt)
                } == true
            }
        }

        val rrfScores = mutableMapOf<String, Float>() // chunkId -> rrfScore
        val metaMap = mutableMapOf<String, VectorSearchService.SearchResult>()
        val bm25ChunkIds = mutableSetOf<String>()

        // 向量 RRF 贡献
        vectorRanked.forEachIndexed { rank, r ->
            val contribution = vectorWeight * (1.0f / (rank + 1 + RRF_K))
            rrfScores[r.chunkId] = (rrfScores[r.chunkId] ?: 0f) + contribution
            metaMap[r.chunkId] = r
        }

        // BM25 RRF 贡献(只贡献分数,内容仍以向量结果为准;若仅在 BM25 命中则单独构造条目)
        // P1-7 修复（审计报告）: threshold 之前只作用于向量路，BM25 命中无论多弱都参与 RRF，
        // 一个恰好含关键词但语义无关的块可能靠 BM25 一路混进 topK。
        // SQLite bm25() 返回负值且越小越相关；这里归一化为 0~1 的相对强度后卡阈值。
        val bm25RawRange = if (bm25Ranked.isEmpty()) {
            0.0
        } else {
            (bm25Ranked.last().score - bm25Ranked.first().score).takeIf { it > 0.0 } ?: 0.0
        }
        bm25Ranked.forEachIndexed { rank, hit ->
            // 相对强度：排名第 0 的命中为 1.0，最弱的为 0.0（无分差时全部为 1.0）
            val strength = if (bm25RawRange <= 0.0) {
                1f
            } else {
                (1.0 - (hit.score - bm25Ranked.first().score) / bm25RawRange).toFloat().coerceIn(0f, 1f)
            }
            // threshold <= 0 表示不启用过滤（与向量路语义一致）
            if (threshold > 0f && strength < threshold) {
                return@forEachIndexed
            }
            val contribution = bm25Weight * (1.0f / (rank + 1 + RRF_K))
            rrfScores[hit.chunkId] = (rrfScores[hit.chunkId] ?: 0f) + contribution
            bm25ChunkIds.add(hit.chunkId)
        }

        // 3. 构造结果(优先用向量结果的内容)
        // P2-31: 此前 `filter { metaMap[it.key] != null }` 把 BM25-only 命中整体丢弃 —
        // 精确命中(专有名词/代码标识符)恰好是 BM25 的强项,不该丢。注入 resolver
        // 后为 BM25-only 命中补齐内容元数据;无法解析的(库中已删除)仍按旧行为跳过。
        val bm25OnlyIds = rrfScores.keys.filter { it !in metaMap }
        for (chunkId in bm25OnlyIds) {
            val m = bm25MetaById[chunkId] ?: continue
            metaMap[chunkId] = VectorSearchService.SearchResult(
                docId = m.docId,
                docTitle = m.docTitle,
                chunkContent = m.content,
                score = 0f,
                chunkIndex = m.chunkIndex,
                chunkId = chunkId,
            )
        }
        val maxScore = rrfScores.values.maxOrNull() ?: 0f
        return rrfScores.entries
            .filter { metaMap[it.key] != null } // 只保留有内容的结果(无法解析的 BM25-only 跳过)
            .map { (chunkId, score) ->
                val r = metaMap[chunkId]!!
                HybridResult(
                    docId = r.docId,
                    docTitle = r.docTitle,
                    chunkContent = r.chunkContent,
                    chunkIndex = r.chunkIndex,
                    chunkId = chunkId,
                    // P1-6 修复（审计报告）: rrfScore 是「相对最高分」的比值（最高一律 1.0），
                    // 不是余弦相似度。UI 上却写成「相似度 0.83」—— 那个数字是假的。
                    // 同时保留 rawScore（向量余弦 / BM25 无），供 UI 展示真实可信的数值。
                    rrfScore = if (maxScore > 0) score / maxScore else 0f,
                    rawScore = r.score.toFloat(),
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
            val filtered = MessageFtsManager.toNgram(query)
                .split(' ')
                .filter { it.length >= 2 }
                .distinct()
                .take(10)
            if (filtered.isEmpty()) {
                return "\"${query.take(50).replace("\"", " ")}\"" // fallback 短语
            }
            // 中文 ngram 需要按 token 边界引用;英文/数字保留裸词以兼容既有 FTS4 语法。
            val rendered = filtered.map { token ->
                if (token.any { it.code > 127 }) "\"$token\"" else token
            }
            // 多 token 用空格分隔(FTS4 默认 AND);若 token 太多降级为 OR。
            return if (rendered.size <= 5) {
                rendered.joinToString(" ")
            } else {
                rendered.joinToString(" OR ")
            }
        }
    }
}
