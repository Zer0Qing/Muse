package io.zer0.muse.rag

import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.util.PriorityQueue
import kotlin.math.sqrt

/**
 * v1.54: 向量检索服务(余弦相似度遍历)。
 *
 * v1.133 改造:
 *  - embedding 读取:BLOB 优先,无 BLOB fallback JSON(兼容旧数据)
 *  - [mmrLambda] 参数:MMR 多样性重排(0=纯多样性,1=纯相似度)
 *  - [scopeDocIds] 参数:限定检索范围(@mention 定向检索 / 助手绑定 KB 时用)
 *  - [metadataFilter] 参数:元数据过滤(暂未实现,接口预留)
 *
 * @param chunkPageProvider 分页加载已索引 chunk(由调用方 join docTitle)
 * @param chunkCountProvider 已索引 chunk 总数
 */
class VectorSearchService(
    private val chunkPageProvider: suspend (limit: Int, offset: Int) -> List<ChunkWithDoc>,
    private val chunkCountProvider: suspend () -> Int,
    /**
     * v1.133: 按 docIds 过滤的分页 provider(用于 @mention 定向检索 / 助手绑定 KB)。
     * null 表示走全量 provider(向后兼容)。
     */
    private val chunkPageByDocIdsProvider: (suspend (docIds: List<String>, limit: Int, offset: Int) -> List<ChunkWithDoc>)? = null,
    /**
     * B4-03: 带 metadata 过滤的分页 provider(SQL 层过滤)。
     * null 时若传了 [MetadataFilter],VectorSearchService 会在内存中过滤。
     */
    private val chunkPageByMetadataProvider: (suspend (filter: MetadataFilter, limit: Int, offset: Int) -> List<ChunkWithDoc>)? = null,
) {
    data class SearchResult(
        val docId: String,
        val docTitle: String,
        val chunkContent: String,
        val score: Float,
        val chunkIndex: Int,
        /** v1.133: chunkId(用于引用渲染定位)。 */
        val chunkId: String = "",
        /**
         * v1.133: 是否为内部文档(预置开发文档 devdoc 等)。
         *
         * 由 [io.zer0.muse.rag.RagService.retrieve] 在结果回填阶段从
         * [io.zer0.muse.data.knowledge.KnowledgeDocEntity.isInternal] 批量查询注入,
         * 统一替代 `docId.startsWith("devdoc-")` 硬编码。
         * 默认 false(未回填前/查询失败时取默认值,行为安全)。
         */
        val isInternal: Boolean = false,
    )

    /** chunk + 所属文档标题(由调用方 join 查询)。 */
    data class ChunkWithDoc(
        val chunkId: String,
        val docId: String,
        val docTitle: String,
        val content: String,
        /** 旧 embedding JSON(兼容读取)。 */
        val embedding: String,
        /** v1.133: 新 embedding BLOB(优先读取)。 */
        val embeddingBlob: ByteArray? = null,
        val chunkIndex: Int,
        /** B4-03: 元数据 JSON(内存过滤兜底用)。 */
        val metadataJson: String = "{}",
        /** B4-03: 创建时间戳(内存过滤兜底用)。 */
        val createdAt: Long = 0L,
    )


    /** B4-03: 元数据过滤条件(docIds/tag/时间范围,全部可选)。 */
    data class MetadataFilter(
        val docIds: List<String> = emptyList(),
        val tag: String = "",
        val startTime: Long = 0L,
        val endTime: Long = 0L,
    ) {
        fun isEmpty(): Boolean = docIds.isEmpty() && tag.isBlank() && startTime == 0L && endTime == 0L
    }

    private data class CachedVector(
        val chunkId: String,
        val docId: String,
        val docTitle: String,
        val content: String,
        val vector: FloatArray,
        val norm: Float,
        val chunkIndex: Int,
    )

    @Volatile
    private var cache: List<CachedVector>? = null
    @Volatile
    private var cacheKey: String? = null  // v1.133: scopeDocIds hash,不同 scope 独立缓存
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    fun invalidateCache() {
        cache = null
        cacheKey = null
    }

    /**
     * v1.133: 检索 — 支持 MMR / scopeDocIds 过滤。
     *
     * @param queryVector 查询向量
     * @param topK 返回条数
     * @param threshold 相似度阈值
     * @param mmrLambda MMR 多样性权重(1.0=纯相似度排序,禁用 MMR;0<λ<1=MMR 重排)
     * @param scopeDocIds 限定检索范围(空=全库,null=向后兼容全库)
     */
    suspend fun search(
        queryVector: FloatArray,
        topK: Int,
        threshold: Float,
        mmrLambda: Float = 1.0f,
        scopeDocIds: List<String>? = null,
        metadataFilter: MetadataFilter? = null,
    ): List<SearchResult> {
        val queryNorm = norm(queryVector)
        if (queryNorm == 0f) return emptyList()

        // B4-03: metadata 过滤路径 — 下推到 SQL provider(不走全量缓存)
        if (metadataFilter != null && !metadataFilter.isEmpty() && chunkPageByMetadataProvider != null) {
            val filteredChunks = resultOf {
                chunkPageByMetadataProvider(metadataFilter, Int.MAX_VALUE, 0)
            }.getOrNull() ?: emptyList()
            val candidates = filteredChunks.mapNotNull { chunk ->
                val vector = parseEmbedding(chunk) ?: return@mapNotNull null
                val n = norm(vector)
                if (n == 0f) return@mapNotNull null
                val score = scoreOf(vector, n, queryVector, queryNorm)
                if (score == Float.NEGATIVE_INFINITY || score < threshold) return@mapNotNull null
                CachedVector(chunk.chunkId, chunk.docId, chunk.docTitle, chunk.content, vector, n, chunk.chunkIndex) to score
            }
            return applyMMR(candidates, topK, mmrLambda)
        }
        // v1.133: scope 过滤路径 — 走 docIds provider(不走全量缓存)
        if (scopeDocIds != null && scopeDocIds.isNotEmpty() && chunkPageByDocIdsProvider != null) {
            val scopeChunks = resultOf {
                chunkPageByDocIdsProvider(scopeDocIds, Int.MAX_VALUE, 0)
            }.getOrNull() ?: emptyList()
            val candidates = scopeChunks.mapNotNull { chunk ->
                if (!chunkMatchesMetadata(chunk, metadataFilter)) return@mapNotNull null
                val vector = parseEmbedding(chunk) ?: return@mapNotNull null
                val n = norm(vector)
                if (n == 0f) return@mapNotNull null
                val score = scoreOf(vector, n, queryVector, queryNorm)
                if (score == Float.NEGATIVE_INFINITY || score < threshold) return@mapNotNull null
                CachedVector(chunk.chunkId, chunk.docId, chunk.docTitle, chunk.content, vector, n, chunk.chunkIndex) to score
            }
            return applyMMR(candidates, topK, mmrLambda)
        }

        val total = resultOf { chunkCountProvider() }.getOrNull() ?: 0
        if (total == 0) return emptyList()

        if (total <= CACHE_THRESHOLD) {
            val vectors = getOrLoadCache(total, scopeDocIds, metadataFilter)
            if (vectors.isEmpty()) return emptyList()
            val candidates = vectors.mapNotNull { v ->
                val score = scoreOf(v.vector, v.norm, queryVector, queryNorm)
                if (score == Float.NEGATIVE_INFINITY || score < threshold) null
                else v to score
            }
            return applyMMR(candidates, topK, mmrLambda)
        }

        Logger.d("VectorSearchService", "大库检索(total=$total > $CACHE_THRESHOLD),走流式分批扫描")
        return searchStreamed(queryVector, queryNorm, topK, threshold, mmrLambda, metadataFilter)
    }

    /** v1.133: 应用 MMR 多样性重排。candidates 已按 score 降序排好。 */
    private fun applyMMR(
        candidates: List<Pair<CachedVector, Float>>,
        topK: Int,
        mmrLambda: Float,
    ): List<SearchResult> {
        if (candidates.isEmpty()) return emptyList()
        // λ=1.0 时纯按相似度排序(禁用 MMR)
        if (mmrLambda >= 1.0f) {
            return candidates
                .sortedByDescending { it.second }
                .take(topK)
                .map { (v, score) ->
                    SearchResult(v.docId, v.docTitle, v.content, score, v.chunkIndex, v.chunkId)
                }
        }

        // MMR: 已选 S,候选 C,每次选 argmax_{c∈C} [λ·sim(q,c) - (1-λ)·max_{s∈S} sim(c,s)]
        val sorted = candidates.sortedByDescending { it.second }.toMutableList()
        val selected = mutableListOf<Pair<CachedVector, Float>>()
        if (sorted.isEmpty()) return emptyList()
        selected.add(sorted.removeAt(0))

        while (selected.size < topK && sorted.isNotEmpty()) {
            var bestIdx = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (i in sorted.indices) {
                val (candidate, simToQuery) = sorted[i]
                var maxSimToSelected = 0f
                for (s in selected) {
                    val sim = cosineSim(candidate.vector, candidate.norm, s.first.vector, s.first.norm)
                    if (sim > maxSimToSelected) maxSimToSelected = sim
                }
                val mmrScore = mmrLambda * simToQuery - (1f - mmrLambda) * maxSimToSelected
                if (mmrScore > bestScore) {
                    bestScore = mmrScore
                    bestIdx = i
                }
            }
            selected.add(sorted.removeAt(bestIdx))
        }

        return selected.map { (v, score) ->
            SearchResult(v.docId, v.docTitle, v.content, score, v.chunkIndex, v.chunkId)
        }
    }

    private fun cosineSim(a: FloatArray, aNorm: Float, b: FloatArray, bNorm: Float): Float {
        if (a.size != b.size || aNorm == 0f || bNorm == 0f) return 0f
        return dotProduct(a, b) / (aNorm * bNorm)
    }

    private suspend fun searchStreamed(
        queryVector: FloatArray,
        queryNorm: Float,
        topK: Int,
        threshold: Float,
        mmrLambda: Float,
        metadataFilter: MetadataFilter?,
    ): List<SearchResult> {
        // 流式场景下 MMR 难以应用(需全量候选集算 sim),降级为纯相似度 + topK×5 候选再做 MMR
        val candidatePoolSize = if (mmrLambda < 1.0f) topK * 5 else topK
        val heap = PriorityQueue<Pair<CachedVector, Float>>(candidatePoolSize + 1) { a, b ->
            a.second.compareTo(b.second)
        }
        var offset = 0
        while (true) {
            val page = resultOf { chunkPageProvider(BATCH_SIZE, offset) }.getOrNull() ?: emptyList()
            if (page.isEmpty()) break
            for (chunk in page) {
                if (!chunkMatchesMetadata(chunk, metadataFilter)) continue
                val vector = parseEmbedding(chunk) ?: continue
                val n = norm(vector)
                if (n == 0f) continue
                val score = scoreOf(vector, n, queryVector, queryNorm)
                if (score == Float.NEGATIVE_INFINITY || score < threshold) continue
                val cv = CachedVector(chunk.chunkId, chunk.docId, chunk.docTitle, chunk.content, vector, n, chunk.chunkIndex)
                heap.offer(cv to score)
                if (heap.size > candidatePoolSize) heap.poll()
            }
            if (page.size < BATCH_SIZE) break
            offset += BATCH_SIZE
        }
        return applyMMR(heap.toList(), topK, mmrLambda)
    }

    private fun scoreOf(
        vector: FloatArray,
        vectorNorm: Float,
        queryVector: FloatArray,
        queryNorm: Float,
    ): Float = if (vector.size != queryVector.size) {
        Float.NEGATIVE_INFINITY
    } else {
        dotProduct(vector, queryVector) / (vectorNorm * queryNorm)
    }

    private suspend fun getOrLoadCache(
        total: Int,
        scopeDocIds: List<String>?,
        metadataFilter: MetadataFilter?,
    ): List<CachedVector> {
        val filterKey = metadataFilter?.let {
            "${it.docIds.sorted().joinToString(",")}#${it.tag}#${it.startTime}#${it.endTime}"
        } ?: ""
        val key = (scopeDocIds?.sorted()?.joinToString(",") ?: "ALL") + "|" + filterKey
        // scope 变化或首次加载
        cache?.let { if (key == cacheKey) return it }
        return mutex.withLock {
            cache?.let { if (key == cacheKey) return@withLock it }
            val chunks = chunkPageProvider(total, 0)
            // v1.133: 若有 scope,在内存中过滤(避免 provider 走全量)
            val filtered = if (scopeDocIds != null && scopeDocIds.isNotEmpty()) {
                chunks.filter { it.docId in scopeDocIds }
            } else chunks
            val filteredByMetadata = filtered.filter { chunkMatchesMetadata(it, metadataFilter) }
            val parsed = filteredByMetadata.mapNotNull { chunk ->
                val vector = parseEmbedding(chunk) ?: return@mapNotNull null
                val n = norm(vector)
                if (n == 0f) return@mapNotNull null
                CachedVector(chunk.chunkId, chunk.docId, chunk.docTitle, chunk.content, vector, n, chunk.chunkIndex)
            }
            cache = parsed
            cacheKey = key
            Logger.d("VectorSearchService", "加载 ${parsed.size} 条向量到缓存(scope=$key)")
            parsed
        }
    }

    /**
     * v1.133: 解析 embedding — BLOB 优先,JSON 兜底。
     */
    /** B4-03: metadata 内存过滤兜底(SQL provider 不可用时使用)。 */
    private fun chunkMatchesMetadata(chunk: ChunkWithDoc, filter: MetadataFilter?): Boolean {
        if (filter == null || filter.isEmpty()) return true
        if (filter.docIds.isNotEmpty() && chunk.docId !in filter.docIds) return false
        if (filter.tag.isNotBlank() && !chunk.metadataJson.contains("\"${filter.tag}\"")) return false
        if (filter.startTime > 0L && chunk.createdAt < filter.startTime) return false
        if (filter.endTime > 0L && chunk.createdAt > filter.endTime) return false
        return true
    }
    private fun parseEmbedding(chunk: ChunkWithDoc): FloatArray? {
        // BLOB 优先
        chunk.embeddingBlob?.let { blob ->
            return resultOf {
                val arr = FloatArray(blob.size / 4)
                ByteBuffer.wrap(blob).asFloatBuffer().get(arr)
                arr
            }.onError { msg, e ->
                Logger.w("VectorSearchService", "BLOB 解析失败,尝试 JSON: $msg", e)
            }.getOrNull()
        }
        // JSON fallback(旧数据)
        if (chunk.embedding.isBlank()) return null
        return resultOf {
            val arr = json.parseToJsonElement(chunk.embedding).jsonArray
            FloatArray(arr.size) { i -> arr[i].jsonPrimitive.float }
        }.getOrNull()
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) sum += a[i] * b[i]
        return sum
    }

    private fun norm(a: FloatArray): Float {
        var sum = 0f
        for (v in a) sum += v * v
        return sqrt(sum)
    }

    companion object {
        private const val BATCH_SIZE = 500
        private const val CACHE_THRESHOLD = 2000

        /** v1.133: FloatArray → ByteArray(BLOB 存储)。 */
        fun floatArrayToBlob(arr: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(arr.size * 4)
            buffer.asFloatBuffer().put(arr)
            return buffer.array()
        }
    }
}
