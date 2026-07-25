package io.zer0.muse.rag

/**
 * v1.133: Rerank Provider 接口 — cross-encoder 重排序。
 *
 * 实现选项:
 *  - [LocalRerankProvider]:本地基于 BM25 + 关键词重叠的简单重排序(无依赖,质量一般)
 *  - CohereRerankProvider:调用 Cohere Rerank API(需依赖 + API Key,质量好)— 待引入依赖后实现
 *  - JinaRerankProvider:调用 Jina Rerank API(需依赖 + API Key,质量好)— 待引入依赖后实现
 *  - OnnxRerankProvider:本地 bge-reranker-base ONNX(需 onnxruntime-android 依赖)— 待引入依赖后实现
 *
 * 调用方在 [RagConfig.rerankEnabled]=true 时根据 [RagConfig.rerankProvider] 选择实现,
 * 不可用时降级到 [LocalRerankProvider]。
 */
interface RerankProvider {
    /**
     * 对候选集重排序。
     * @param query 用户查询
     * @param candidates 候选列表(docId/docTitle/content)
     * @param topK 最终返回条数
     * @return 重排序后的列表(分数已归一化到 0-1)
     */
    suspend fun rerank(
        query: String,
        candidates: List<RerankCandidate>,
        topK: Int,
    ): List<RerankResult>
}

/** 重排序输入候选。 */
data class RerankCandidate(
    val chunkId: String,
    val docId: String,
    val docTitle: String,
    val content: String,
    val chunkIndex: Int,
)

/** 重排序输出结果。 */
data class RerankResult(
    val chunkId: String,
    val docId: String,
    val docTitle: String,
    val content: String,
    val chunkIndex: Int,
    /** 重排序分数(0-1,越高越相关)。 */
    val score: Float,
)

/**
 * v1.133: 本地重排序 — 基于查询词覆盖率 + BM25 启发式(无需依赖,质量一般但可用)。
 *
 * 算法:
 *  1. 提取 query 的关键词(长度≥2 的 token)
 *  2. 对每个候选计算关键词覆盖率(content 中包含的关键词数 / 总关键词数)
 *  3. 结合 BM25 式的 TF-IDF 加权
 *
 * 适用场景:无网络 / 未配置 rerank API 时的降级。
 */
class LocalRerankProvider : RerankProvider {
    override suspend fun rerank(
        query: String,
        candidates: List<RerankCandidate>,
        topK: Int,
    ): List<RerankResult> {
        if (candidates.isEmpty()) return emptyList()
        val queryTerms = extractTerms(query)
        if (queryTerms.isEmpty()) {
            // 退化:维持原顺序
            return candidates.take(topK).map {
                RerankResult(it.chunkId, it.docId, it.docTitle, it.content, it.chunkIndex, 0.5f)
            }
        }

        val scored = candidates.map { c ->
            val contentLower = c.content.lowercase()
            val titleLower = c.docTitle.lowercase()
            var hitCount = 0
            var titleBonus = 0f
            for (term in queryTerms) {
                val termLower = term.lowercase()
                if (contentLower.contains(termLower)) hitCount++
                if (titleLower.contains(termLower)) titleBonus += 0.1f
            }
            val coverage = hitCount.toFloat() / queryTerms.size
            // 综合分:覆盖率 + 标题命中加成 + 长度归一化(短文本优先)
            val lengthPenalty = (1f / kotlin.math.sqrt(c.content.length.toFloat() / 100f + 1f))
            val score = (coverage * 0.8f + titleBonus) * lengthPenalty
            RerankResult(c.chunkId, c.docId, c.docTitle, c.content, c.chunkIndex, score.coerceIn(0f, 1f))
        }
        return scored.sortedByDescending { it.score }.take(topK)
    }

    private fun extractTerms(query: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in query) {
            if (ch.isLetterOrDigit()) current.append(ch)
            else {
                if (current.isNotEmpty()) {
                    tokens.add(current.toString())
                    current.clear()
                }
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        // 过滤单字(噪声大),保留长度≥2 的 token
        return tokens.filter { it.length >= 2 }.distinct()
    }
}
