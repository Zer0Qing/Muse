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
 *  - [metadataFilter] 参数:元数据过滤(docIds/标题/来源/tag/时间范围),向量与 BM25 两条链路共用同一判定
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

    /**
     * B4-03: 元数据过滤条件(全部可选,均为「与」关系;为空表示不约束)。
     *
     * 文档级条件([docIds]/[titleKeyword])与分块级条件([sourceKeyword]/[tag]/[startTime]/[endTime])
     * 统一由 [matches] 判定,向量检索与 BM25 检索共用同一份语义,避免两条链路过滤结果不一致。
     */
    data class MetadataFilter(
        /** 限定文档 ID 集合(空 = 不按文档过滤)。 */
        val docIds: List<String> = emptyList(),
        /** 分块 metadata_json 中的 tag 精确标记(空 = 不按 tag 过滤)。 */
        val tag: String = "",
        /** 分块创建时间下界(0 = 不限制,含边界)。 */
        val startTime: Long = 0L,
        /** 分块创建时间上界(0 = 不限制,含边界)。 */
        val endTime: Long = 0L,
        /** 标题关键字(忽略大小写包含匹配,命中文档标题;空 = 不按标题过滤)。 */
        val titleKeyword: String = "",
        /** 来源关键字(忽略大小写包含匹配,命中分块 metadata_json 的 source/file 等字段;空 = 不按来源过滤)。 */
        val sourceKeyword: String = "",
    ) {
        fun isEmpty(): Boolean = docIds.isEmpty() && tag.isBlank() && startTime == 0L && endTime == 0L &&
            titleKeyword.isBlank() && sourceKeyword.isBlank()

        /**
         * B4-03: 判定单条分块是否满足全部过滤条件。
         *
         * @param docId 所属文档 ID(对应 [docIds])
         * @param docTitle 文档标题(对应 [titleKeyword])
         * @param metadataJson 分块元数据 JSON(对应 [sourceKeyword]/[tag])
         * @param createdAt 分块创建时间戳(对应 [startTime]/[endTime])
         */
        fun matches(docId: String, docTitle: String, metadataJson: String, createdAt: Long): Boolean {
            if (docIds.isNotEmpty() && docId !in docIds) return false
            if (titleKeyword.isNotBlank() && !docTitle.contains(titleKeyword, ignoreCase = true)) return false
            if (sourceKeyword.isNotBlank() && !metadataJson.contains(sourceKeyword, ignoreCase = true)) return false
            if (tag.isNotBlank() && !metadataJson.contains("\"$tag\"")) return false
            if (startTime > 0L && createdAt < startTime) return false
            if (endTime > 0L && createdAt > endTime) return false
            return true
        }
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

    /**
     * 向量缓存：键 = scope|metadata 过滤条件。
     *
     * 为什么是**有界多条目**而不是单条目：旧实现只留一份，键一变就整表重载。
     * 实际使用里最常见的组合是「不带过滤」与「某个固定 tag 过滤」交替出现
     * （主对话走全库、@mention 或定向检索带过滤），单条目缓存会在这两者之间来回打穿。
     *
     * 为什么只留 [MAX_CACHE_ENTRIES] 条：单条最多 [CACHE_THRESHOLD] 个 768 维向量，
     * 每条都带正文，条数放大就是内存放大。两条正好覆盖「全库 + 一个过滤」的交替，
     * 再多就要按真实内存预算重新论证。
     */
    private val cacheEntries = object : LinkedHashMap<String, List<CachedVector>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<CachedVector>>): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    fun invalidateCache() {
        synchronized(cacheEntries) { cacheEntries.clear() }
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

        // B4-03: metadata 过滤路径 — docIds/tag/时间下推到 SQL provider(避免全量缓存)。
        // P2-33: 分批拉取(旧实现传 Int.MAX_VALUE,一次性把过滤结果全部载入内存)
        // 注意:SQL provider 只覆盖 docIds/tag/时间,标题/来源等条件仍需内存兜底判定,
        //       故 applyMetadataInMemory=true(SQL 为优化,内存判定为语义唯一真源)。
        if (metadataFilter != null && !metadataFilter.isEmpty() && chunkPageByMetadataProvider != null) {
            return scanPagedProvider(
                queryVector = queryVector,
                queryNorm = queryNorm,
                topK = topK,
                threshold = threshold,
                mmrLambda = mmrLambda,
                applyMetadataInMemory = true,
                metadataFilter = metadataFilter,
                pageProvider = { limit, offset -> chunkPageByMetadataProvider(metadataFilter, limit, offset) },
            )
        }
        // v1.133: scope 过滤路径 — 走 docIds provider(不走全量缓存)
        // P2-33: 同样分批拉取:定向检索不再把整个检索范围(大库可 >2000 chunk)一次性载入
        if (scopeDocIds != null && scopeDocIds.isNotEmpty() && chunkPageByDocIdsProvider != null) {
            return scanPagedProvider(
                queryVector = queryVector,
                queryNorm = queryNorm,
                topK = topK,
                threshold = threshold,
                mmrLambda = mmrLambda,
                applyMetadataInMemory = true,
                metadataFilter = metadataFilter,
                pageProvider = { limit, offset -> chunkPageByDocIdsProvider(scopeDocIds, limit, offset) },
            )
        }

        val total = resultOf { chunkCountProvider() }.getOrNull() ?: 0
        if (total == 0) return emptyList()

        if (total <= CACHE_THRESHOLD) {
            val vectors = getOrLoadCache(total, scopeDocIds, metadataFilter)
            if (vectors.isEmpty()) return emptyList()
            val candidates = vectors.mapNotNull { v ->
                val score = scoreOf(v.vector, v.norm, queryVector, queryNorm)
                if (score == Float.NEGATIVE_INFINITY || score < threshold) {
                    null
                } else {
                    v to score
                }
            }
            return applyMMR(candidates, topK, mmrLambda)
        }

        Logger.d("VectorSearchService", "大库检索(total=$total > $CACHE_THRESHOLD),走流式分批扫描")
        // P2-30: 流式路径此前丢弃 scopeDocIds,大库(>CACHE_THRESHOLD)时越界到全库检索
        return searchStreamed(queryVector, queryNorm, topK, threshold, mmrLambda, scopeDocIds, metadataFilter)
    }

    /**
     * P2-33: 定向检索的分批扫描(scope / metadata 两条 provider 路径共用)。
     *
     * 按 [BATCH_SIZE] 逐页拉取(每次 provider 调用只载入一页),峰值载入量与库大小解耦;
     * 结果与旧「一次性全量载入 + 打分 + 排序」严格等价:
     *  - λ<1.0(MMR):候选集必须全局可见,故保留全部候选(数量受检索范围而非全库限制);
     *  - λ≥1.0(纯相似度,含默认值):用 [BoundedTopK] 有界候选池,只保留 topK 且平局次序
     *    与稳定排序 `sortedByDescending{score}.take(topK)` 一致。
     *
     * @param applyMetadataInMemory 是否按 metadataFilter 逐条内存判定。scope / metadata 两条
     *   provider 路径均需开启:provider 只承担候选范围裁剪,过滤语义统一由 [MetadataFilter.matches]
     *   在内存中判定(避免标题/来源等 provider 未覆盖的条件被静默忽略)。
     */
    private suspend fun scanPagedProvider(
        queryVector: FloatArray,
        queryNorm: Float,
        topK: Int,
        threshold: Float,
        mmrLambda: Float,
        applyMetadataInMemory: Boolean,
        metadataFilter: MetadataFilter?,
        pageProvider: suspend (limit: Int, offset: Int) -> List<ChunkWithDoc>,
    ): List<SearchResult> {
        val needsFullCandidates = mmrLambda < 1.0f
        val allCandidates = if (needsFullCandidates) ArrayList<Pair<CachedVector, Float>>() else null
        val bounded = if (needsFullCandidates) null else BoundedTopK(topK)
        var offset = 0
        while (true) {
            val page = resultOf { pageProvider(BATCH_SIZE, offset) }.getOrNull() ?: emptyList()
            if (page.isEmpty()) break
            for (chunk in page) {
                if (applyMetadataInMemory && !chunkMatchesMetadata(chunk, metadataFilter)) continue
                val vector = parseEmbedding(chunk) ?: continue
                val n = norm(vector)
                if (n == 0f) continue
                val score = scoreOf(vector, n, queryVector, queryNorm)
                if (score == Float.NEGATIVE_INFINITY || score < threshold) continue
                val candidate = CachedVector(
                    chunk.chunkId, chunk.docId, chunk.docTitle, chunk.content, vector, n, chunk.chunkIndex,
                ) to score
                allCandidates?.add(candidate) ?: bounded?.offer(candidate)
            }
            if (page.size < BATCH_SIZE) break
            offset += BATCH_SIZE
        }
        val candidates = allCandidates ?: bounded?.toList() ?: emptyList()
        return applyMMR(candidates, topK, mmrLambda)
    }

    /**
     * P2-33: 有界 top-K 候选池 — 与「全量候选 + 稳定降序排序 + take(topK)」结果严格一致。
     *
     * 只在「新分数严格大于当前最差」时替换最差项;平局保留更早插入者,且插入位置在
     * 所有同分项之后,因此输出顺序与稳定排序一致,内存占用固定为 O(topK)。
     */
    private class BoundedTopK(private val capacity: Int) {
        private val best = ArrayList<Pair<CachedVector, Float>>(capacity + 1)

        fun offer(item: Pair<CachedVector, Float>) {
            if (capacity <= 0) return
            if (best.size >= capacity) {
                val last = best.last()
                if (item.second <= last.second) return
                best.removeAt(best.size - 1)
            }
            var index = best.size
            while (index > 0 && best[index - 1].second < item.second) index--
            best.add(index, item)
        }

        fun toList(): List<Pair<CachedVector, Float>> = best
    }

    /** v1.133: 应用 MMR 多样性重排。candidates 已按 score 降序排好。 */
    private fun applyMMR(candidates: List<Pair<CachedVector, Float>>, topK: Int, mmrLambda: Float): List<SearchResult> {
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
        // P2-30: 限定检索范围的文档集合;null/空 = 全库
        scopeDocIds: List<String>?,
        metadataFilter: MetadataFilter?,
    ): List<SearchResult> {
        // 流式场景下 MMR 难以应用(需全量候选集算 sim),降级为纯相似度 + topK×5 候选再做 MMR
        val candidatePoolSize = if (mmrLambda < 1.0f) topK * 5 else topK
        val scopeSet = scopeDocIds?.toHashSet()?.takeIf { it.isNotEmpty() }
        val heap = PriorityQueue<Pair<CachedVector, Float>>(candidatePoolSize + 1) { a, b ->
            a.second.compareTo(b.second)
        }
        var offset = 0
        while (true) {
            val page = resultOf { chunkPageProvider(BATCH_SIZE, offset) }.getOrNull() ?: emptyList()
            if (page.isEmpty()) break
            for (chunk in page) {
                // P2-30: 流式分页同样按 scope 过滤,避免越界检索
                if (scopeSet != null && chunk.docId !in scopeSet) continue
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

    private fun scoreOf(vector: FloatArray, vectorNorm: Float, queryVector: FloatArray, queryNorm: Float): Float =
        if (vector.size != queryVector.size) {
            Float.NEGATIVE_INFINITY
        } else {
            dotProduct(vector, queryVector) / (vectorNorm * queryNorm)
        }

    private suspend fun getOrLoadCache(total: Int, scopeDocIds: List<String>?, metadataFilter: MetadataFilter?): List<CachedVector> {
        val filterKey = metadataFilter?.let {
            "${it.docIds.sorted().joinToString(",")}#${it.tag}#${it.startTime}#${it.endTime}" +
                "#${it.titleKeyword}#${it.sourceKeyword}"
        } ?: ""
        val key = (scopeDocIds?.sorted()?.joinToString(",") ?: "ALL") + "|" + filterKey
        // 命中已有条目（含 scope/过滤条件都一致的重复查询）
        synchronized(cacheEntries) { cacheEntries[key] }?.let { return it }
        return mutex.withLock {
            synchronized(cacheEntries) { cacheEntries[key] }?.let { return@withLock it }
            val chunks = chunkPageProvider(total, 0)
            // v1.133: 若有 scope,在内存中过滤(避免 provider 走全量)
            val filtered = if (scopeDocIds != null && scopeDocIds.isNotEmpty()) {
                chunks.filter { it.docId in scopeDocIds }
            } else {
                chunks
            }
            val filteredByMetadata = filtered.filter { chunkMatchesMetadata(it, metadataFilter) }
            val parsed = filteredByMetadata.mapNotNull { chunk ->
                val vector = parseEmbedding(chunk) ?: return@mapNotNull null
                val n = norm(vector)
                if (n == 0f) return@mapNotNull null
                CachedVector(chunk.chunkId, chunk.docId, chunk.docTitle, chunk.content, vector, n, chunk.chunkIndex)
            }
            synchronized(cacheEntries) { cacheEntries[key] = parsed }
            Logger.d("VectorSearchService", "加载 ${parsed.size} 条向量到缓存(scope=$key)")
            parsed
        }
    }

    /**
     * v1.133: 解析 embedding — BLOB 优先,JSON 兜底。
     */
    /** B4-03: metadata 内存过滤(SQL provider 未注入或缺字段时兜底)。 */
    private fun chunkMatchesMetadata(chunk: ChunkWithDoc, filter: MetadataFilter?): Boolean {
        if (filter == null || filter.isEmpty()) return true
        return filter.matches(chunk.docId, chunk.docTitle, chunk.metadataJson, chunk.createdAt)
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
        /** P2-33: 定向检索/元数据检索的分页批量(每次 provider 调用最多载入的 chunk 数)。 */
        internal const val BATCH_SIZE = 500
        private const val CACHE_THRESHOLD = 2000

        /** 向量缓存条目上限（见 cacheEntries 的取舍说明）。 */
        internal const val MAX_CACHE_ENTRIES = 2

        /** v1.133: FloatArray → ByteArray(BLOB 存储)。 */
        fun floatArrayToBlob(arr: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(arr.size * 4)
            buffer.asFloatBuffer().put(arr)
            return buffer.array()
        }
    }
}
