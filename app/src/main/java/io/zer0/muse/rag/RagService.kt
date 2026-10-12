package io.zer0.muse.rag

import io.zer0.ai.core.RagCitation
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.Perf
import io.zer0.common.resultOf
import io.zer0.muse.data.knowledge.KnowledgeChunkDao
import io.zer0.muse.data.knowledge.KnowledgeChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsRow
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsSelfHealer
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.data.session.MessageFtsManager
import io.zer0.muse.util.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.54: RAG 编排服务(分块 + embedding + 索引 + 检索 + 注入)。
 *
 * v1.133 改造:
 *  - embedding 存储:BLOB(替代 JSON,5× 加速)
 *  - FTS4 同步:索引文档时同步建立 FTS 索引,删文档时同步清理
 *  - 多知识库:retrieve 支持 [scopeDocIds](@mention 定向 / 助手绑定 KB)
 *  - 混合检索:[hybridSearchService] 提供 BM25+向量 RRF 融合
 *  - Rerank:[rerankProvider] 对 top-K×5 候选重排序
 *  - token 预算:[tokenBudget] 控制注入 token 数,与 webSearch 共享预算池
 *  - 引用返回:[buildInjectionContextWithCitations] 返回 [RagInjection](含 citations)
 */
@Suppress("LongParameterList", "LargeClass")
class RagService(
    private val chunkDao: KnowledgeChunkDao,
    private val docDao: KnowledgeDocDao,
    private val ftsDao: KnowledgeChunkFtsDao,
    private val docTitleProvider: suspend () -> Map<String, String>,
    private val embeddingService: EmbeddingService,
    /** v1.133: 混合检索服务(可选,仅 [RagConfig.hybridEnabled]=true 时启用)。 */
    private val hybridSearchService: HybridSearchService? = null,
    /** v1.133: Rerank Provider(可选,仅 [RagConfig.rerankEnabled]=true 时启用)。 */
    private val rerankProvider: RerankProvider? = null,
    /**
     * v1.134: 本地 ONNX Cross-Encoder Rerank Provider(可选)。
     *
     * 仅当 [RagConfig.rerankProvider] == "onnx" 时启用;模型不可用时降级到 [rerankProvider]。
     */
    private val onnxRerankProvider: OnnxRerankProvider? = null,
    /**
     * 向量检索无结果时的无作用域关键词兜底。结构化结果保留引用与过滤元数据。
     */
    private val keywordSearchFallback: (suspend (String, Int) -> List<KeywordFallbackResult>)? = null,
    /**
     * 作用域感知的关键词兜底；必须保持文档范围、内部文档排除和 metadataFilter 一致。
     */
    private val scopedKeywordSearchFallback: (
        suspend (String, Int, List<String>?, VectorSearchService.MetadataFilter?) -> List<KeywordFallbackResult>
    )? = null,
    /**
     * v1.55: HNSW 索引持久化文件路径(null = 不持久化,仅内存,App 重启后丢失)。
     *
     * 由 AppKoinModule 注入 `File(androidContext().filesDir, "rag/hnsw_index.bin")`。
     * App 启动时调用 [loadVectorIndexIfNeeded] 加载;新增 chunk 时增量更新 + 定期 save。
     */
    private val indexFile: File? = null,
    /** Cache directory used to stage a full source before replacing its current index. */
    private val reindexStagingDirectory: File? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val embedBatchSize = 50
    private val vectorIndexRebuildMarkerFile: File? = indexFile?.let { File(it.absolutePath + ".rebuild_required") }

    private fun persistVectorIndexRebuildMarker(reason: String) {
        val marker = vectorIndexRebuildMarkerFile ?: return
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(reason.take(512))
        }.onFailure { Logger.e("RagService", "写入 HNSW 重建标记失败: ${it.message}", it) }
    }

    /** HNSW is derived data; invalidation forces DB-vector retrieval until a complete reindex. */
    private fun markVectorIndexNeedsFullRebuild(reason: String) {
        vectorIndexNeedsFullRebuild = true
        pendingSaveCount.set(0)
        val marker = vectorIndexRebuildMarkerFile
        val markerWritten = if (marker == null) {
            false
        } else {
            persistVectorIndexRebuildMarker(reason)
            marker.exists()
        }
        if (!markerWritten) {
            // If marker persistence fails, a missing index plus remaining DB vectors forces rebuild on next boot.
            indexFile?.let { file ->
                if (file.exists() && !file.delete()) Logger.e("RagService", "无法使旧 HNSW 索引失效: ${file.absolutePath} ($reason)")
            }
        }
        Logger.w("RagService", "HNSW 标记为需全量重建: $reason")
    }

    /** R-DB-02: FTS 表缺失时先自愈重建再重试(批量插入在 Room 事务外调用,避免 DDL 死锁)。 */
    private suspend fun <T> withFtsSelfHeal(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: RuntimeException) {
            if (e.message?.contains("no such table: knowledge_chunks_fts") != true) throw e
            KnowledgeChunkFtsSelfHealer.repair()
            return block()
        }
    }

    // ── v1.55: HNSW 近似最近邻索引(大规模库 >=5000 chunk 时启用) ──
    /**
     * HNSW 向量索引实例(由 [VectorIndexFactory] 创建,默认 HNSW 策略)。
     *
     * - 检索时若 [vectorIndex].size > 0 且非混合检索/非定向检索 → 走 HNSW 路径
     * - 否则回退 [vectorSearch] 暴力遍历(向后兼容,<5000 chunk 的旧库仍可用)
     * - App 启动时通过 [loadVectorIndexIfNeeded] 从 [indexFile] 加载
     * - 新增 chunk 时增量 add;累计 [SAVE_INTERVAL] 次后自动 save
     */
    private val vectorIndex: VectorIndex? = VectorIndexFactory.createIndex(
        VectorIndexFactory.IndexStrategy.HNSW,
    )

    /**
     * chunkId → 元数据(用于 HNSW 检索结果回填 docId/docTitle/content/chunkIndex)。
     * B-35: 改为 LRU 上限缓存(access-order,命中提升,淘汰最久未用),上限 [MAX_CHUNK_META],
     * 防止大知识库全量 chunk content 常驻内存导致 OOM;未命中时 HNSW 结果整体降级到 vectorSearch。
     */
    private val chunkMetaCache = LruChunkMetaCache(MAX_CHUNK_META)

    /** HNSW 索引是否已从磁盘加载(避免每次检索都重复 load)。 */
    @Volatile
    private var vectorIndexLoaded = false
    private val vectorIndexMutex = kotlinx.coroutines.sync.Mutex()

    @Volatile
    private var vectorIndexFingerprint: String? = null

    @Volatile
    private var vectorIndexNeedsFullRebuild = false

    @Volatile
    private var vectorEmbeddingsNeedReindex = false

    /** 自首次 add 后累计的待保存计数,达到 [SAVE_INTERVAL] 时触发 [saveVectorIndex]。 */
    private val pendingSaveCount = AtomicInteger(0)

    /** v2.x: HNSW 落盘节流 — 距上次落盘不足 [SAVE_THROTTLE_MS] 时暂缓,防大文档每 50 块全量重写索引。 */
    private val lastVectorIndexSaveAt = java.util.concurrent.atomic.AtomicLong(0L)

    /** v1.55: chunk 元数据缓存条目(HNSW 检索结果回填用)。 */
    private data class ChunkMeta(
        val chunkId: String,
        val docId: String,
        val content: String,
        val chunkIndex: Int,
    )

    /**
     * B-35: 线程安全的 LRU chunk 元数据缓存,上限 [capacity] 条,防止全量 chunk content 常驻内存。
     * access-order 的 LinkedHashMap:get 命中会把条目提升为最近使用,插入满员时淘汰最久未用条目。
     * 所有可变/只读操作均持同一把锁,避免遍历(values)与写并发导致 ConcurrentModificationException。
     */
    private class LruChunkMetaCache(private val capacity: Int) {
        // accessOrder=true:get 命中提升为最近使用;满员时淘汰最久未用(keys 首项)。
        private val delegate = java.util.LinkedHashMap<String, ChunkMeta>(capacity, 0.75f, true)

        fun get(id: String): ChunkMeta? = synchronized(delegate) { delegate[id] }

        fun put(meta: ChunkMeta) {
            synchronized(delegate) {
                delegate[meta.chunkId] = meta
                while (delegate.size > capacity) {
                    val eldest = delegate.keys.first()
                    delegate.remove(eldest)
                }
            }
        }

        fun remove(id: String) {
            synchronized(delegate) { delegate.remove(id) }
        }

        fun clear() {
            synchronized(delegate) { delegate.clear() }
        }

        fun size(): Int = synchronized(delegate) { delegate.size }

        fun asList(): List<ChunkMeta> = synchronized(delegate) { delegate.values.toList() }
    }

    // v1.0.47: embedding 熔断器 — embed 失败后 5 分钟内 retrieve 直接返回空,
    //   让 buildInjectionContextWithCitations 自然降级到 keywordSearchFallback(本地数据库搜索),
    //   避免每次对话都打 404 浪费 60-200ms + 刷屏 + 触发商汤 rpm 限制。
    //   5 分钟后自愈:重新尝试 embed,成功则清熔断,失败则续 5 分钟。
    @Volatile
    private var embeddingFailureUntil: Long = 0L

    // v1.133: docTitle TTL 缓存 — 避免每次检索都查全表(原 docTitleProvider 每次 observeAll().first())
    // 5 分钟过期,文档增删后最多 5 分钟同步;indexDocument/deleteDocIndex 时主动失效
    @Volatile
    private var cachedTitles: Map<String, String>? = null

    @Volatile
    private var cachedTitlesAt: Long = 0
    private val titlesMutex = kotlinx.coroutines.sync.Mutex()

    /** v1.133: 带缓存的 docTitle 获取 — 5 分钟 TTL,失效后重新加载。 */
    private suspend fun getCachedTitles(): Map<String, String> {
        val now = System.currentTimeMillis()
        cachedTitles?.let {
            if (now - cachedTitlesAt < TITLES_TTL_MS) return it
        }
        return titlesMutex.withLock {
            // 双重检查,避免并发时重复加载
            cachedTitles?.let {
                if (now - cachedTitlesAt < TITLES_TTL_MS) return@withLock it
            }
            val titles = resultOf { docTitleProvider() }
                .onError { msg, e -> Logger.w("RagService", "docTitle 加载失败: $msg", e) }
                .getOrNull() ?: emptyMap()
            cachedTitles = titles
            cachedTitlesAt = System.currentTimeMillis()
            titles
        }
    }

    /** v1.133: 主动失效 docTitle 缓存(文档增删后调用)。 */
    private fun invalidateTitlesCache() {
        cachedTitles = null
        cachedTitlesAt = 0
    }

    // ── v1.55: HNSW 索引生命周期管理 ──

    /**
     * v1.55: App 启动后调用 — 从 [indexFile] 加载 HNSW 索引,并从 DB 重建 [chunkMetaCache]。
     *
     * 幂等:多次调用只首次实际加载,后续直接返回。
     * 即使 [indexFile] 为 null 或加载失败,也会重建 chunkMetaCache(保证后续 HNSW 检索可用)。
     */
    /** Invalidate derived HNSW state after backup restore; DB embeddings remain authoritative. */
    suspend fun invalidateAfterRestore() {
        vectorIndexMutex.withLock {
            vectorIndex?.clear()
            chunkMetaCache.clear()
            pendingSaveCount.set(0)
            lastVectorIndexSaveAt.set(0L)
            vectorIndexLoaded = true
            vectorIndexFingerprint = null
            vectorIndexNeedsFullRebuild = true
            persistVectorIndexRebuildMarker("backup restore")
            vectorEmbeddingsNeedReindex = false
            indexFile?.let { file ->
                if (file.exists() && !file.delete()) {
                    Logger.w("RagService", "备份恢复后删除旧 HNSW 索引失败: ${file.absolutePath}")
                }
            }
        }
        vectorSearch.invalidateCache()
        invalidateTitlesCache()
    }

    suspend fun loadVectorIndexIfNeeded() {
        loadVectorIndexIfNeeded(embeddingService.getProvider(RagConfig()))
    }

    suspend fun loadVectorIndexIfNeeded(ragConfig: RagConfig) {
        loadVectorIndexIfNeeded(embeddingService.getProvider(ragConfig))
    }

    private suspend fun loadVectorIndexIfNeeded(provider: EmbeddingProvider) {
        val vi = vectorIndex ?: return
        val expectedFingerprint = embeddingFingerprint(provider)
        if (vectorIndexLoaded && vectorIndexFingerprint == expectedFingerprint) return
        vectorIndexMutex.withLock {
            if (vectorIndexLoaded && vectorIndexFingerprint == expectedFingerprint) return@withLock
            val previousFingerprint = vectorIndexFingerprint
            val fingerprintChanged = previousFingerprint != null && previousFingerprint != expectedFingerprint
            if (fingerprintChanged) vectorEmbeddingsNeedReindex = true
            preservePendingIndexBeforeLoad(vi, expectedFingerprint)
            val file = indexFile
            val rebuildMarkerExists = vectorIndexRebuildMarkerFile?.exists() == true
            val loadResult = if (rebuildMarkerExists) {
                IndexLoadResult(loaded = false)
            } else {
                loadIndexFile(vi, file, expectedFingerprint)
            }
            if (!loadResult.loaded) {
                vi.clear()
                pendingSaveCount.set(0)
                val indexedCount = resultOf { chunkDao.countIndexed() }.getOrNull()
                if (rebuildMarkerExists || file?.exists() == true || fingerprintChanged || indexedCount == null || indexedCount > 0) {
                    vectorIndexNeedsFullRebuild = true
                }
                if (loadResult.fingerprintMismatch) vectorEmbeddingsNeedReindex = true
            } else {
                vectorIndexNeedsFullRebuild = false
            }
            vectorIndexFingerprint = expectedFingerprint
            // 无论 load 是否成功,都重建 chunkMetaCache(HNSW 检索结果回填需要)
            rebuildChunkMetaCache()
            vectorIndexLoaded = true
        }
    }

    private suspend fun preservePendingIndexBeforeLoad(vi: VectorIndex, expectedFingerprint: String) {
        if (pendingSaveCount.get() <= 0) return
        if (vectorIndexFingerprint == null || vectorIndexFingerprint == expectedFingerprint) {
            saveVectorIndex(expectedFingerprint)
        } else {
            vi.clear()
            pendingSaveCount.set(0)
            vectorIndexNeedsFullRebuild = true
        }
    }

    private data class IndexLoadResult(val loaded: Boolean, val fingerprintMismatch: Boolean = false)

    private fun loadIndexFile(vi: VectorIndex, file: File?, expectedFingerprint: String): IndexLoadResult {
        if (file == null || !file.exists()) return IndexLoadResult(loaded = false)
        var fingerprintMismatch = false
        val loaded = resultOf {
            vi.load(file, expectedFingerprint)
            true
        }
            .onSuccess { Logger.i("RagService", "HNSW 索引加载完成:size=${vi.size}") }
            .onError { msg, error ->
                fingerprintMismatch = error is HnswEmbeddingFingerprintMismatchException
                Logger.w("RagService", "HNSW 索引加载失败(将走暴力遍历): $msg", error)
            }
            .getOrNull() == true
        return IndexLoadResult(loaded, fingerprintMismatch)
    }

    /**
     * v1.55: 检索时调用 — 若索引未加载则触发加载,否则直接返回。
     *
     * 与 [loadVectorIndexIfNeeded] 的区别:此方法仅供 [retrieve] 内部使用,
     * 避免每次检索都做 volatile 读 + Mutex 检查的开销。
     */
    private suspend fun ensureVectorIndexLoaded() {
        if (vectorIndexLoaded) return
        loadVectorIndexIfNeeded()
    }

    /**
     * v1.55: 把 HNSW 索引持久化到 [indexFile]。
     *
     * 触发时机:
     *  - [addChunksToVectorIndex] 累计 [SAVE_INTERVAL] 次后自动触发
     *  - App 退出 / 后台时由调用方主动调用
     *  - [deleteDocIndex] 后由调用方主动调用(可选)
     */
    suspend fun saveVectorIndex(fingerprint: String? = vectorIndexFingerprint) {
        val vi = vectorIndex
        val file = indexFile
        if (vi == null || file == null) return
        if (vectorIndexNeedsFullRebuild || vectorEmbeddingsNeedReindex) {
            Logger.i("RagService", "Embedding 全库重新索引完成前，暂不持久化部分 HNSW 索引")
            return
        }
        resultOf {
            // 同步块内执行 IO(HNSW save 内部用读锁,不阻塞 search)
            vi.save(file, fingerprint)
        }.onSuccess {
            pendingSaveCount.set(0)
            lastVectorIndexSaveAt.set(System.currentTimeMillis())
            vectorIndexRebuildMarkerFile?.let { marker ->
                if (marker.exists() && !marker.delete()) Logger.w("RagService", "HNSW 标记文件清理失败: ${marker.absolutePath}")
            }
            Logger.d("RagService", "HNSW 索引已保存:size=${vi.size}, file=${file.absolutePath}")
        }.onError { msg, e ->
            Logger.w("RagService", "HNSW 索引保存失败: $msg", e)
        }
    }

    /**
     * v1.55: 从 DB 全量加载 chunk 元数据到 [chunkMetaCache]。
     *
     * 用于 [loadVectorIndexIfNeeded] 启动时构建缓存;失败时清空缓存(后续 HNSW 检索会因
     * chunkMetaCache 未命中而降级到 vectorSearch,不影响功能)。
     */
    private suspend fun rebuildChunkMetaCache() {
        val chunks = resultOf { chunkDao.getAllWithEmbedding() }
            .onError { msg, e -> Logger.w("RagService", "chunkMetaCache 重建失败: $msg", e) }
            .getOrNull() ?: emptyList()
        chunkMetaCache.clear()
        for (chunk in chunks) {
            chunkMetaCache.put(
                ChunkMeta(
                    chunkId = chunk.id,
                    docId = chunk.docId,
                    content = chunk.content,
                    chunkIndex = chunk.chunkIndex,
                ),
            )
        }
        Logger.d("RagService", "chunkMetaCache 重建完成:${chunkMetaCache.size()} 条")
    }

    /**
     * v1.55: 把新 chunk 加入 HNSW 索引 + chunkMetaCache。
     *
     * 失败不抛异常(索引更新失败不影响 DB 已写入的 chunk,下次 App 启动会从 DB 重建)。
     * 累计 [SAVE_INTERVAL] 次后触发 [saveVectorIndex] 持久化。
     */
    private suspend fun addChunksToVectorIndex(
        entities: List<KnowledgeChunkEntity>,
        embeddings: List<FloatArray>,
        provider: EmbeddingProvider,
    ) {
        val vi = vectorIndex ?: return
        loadVectorIndexIfNeeded(provider)
        var addFailed = false
        for ((idx, entity) in entities.withIndex()) {
            val vec = embeddings.getOrNull(idx) ?: continue
            var added = true
            resultOf { vi.add(entity.id, vec) }
                .onError { msg, e ->
                    added = false
                    Logger.w("RagService", "HNSW add 失败(chunkId=${entity.id}): $msg", e)
                }
            if (added) {
                chunkMetaCache.put(
                    ChunkMeta(
                        chunkId = entity.id,
                        docId = entity.docId,
                        content = entity.content,
                        chunkIndex = entity.chunkIndex,
                    ),
                )
            } else {
                addFailed = true
                chunkMetaCache.remove(entity.id)
            }
        }
        if (addFailed) {
            markVectorIndexNeedsFullRebuild("incremental add failed")
            vectorSearch.invalidateCache()
            return
        }
        val pending = pendingSaveCount.addAndGet(entities.size)
        if (pending >= SAVE_INTERVAL && System.currentTimeMillis() - lastVectorIndexSaveAt.get() >= SAVE_THROTTLE_MS) {
            saveVectorIndex()
        }
    }

    /**
     * v1.55: 从 HNSW 索引 + chunkMetaCache 移除指定 doc 的全部 chunk。
     *
     * P2-35: 原先只按 [chunkMetaCache](LRU,上限 [MAX_CHUNK_META]) 反查,大库中该 doc
     * 的 chunk 被 LRU 淘汰后漏删,HNSW 残留脏数据(检索仍返回已删内容)。改为以 DB 的
     * chunkId 全集为删除源(缓存只为清理内存表),并叠加缓存里 DB 已删的残留 id。
     * 调用方必须先于 DB 删除执行本方法(DB 是权威来源)。
     */
    private suspend fun removeDocChunksFromVectorIndex(docId: String) {
        val vi = vectorIndex ?: return
        var dbReadFailed = false
        val dbIds = resultOf { chunkDao.getByDoc(docId).map { it.id } }
            .onError { msg, e ->
                dbReadFailed = true
                Logger.e("RagService", "读取待删除文档的 chunkId 失败(docId=$docId): $msg", e)
            }
            .getOrNull()
        val cacheIds = chunkMetaCache.asList().filter { it.docId == docId }.map { it.chunkId }
        if (dbReadFailed || dbIds == null) {
            markVectorIndexNeedsFullRebuild("chunkId lookup failed for doc $docId")
            cacheIds.forEach(chunkMetaCache::remove)
            return
        }
        val idsToRemove = (dbIds + cacheIds).distinct()
        if (idsToRemove.isNotEmpty()) {
            var removeFailed = false
            for (id in idsToRemove) {
                resultOf { vi.remove(id) }
                    .onError { msg, e ->
                        removeFailed = true
                        Logger.e("RagService", "HNSW remove 失败(chunkId=$id): $msg", e)
                    }
                chunkMetaCache.remove(id)
            }
            if (removeFailed) markVectorIndexNeedsFullRebuild("incremental remove failed for doc $docId")
        }
    }

    private val vectorSearch = VectorSearchService(
        chunkPageProvider = { limit, offset ->
            val titles = getCachedTitles()
            chunkDao.getPageWithEmbedding(limit, offset).map { chunk ->
                VectorSearchService.ChunkWithDoc(
                    chunkId = chunk.id,
                    docId = chunk.docId,
                    docTitle = titles[chunk.docId] ?: "Unknown Document",
                    content = chunk.content,
                    embedding = chunk.embedding,
                    embeddingBlob = chunk.embeddingBlob,
                    chunkIndex = chunk.chunkIndex,
                    metadataJson = chunk.metadataJson,
                    createdAt = chunk.createdAt,
                )
            }
        },
        chunkCountProvider = { chunkDao.countIndexed() },
        chunkPageByDocIdsProvider = { docIds, limit, offset ->
            val titles = getCachedTitles()
            // v1.133: 按 docIds 过滤的候选集(@mention 定向检索用)
            // P2-33: 尊重 limit/offset(此前忽略分页参数、每次调用都全量载入 scope 内全部分块)
            val chunks = if (docIds.isNotEmpty()) chunkDao.getPageByDocIds(docIds, limit, offset) else emptyList()
            chunks.map { chunk ->
                VectorSearchService.ChunkWithDoc(
                    chunkId = chunk.id,
                    docId = chunk.docId,
                    docTitle = titles[chunk.docId] ?: "Unknown Document",
                    content = chunk.content,
                    embedding = chunk.embedding,
                    embeddingBlob = chunk.embeddingBlob,
                    chunkIndex = chunk.chunkIndex,
                    metadataJson = chunk.metadataJson,
                    createdAt = chunk.createdAt,
                )
            }
        },
        chunkPageByMetadataProvider = { filter, limit, offset ->
            val titles = getCachedTitles()
            chunkDao.getFilteredPageWithEmbedding(
                limit = limit,
                offset = offset,
                docIdsJson = AppJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(String.serializer()),
                    filter.docIds,
                ),
                tag = filter.tag,
                startTime = filter.startTime,
                endTime = filter.endTime,
            ).map { chunk ->
                VectorSearchService.ChunkWithDoc(
                    chunkId = chunk.id,
                    docId = chunk.docId,
                    docTitle = titles[chunk.docId] ?: "Unknown Document",
                    content = chunk.content,
                    embedding = chunk.embedding,
                    embeddingBlob = chunk.embeddingBlob,
                    chunkIndex = chunk.chunkIndex,
                    metadataJson = chunk.metadataJson,
                    createdAt = chunk.createdAt,
                )
            }
        },
    )

    /**
     * 索引文档:分块 + embedding + 存储 + FTS 同步。
     *
     * v2.x: 分块/HNSW 写入等 CPU 与磁盘操作在 [Dispatchers.Default] 执行,
     * 避免大文件导入在调用方线程(主线程)上阻塞界面。
     *
     * @return 分块数
     */
    suspend fun indexDocument(
        docId: String,
        content: String,
        ragConfig: RagConfig,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = indexDocumentInternal(
        docId,
        content,
        ragConfig,
        allowDimensionChange = false,
        onProgress = onProgress,
    )

    private suspend fun indexDocumentInternal(
        docId: String,
        content: String,
        ragConfig: RagConfig,
        allowDimensionChange: Boolean,
        onProgress: (current: Int, total: Int) -> Unit,
    ): Int = withContext(Dispatchers.Default) {
        if (content.isBlank()) return@withContext 0
        val perfTimer = Perf.start("rag-index-$docId")

        // 1. 分块(v1.133:支持 markdownAware / chunkByToken)
        val chunker = TextChunker(
            targetSize = ragConfig.chunkSize,
            overlap = ragConfig.chunkOverlap,
            markdownAware = ragConfig.markdownAware,
            chunkByToken = ragConfig.chunkByToken,
        )
        val chunks = chunker.split(content)
        if (chunks.isEmpty()) {
            if (allowDimensionChange && vectorIndexNeedsFullRebuild) clearDocumentChunks(docId)
            return@withContext 0
        }
        Logger.d(
            "RagService",
            "文档 $docId 分块 ${chunks.size} 块(markdownAware=${ragConfig.markdownAware}, chunkByToken=${ragConfig.chunkByToken})",
        )
        perfTimer.split("chunk")

        // 2. 批量 embedding
        val provider = embeddingService.getProvider(ragConfig)
        loadVectorIndexIfNeeded(provider)
        val embeddings = mutableListOf<FloatArray>()
        for (i in chunks.indices step embedBatchSize) {
            val batch = chunks.subList(i, minOf(i + embedBatchSize, chunks.size))
            val vectors = resultOf { provider.embed(batch.map { it.content }) }
                .onError { msg, e -> Logger.e("RagService", "Embedding 批次 $i 失败: $msg", e) }
                .getOrThrow()
            check(vectors.size == batch.size) {
                "Provider returned ${vectors.size} vectors for ${batch.size} chunks (batch $i)"
            }
            embeddings.addAll(vectors)
            onProgress(minOf(i + embedBatchSize, chunks.size), chunks.size)
        }
        perfTimer.split("embedding")

        // v1.133: embedding 维度一致性校验 — 新 embedding 维度必须与库中已有 chunk 维度一致
        // 维度不一致会导致向量检索 cosSim 永远为 0(被 size mismatch 拦截),知识库"看起来索引了但搜不到"。
        // 校验失败时抛异常,由上层 reindexAllInKbs/调用方记录为失败,不写入数据库。
        val newDim = embeddings.firstOrNull()?.size ?: 0
        if (newDim > 0) {
            val existingDim = resultOf { chunkDao.getFirstIndexedEmbeddingDim() }.getOrNull()
            if (shouldRejectDimensionChange(existingDim, newDim, allowDimensionChange)) {
                throwDimensionMismatch(docId, checkNotNull(existingDim), newDim)
            }
        }

        // 3. 首批 embedding + 维度检查已成功;从此任何失败都必须清理半索引状态。
        var destructiveStarted = false
        try {
            destructiveStarted = true
            removeDocChunksFromVectorIndex(docId)
            chunkDao.deleteByDoc(docId)
            withFtsSelfHeal { ftsDao.deleteByDoc(docId) }

            // 4. 存储 chunk + embedding(BLOB)
            val now = System.currentTimeMillis()
            val entities = createChunkEntities(docId, chunks, embeddings, chunker, now)
            chunkDao.insertAll(entities)

            // v1.55: 同步加入 HNSW 索引(增量更新)
            addChunksToVectorIndex(entities, embeddings, provider)

            // 5. FTS 同步索引(用于混合检索 BM25 路径)
            val ftsRows = entities.map {
                KnowledgeChunkFtsRow(
                    chunkId = it.id,
                    docId = it.docId,
                    content = MessageFtsManager.toNgram(it.content),
                )
            }
            resultOf { withFtsSelfHeal { ftsDao.insertAll(ftsRows) } }
                .onError { msg, e -> Logger.w("RagService", "FTS 同步失败(不影响向量检索): $msg", e) }

            perfTimer.split("store")
            vectorSearch.invalidateCache()
            invalidateTitlesCache()

            Logger.d("RagService", "文档 $docId 索引完成:${entities.size} 块,维度 ${embeddings.firstOrNull()?.size ?: 0}")
            perfTimer.end()
            return@withContext entities.size
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (destructiveStarted) cleanupFailedIndexReplacement(docId)
            throw cancelled
        } catch (error: Exception) {
            if (destructiveStarted) cleanupFailedIndexReplacement(docId)
            throw error
        }
    }

    private suspend fun cleanupFailedIndexReplacement(docId: String) = withContext(NonCancellable) {
        var cleanupFailed = false
        runCatching { removeDocChunksFromVectorIndex(docId) }
            .onFailure {
                cleanupFailed = true
                Logger.e("RagService", "索引替换失败后清理 HNSW 状态失败(docId=$docId)", it)
            }
        runCatching { withFtsSelfHeal { ftsDao.deleteByDoc(docId) } }
            .onFailure {
                cleanupFailed = true
                Logger.e("RagService", "索引替换失败后清理 FTS 失败(docId=$docId)", it)
            }
        runCatching { chunkDao.deleteByDoc(docId) }
            .onFailure {
                cleanupFailed = true
                Logger.e("RagService", "索引替换失败后清理 chunk 行失败(docId=$docId)", it)
            }
        runCatching { docDao.clearIndexStatus(docId) }
            .onFailure {
                cleanupFailed = true
                Logger.e("RagService", "索引替换失败后重置文档状态失败(docId=$docId)", it)
            }
        if (cleanupFailed) markVectorIndexNeedsFullRebuild("cleanup failed for doc $docId")
        vectorSearch.invalidateCache()
        invalidateTitlesCache()
    }

    /**
     * v2.x: 流式索引文档 — 分块/embedding/存储全程流式,内存占用 O(窗口) 而非 O(全文)。
     *
     * v2.x: 滑窗切分与 HNSW 批量写入在 [Dispatchers.Default] 执行,避免大文件导入阻塞主线程。
     *
     * 与 [indexDocument] 语义对齐(先清旧块再写新块 / 维度校验 / FTS 同步),差异在文本入口:
     * 文本以 [textPieces] 分片流式喂入,不需要把全文一次性载入内存,支持任意大小文件导入。
     *
     * 实现:滑窗摄取 — 缓冲区累积到 [windowChars] 后在安全边界(段落/换行)切窗,
     * 复用 [TextChunker] 逐窗分块;块攒批([embedBatchSize])嵌入后分批写入 chunk 表 / HNSW / FTS。
     *
     * 失败语义:
     *  - 首批 embedding 失败或维度不匹配:不清理旧块、零写入(与 [indexDocument] 一致);
     *  - 中途失败:已写入块保留(部分索引),重新导入时首批写入前会清理旧数据。
     *
     * @param textPieces 文本分片流(文件读取循环逐块 emit)
     * @param windowChars 滑窗大小(字符,默认 [STREAM_WINDOW_CHARS];测试可调小)
     * @param onProgress 已索引块数回调(每批一次)
     * @return 总索引块数
     */
    suspend fun indexDocumentStreamed(
        docId: String,
        textPieces: Flow<String>,
        ragConfig: RagConfig,
        windowChars: Int = STREAM_WINDOW_CHARS,
        onProgress: (indexedChunks: Int) -> Unit = {},
    ): Int = withContext(Dispatchers.Default) {
        val perfTimer = Perf.start("rag-index-stream-$docId")
        val effectiveWindow = windowChars.coerceAtLeast(1024)
        val chunker = TextChunker(
            targetSize = ragConfig.chunkSize,
            overlap = ragConfig.chunkOverlap,
            markdownAware = ragConfig.markdownAware,
            chunkByToken = ragConfig.chunkByToken,
        )
        val provider = embeddingService.getProvider(ragConfig)
        loadVectorIndexIfNeeded(provider)

        val buffer = StringBuilder()
        val pending = mutableListOf<TextChunker.Chunk>()
        var nextIndex = 0
        var totalIndexed = 0
        var initialized = false
        var destructiveStarted = false

        suspend fun cleanupPartialIndex() {
            if (!destructiveStarted) return
            runCatching { deleteDocIndex(docId) }
                .onFailure { Logger.e("RagService", "流式索引失败后的部分索引清理失败(docId=$docId)", it) }
            runCatching { docDao.clearIndexStatus(docId) }
                .onFailure { Logger.e("RagService", "流式索引失败后清理文档索引状态失败(docId=$docId)", it) }
        }

        suspend fun storeBatch(batch: List<TextChunker.Chunk>) {
            if (batch.isEmpty()) return
            val vectors = resultOf { provider.embed(batch.map { it.content }) }
                .onError { msg, e -> Logger.e("RagService", "流式索引 embedding 批次失败(已索引 $totalIndexed 块): $msg", e) }
                .getOrThrow()
            check(vectors.size == batch.size) {
                "Provider returned ${vectors.size} vectors for ${batch.size} chunks"
            }
            if (!initialized) {
                // 首批:维度一致性校验(与 indexDocument 相同口径;失败时不清理旧块、零写入)
                val newDim = vectors.firstOrNull()?.size ?: 0
                if (newDim > 0) {
                    val existingDim = resultOf { chunkDao.getFirstIndexedEmbeddingDim() }.getOrNull()
                    if (existingDim != null && existingDim > 0 && existingDim != newDim) {
                        Logger.e(
                            "RagService",
                            "Embedding 维度不匹配:库中已有 chunk 维度=$existingDim,新文档 $docId 维度=$newDim。",
                        )
                        throw IllegalStateException(
                            "Embedding dimension mismatch: existing=$existingDim, new=$newDim (doc=$docId). " +
                                "Run 'reindex all' with the same embedding model.",
                        )
                    }
                }
                // 首批 embedding 成功后才清理旧块(与 indexDocument「embedding 成功后删旧分块」对齐)。
                // 从此处开始，任何中途失败都必须清理本次部分索引，避免文档处于“半可检索”状态。
                destructiveStarted = true
                removeDocChunksFromVectorIndex(docId)
                chunkDao.deleteByDoc(docId)
                withFtsSelfHeal { ftsDao.deleteByDoc(docId) }
                initialized = true
            }
            val now = System.currentTimeMillis()
            val entities = batch.mapIndexed { i, chunk ->
                KnowledgeChunkEntity(
                    id = "chunk-$docId-${chunk.index}",
                    docId = docId,
                    content = chunk.content,
                    embedding = "",
                    embeddingBlob = VectorSearchService.floatArrayToBlob(vectors[i]),
                    chunkIndex = chunk.index,
                    tokenCount = chunker.estimateTokens(chunk.content),
                    metadataJson = encodeMetadata(chunk.metadata),
                    createdAt = now,
                )
            }
            chunkDao.insertAll(entities)
            addChunksToVectorIndex(entities, vectors, provider)
            val ftsRows = entities.map {
                KnowledgeChunkFtsRow(
                    chunkId = it.id,
                    docId = it.docId,
                    content = MessageFtsManager.toNgram(it.content),
                )
            }
            resultOf { withFtsSelfHeal { ftsDao.insertAll(ftsRows) } }
                .onError { msg, e -> Logger.w("RagService", "FTS 同步失败(不影响向量检索): $msg", e) }
            totalIndexed += entities.size
            onProgress(totalIndexed)
        }

        /** 切出一窗并分块入队;返回是否有窗口被消费。 */
        fun drainWindow(hard: Boolean): Boolean {
            if (buffer.isEmpty()) return false
            if (!hard && buffer.length < effectiveWindow) return false
            val limit = minOf(buffer.length, effectiveWindow)
            val cut = run {
                val para = buffer.lastIndexOf("\n\n", limit - 1)
                if (para >= limit / 2) {
                    para + 2
                } else {
                    val line = buffer.lastIndexOf("\n", limit - 1)
                    if (line >= limit / 2) line + 1 else limit
                }
            }
            val part = buffer.substring(0, cut)
            buffer.delete(0, cut)
            if (part.isNotBlank()) {
                for (chunk in chunker.split(part)) {
                    for (piece in splitOversizedPlainChunk(chunk, ragConfig.chunkSize, ragConfig.chunkOverlap)) {
                        pending.add(piece.copy(index = nextIndex++))
                    }
                }
            }
            return true
        }

        try {
            textPieces.collect { piece ->
                if (piece.isEmpty()) return@collect
                buffer.append(piece)
                while (drainWindow(hard = false)) {
                    while (pending.size >= embedBatchSize) {
                        val batch = pending.subList(0, embedBatchSize).toList()
                        pending.subList(0, embedBatchSize).clear()
                        storeBatch(batch)
                    }
                }
            }
            // 收尾:残余窗口循环切完(每次 drain 只消费一窗),块在下方按批写入
            while (drainWindow(hard = true)) {
                // 每轮消费一窗,直到缓冲区空
            }
            while (pending.size >= embedBatchSize) {
                val batch = pending.subList(0, embedBatchSize).toList()
                pending.subList(0, embedBatchSize).clear()
                storeBatch(batch)
            }
            if (pending.isNotEmpty()) {
                storeBatch(pending.toList())
                pending.clear()
            }

            if (totalIndexed > 0) {
                vectorSearch.invalidateCache()
                invalidateTitlesCache()
            }
            perfTimer.end()
            Logger.d("RagService", "文档 $docId 流式索引完成:$totalIndexed 块")
            return@withContext totalIndexed
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            cleanupPartialIndex()
            throw cancelled
        } catch (error: Exception) {
            cleanupPartialIndex()
            throw error
        }
    }

    /**
     * v2.x: 流式摄取保底切分 — 窗口内超长纯文本块二次切分。
     *
     * TextChunker 的 markdown-aware 模式按结构边界(标题/代码块/表格)切分,
     * 无 Markdown 结构的纯段落长文会被合并成单一巨块(见 TextChunkerTest
     * 「纯段落无边界时会合并到单块」)。巨块直接进入 embedding 会超模型上限、
     * 进入检索会拖垮注入质量,因此在流式摄取侧对超过 2×targetSize 的纯文本块
     * 做保底硬切(切成 targetSize 量级,保留 overlap)。
     *
     * 代码块/表格(metadata type=code/table)尊重结构语义,保持整体不切。
     */
    private fun splitOversizedPlainChunk(chunk: TextChunker.Chunk, targetSize: Int, overlap: Int): List<TextChunker.Chunk> {
        val type = chunk.metadata["type"]
        if (type == "code" || type == "table") return listOf(chunk)
        val limit = targetSize * 2
        val text = chunk.content
        if (text.length <= limit) return listOf(chunk)
        val pieces = mutableListOf<TextChunker.Chunk>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + targetSize, text.length)
            pieces.add(chunk.copy(content = text.substring(start, end)))
            if (end >= text.length) break
            start = (end - overlap).coerceAtLeast(start + 1)
        }
        return pieces
    }

    /**
     * v1.133: 检索 — 支持混合检索 / MMR / scopeDocIds。
     *
     * v1.55: 大规模库(>=5000 chunk)优先走 HNSW 近似最近邻;
     *       非混合 / 非定向检索场景下若 [vectorIndex] 有数据则用 HNSW,否则回退暴力遍历。
     *
     * @param scopeDocIds 限定检索范围(@mention 定向检索 / 助手绑定 KB 用)
     * @param metadataFilter 元数据过滤条件(docIds/标题/来源/tag/时间范围,可选,null = 不过滤);
     *   同时作用于混合检索(向量+BM25)与纯向量检索两条链路。
     */
    suspend fun retrieve(
        query: String,
        topK: Int,
        threshold: Float,
        ragConfig: RagConfig,
        scopeDocIds: List<String>? = null,
        metadataFilter: VectorSearchService.MetadataFilter? = null,
    ): List<VectorSearchService.SearchResult> {
        if (query.isBlank()) return emptyList()
        val start = System.currentTimeMillis()
        // v1.0.47: 熔断期内直接返回空,让上层走 keywordSearchFallback(本地数据库搜索),
        //   不再调 embed 触发 404。5 分钟后自愈。
        if (System.currentTimeMillis() < embeddingFailureUntil) {
            Perf.log("rag-retrieve", System.currentTimeMillis() - start)
            return emptyList()
        }
        val provider = embeddingService.getProvider(ragConfig)
        val queryVector: FloatArray = resultOf { provider.embed(query) }
            .onError { msg, e ->
                Logger.w("RagService", "Query embedding 失败: $msg", e)
                // v1.0.47: 设熔断 5 分钟,期间 retrieve 直接返回空 → 上层降级本地搜索
                embeddingFailureUntil = System.currentTimeMillis() + EMBEDDING_CIRCUIT_BREAKER_MS
                Logger.i("RagService", "embedding 熔断 ${EMBEDDING_CIRCUIT_BREAKER_MS / 1000}s,期间检索降级到本地关键词")
            }
            .getOrNull()
            ?: return emptyList<VectorSearchService.SearchResult>().also { Perf.log("rag-retrieve", System.currentTimeMillis() - start) }
        // v1.0.47: embed 成功 → 清熔断(自愈),下次恢复走正常向量检索
        embeddingFailureUntil = 0L

        if (vectorEmbeddingsNeedReindex) {
            Logger.w("RagService", "Embedding 模型与已存储向量不一致，等待全库重新索引后再恢复向量检索")
            return emptyList()
        }

        // v1.133: 混合检索路径
        if (ragConfig.hybridEnabled && hybridSearchService != null) {
            val hybrid = resultOf {
                hybridSearchService.hybridSearch(
                    query = query,
                    queryVector = queryVector,
                    topK = topK,
                    threshold = threshold,
                    mmrLambda = ragConfig.mmrLambda,
                    bm25Weight = ragConfig.hybridBm25Weight,
                    vectorWeight = ragConfig.hybridVectorWeight,
                    scopeDocIds = scopeDocIds,
                    metadataFilter = metadataFilter,
                )
            }.onError { msg, e -> Logger.w("RagService", "混合检索失败,降级向量: $msg", e) }
                .getOrNull()
            if (hybrid != null) {
                Perf.log("rag-retrieve-hybrid", System.currentTimeMillis() - start)
                val mapped = hybrid.map {
                    VectorSearchService.SearchResult(
                        docId = it.docId,
                        docTitle = it.docTitle,
                        chunkContent = it.chunkContent,
                        score = it.rrfScore,
                        chunkIndex = it.chunkIndex,
                        chunkId = it.chunkId,
                    )
                }
                // v1.133: 回填 isInternal 字段(从 KnowledgeDocEntity 批量查询,统一替代 docId 前缀硬编码)
                return applyIsInternal(mapped)
            }
        }

        // v1.55: HNSW 路径(大规模库 + 非定向检索)
        // 条件:vectorIndex 有数据 + 无 scopeDocIds(scope 场景候选集小,暴力遍历足够)
        //      + 无 metadataFilter:HNSW 结果不带元数据,无法过滤;过滤开启时必须落到
        //        vectorSearch(其内部支持 metadataFilter),避免过滤被静默忽略。
        // 注意:混合检索失败 fallback 时也会走到这里 — HNSW 比 brute-force 快得多
        val vi = vectorIndex
        if (vi != null && scopeDocIds.isNullOrEmpty() && (metadataFilter == null || metadataFilter.isEmpty())) {
            loadVectorIndexIfNeeded(provider)
            if (!vectorIndexNeedsFullRebuild && vi.size > 0) {
                val hnswResults = resultOf { vi.search(queryVector, topK) }
                    .onError { msg, e -> Logger.w("RagService", "HNSW 检索失败,降级暴力遍历: $msg", e) }
                    .getOrNull()
                if (hnswResults != null && hnswResults.isNotEmpty()) {
                    val titles = getCachedTitles()
                    val mapped = hnswResults.mapNotNull { r ->
                        // chunkMetaCache 命中 → 直接回填;未命中(罕见,索引与 DB 不一致)→ 跳过
                        val meta = chunkMetaCache.get(r.id) ?: return@mapNotNull null
                        VectorSearchService.SearchResult(
                            docId = meta.docId,
                            docTitle = titles[meta.docId] ?: "Unknown Document",
                            chunkContent = meta.content,
                            score = r.score,
                            chunkIndex = meta.chunkIndex,
                            chunkId = meta.chunkId,
                        )
                    }.filter { it.score >= threshold }
                    if (mapped.isNotEmpty()) {
                        Perf.log("rag-retrieve-hnsw", System.currentTimeMillis() - start)
                        // v1.133: 回填 isInternal 字段
                        return applyIsInternal(mapped)
                    }
                    // HNSW 结果为空(可能全部低于阈值或 chunkMetaCache 全未命中)→ 落到 vectorSearch
                }
            }
        }

        val results = vectorSearch.search(
            queryVector = queryVector,
            topK = topK,
            threshold = threshold,
            mmrLambda = ragConfig.mmrLambda,
            scopeDocIds = scopeDocIds,
            metadataFilter = metadataFilter,
        )
        Perf.log("rag-retrieve", System.currentTimeMillis() - start)
        // v1.133: 回填 isInternal 字段
        return applyIsInternal(results)
    }

    /**
     * v1.133: 批量回填 [VectorSearchService.SearchResult.isInternal] 字段。
     *
     * 从 [KnowledgeDocDao.getByIds] 查询各 docId 对应的 isInternal 标记,然后 copy 到每条
     * SearchResult 上。统一替代原 `docId.startsWith("devdoc-")` 硬编码判断。
     *
     * 查询失败或文档实体缺失时返回空列表，fail-closed，避免内部文档或孤儿 chunk
     * 在元数据不可用时进入用户上下文。
     */
    private suspend fun applyIsInternal(results: List<VectorSearchService.SearchResult>): List<VectorSearchService.SearchResult> {
        if (results.isEmpty()) return results
        val docIds = results.map { it.docId }.filter { it.isNotEmpty() }.distinct()
        if (docIds.isEmpty()) return results
        val docs = resultOf { docDao.getByIds(docIds) }
            .onError { msg, e -> Logger.w("RagService", "文档元数据批量查询失败: $msg", e) }
            .getOrNull()
            ?: return emptyList() // Fail closed: metadata failure must not expose internal documents as user content.
        if (docs.isEmpty()) return emptyList()
        val isInternalMap = docs.associate { it.id to it.isInternal }
        // chunk/HNSW/FTS 中残留但文档实体已删除的记录不得继续进入上下文。
        return results
            .filter { it.docId in isInternalMap }
            .map { r -> r.copy(isInternal = isInternalMap[r.docId] == true) }
    }

    /**
     * v1.54: 构建注入文本(向后兼容)。新代码应使用 [buildInjectionContextWithCitations]。
     */
    suspend fun buildInjectionContext(query: String, ragConfig: RagConfig): String =
        buildInjectionContextWithCitations(query, ragConfig, scopeDocIds = null).text

    /**
     * v1.133: 构建注入 — 返回 [RagInjection](含文本 + 引用列表)。
     *
     * 支持:
     *  - 混合检索 / MMR / scopeDocIds
     *  - Rerank(对 top-K×5 候选重排序)
     *  - Token 预算控制(按相似度降序累加,超预算即停)
     *  - 引用列表(供 ChatUI 渲染)
     *
     * @param scopeDocIds 限定检索范围(@mention 定向检索用)
     * @param metadataFilter 元数据过滤条件(可选,null = 不过滤);由 [retrieve] 向下透传,
     *   向量与 BM25 两条链路都生效。
     */
    suspend fun buildInjectionContextWithCitations(
        query: String,
        ragConfig: RagConfig,
        scopeDocIds: List<String>? = null,
        metadataFilter: VectorSearchService.MetadataFilter? = null,
    ): RagInjection {
        val start = System.currentTimeMillis()
        if (query.isBlank()) return RagInjection("", emptyList(), 0)

        // 1. 检索(扩大候选池到 topK×5 供 Rerank 用)
        val activeRerankProvider = resolveRerankProvider(ragConfig)
        val candidateK = if (ragConfig.rerankEnabled && activeRerankProvider != null) ragConfig.topK * 5 else ragConfig.topK
        // 内部文档只允许显式的 knowledge_search include_internal 路径使用，
        // 不得进入自动 system-prompt RAG 注入。
        var results = retrieve(query, candidateK, ragConfig.threshold, ragConfig, scopeDocIds, metadataFilter)
            .filterNot { it.isInternal }

        // v1.133: 缓存 docId → isInternal 映射(retrieve 已回填过),rerank 后需重新回填
        // (Rerank 路径会丢弃原 SearchResult 重建,rerank 结果不带 isInternal)
        val isInternalMap = results.associate { it.docId to it.isInternal }

        // 2. 重排序
        var matchType = if (ragConfig.hybridEnabled) "hybrid" else "vector"
        if (ragConfig.rerankEnabled && activeRerankProvider != null && results.isNotEmpty()) {
            val candidates = results.map {
                RerankCandidate(
                    chunkId = it.chunkId,
                    docId = it.docId,
                    docTitle = it.docTitle,
                    content = it.chunkContent,
                    chunkIndex = it.chunkIndex,
                )
            }
            val reranked = resultOf { activeRerankProvider.rerank(query, candidates, ragConfig.topK) }
                .onError { msg, e -> Logger.w("RagService", "Rerank 失败,降级原序: $msg", e) }
                .getOrNull()
            if (reranked != null) {
                results = reranked.map { r ->
                    VectorSearchService.SearchResult(
                        docId = r.docId,
                        docTitle = r.docTitle,
                        chunkContent = r.content,
                        score = r.score,
                        chunkIndex = r.chunkIndex,
                        chunkId = r.chunkId,
                        // v1.133: 保留 isInternal(retrieve 已查过,避免 rerank 重建丢失)
                        isInternal = isInternalMap[r.docId] ?: false,
                    )
                }
                matchType = if (ragConfig.rerankProvider == "onnx") "rerank-onnx" else "rerank"
            }
        } else {
            results = results.take(ragConfig.topK)
        }

        // 3. 构造文本 + 引用(应用 token 预算)
        if (results.isNotEmpty()) {
            val citations = mutableListOf<RagCitation>()
            val sb = StringBuilder()
            sb.appendLine("以下是从知识库检索到的相关资料,回答时请参考(相似度仅供参考):")
            var usedTokens = 0
            val budget = ragConfig.tokenBudget
            for ((idx, r) in results.withIndex()) {
                val chunkSnippet = r.chunkContent.take(200)
                // 注入只发送 snippet，预算也必须按实际注入文本计量，避免用整块 token 数误杀后续结果。
                val chunkTokens = TokenEstimator.estimate(chunkSnippet)
                // token 预算控制(0 = 不限制)
                if (budget > 0 && usedTokens + chunkTokens > budget) {
                    Logger.d("RagService", "Token 预算用尽(budget=$budget, used=$usedTokens, 剩余 ${results.size - idx} 条丢弃)")
                    break
                }
                usedTokens += chunkTokens
                sb.appendLine("[${idx + 1}] 来源: ${r.docTitle} (相似度 ${"%.2f".format(r.score)})")
                // Keep the actual injected text identical to the budgeted snippet.
                sb.appendLine(chunkSnippet)
                sb.appendLine()
                citations.add(
                    RagCitation(
                        index = idx + 1,
                        docId = r.docId,
                        docTitle = r.docTitle,
                        chunkId = r.chunkId,
                        chunkIndex = r.chunkIndex,
                        snippet = chunkSnippet,
                        score = r.score,
                        matchType = matchType,
                        // v1.133: 从 SearchResult 传入 isInternal(替代原 docId 前缀硬编码判断)
                        isInternal = r.isInternal,
                    ),
                )
            }
            return RagInjection(
                text = sb.toString().trimEnd(),
                citations = citations,
                elapsedMs = System.currentTimeMillis() - start,
            )
        }

        // 4. 向量检索无结果 → 关键词兜底(LIKE 子串匹配)
        val fallback = scopedKeywordSearchFallback ?: if (scopeDocIds == null) {
            keywordSearchFallback?.let { legacy ->
                { text: String, limit: Int, _: List<String>?, _: VectorSearchService.MetadataFilter? -> legacy(text, limit) }
            }
        } else {
            null
        } ?: return RagInjection("", emptyList(), System.currentTimeMillis() - start)
        val allowedDocIds = scopeDocIds?.toSet()
        val fallbackResults = resultOf { fallback(query, ragConfig.topK, scopeDocIds, metadataFilter) }
            .onError { msg, e -> Logger.w("RagService", "关键词兜底失败: $msg", e) }
            .getOrNull().orEmpty()
            // Defend at the final injection boundary too; a fallback provider must not be
            // able to leak internal or out-of-scope documents into automatic prompts.
            .filterNot { it.isInternal }
            .filter { allowedDocIds == null || it.docId in allowedDocIds }
            .filter {
                metadataFilter == null || metadataFilter.isEmpty() ||
                    metadataFilter.matches(it.docId, it.docTitle, it.metadataJson, it.createdAt)
            }
            .distinctBy { it.chunkId.ifBlank { "${it.docId}\u0000${it.docTitle}\u0000${it.snippet}" } }
            .take(ragConfig.topK)
        if (fallbackResults.isEmpty()) return RagInjection("", emptyList(), System.currentTimeMillis() - start)

        val citations = mutableListOf<RagCitation>()
        val sb = StringBuilder()
        sb.appendLine("以下是从知识库检索到的相关资料(关键词匹配),回答时请参考:")
        var usedTokens = 0
        val budget = ragConfig.tokenBudget
        for ((idx, result) in fallbackResults.withIndex()) {
            val snippet = result.snippet.take(200)
            val snippetTokens = TokenEstimator.estimate(snippet)
            if (budget > 0 && usedTokens + snippetTokens > budget) break
            usedTokens += snippetTokens
            sb.appendLine("[${idx + 1}] 来源: ${result.docTitle}")
            sb.appendLine(snippet)
            sb.appendLine()
            citations.add(
                RagCitation(
                    index = idx + 1,
                    docId = result.docId,
                    docTitle = result.docTitle,
                    chunkId = result.chunkId,
                    chunkIndex = result.chunkIndex,
                    snippet = snippet,
                    score = result.score,
                    matchType = "keyword_fallback",
                    isInternal = result.isInternal,
                ),
            )
        }
        return RagInjection(
            text = sb.toString().trimEnd(),
            citations = citations,
            elapsedMs = System.currentTimeMillis() - start,
        )
    }

    /** 删除文档的全部分块索引(含 FTS + HNSW)。 */
    suspend fun deleteDocIndex(docId: String) {
        require(docId.isNotBlank()) { "docId must not be blank" }
        // 先加载磁盘索引，确保删除也覆盖尚未进入内存的旧 HNSW 条目。
        ensureVectorIndexLoaded()
        // P2-35: 删除顺序改为「先清 HNSW(DB 权威取 chunkId 全集)→ 再删 DB/FTS」,
        // 保证大库中被 LRU 淘汰的 chunk 也能从索引删除,不残留。
        removeDocChunksFromVectorIndex(docId)
        // FTS 清理失败必须上抛：继续删除 chunk 会留下不可诊断的幽灵 FTS 命中。
        withFtsSelfHeal { ftsDao.deleteByDoc(docId) }
        chunkDao.deleteByDoc(docId)
        // HNSW 是派生索引；删除后立即落盘，避免进程重启从旧索引复活已删除文档。
        saveVectorIndex()
        vectorSearch.invalidateCache()
        invalidateTitlesCache()
    }

    /** 删除文档实体及其全部派生索引；UI/导入取消统一走此入口。 */
    suspend fun deleteDocument(docId: String) {
        deleteDocIndex(docId)
        docDao.delete(docId)
        invalidateTitlesCache()
    }

    private data class StagedReindexSource(val file: File, val preview: String, val contentHash: String, val charCount: Long)

    private suspend fun stageReindexSource(pieces: Flow<String>): StagedReindexSource = withContext(Dispatchers.IO) {
        val directory = reindexStagingDirectory ?: indexFile?.parentFile
            ?: error("RAG reindex staging directory is unavailable")
        if (!directory.exists() && !directory.mkdirs()) error("Cannot create RAG reindex staging directory")
        val staged = File.createTempFile("reindex-", ".txt", directory)
        val digest = MessageDigest.getInstance("SHA-256")
        val preview = StringBuilder()
        var charCount = 0L
        try {
            staged.outputStream().buffered().use { output ->
                pieces.collect { piece ->
                    if (piece.isEmpty()) return@collect
                    val bytes = piece.toByteArray(Charsets.UTF_8)
                    output.write(bytes)
                    digest.update(bytes)
                    if (preview.length < REINDEX_PREVIEW_CHARS) {
                        preview.append(piece.take(REINDEX_PREVIEW_CHARS - preview.length))
                    }
                    charCount += piece.length
                }
            }
            check(charCount > 0) { "Original document source is empty" }
            StagedReindexSource(
                file = staged,
                preview = preview.toString(),
                contentHash = digest.digest().joinToString("") { "%02x".format(it) },
                charCount = charCount,
            )
        } catch (error: Throwable) {
            staged.delete()
            throw error
        }
    }

    private fun stagedReindexFlow(file: File): Flow<String> = flow {
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(64 * 1024)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (count > 0) emit(String(buffer, 0, count))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * v1.133: 批量重新索引指定 KB 下的全部文档(用于 KB 管理页"重新索引全部"按钮)。
     *
     * 流程:
     *  1. 通过 [KnowledgeDocDao.getByKbIds] 取 KB 下全部文档
     *  2. 逐篇调用 [indexDocument](内部会先 deleteByDoc 再重建)
     *  3. 每篇完成后回调 [onProgress](current, total)
     *  4. 任意一篇失败不中断,继续下一篇,最终返回失败的 docId 列表
     *
     * @param kbIds 要重索引的 KB id 列表(空列表 = 不执行)
     * @param ragConfig 使用的 RAG 配置(分块参数/embedding 来源)
     * @param onProgress 进度回调,(current, total) — 1-based
     * @return 失败的 docId → 失败原因 Map
     */
    suspend fun reindexAllInKbs(
        kbIds: List<String>,
        ragConfig: RagConfig,
        sourceContentProvider: (suspend (KnowledgeDocEntity) -> Flow<String>?)? = null,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<String, String> {
        val visibleDocs =
            if (kbIds.isEmpty()) {
                null
            } else {
                resultOf { docDao.getByKbIds(kbIds) }
                    .onError { msg, e -> Logger.e("RagService", "KB 重索引:加载文档失败: $msg", e) }
                    .getOrNull()
            }
        if (visibleDocs == null) {
            if (kbIds.isNotEmpty()) Logger.w("RagService", "KB 重索引:无文档可处理")
            return emptyMap()
        }
        val provider = embeddingService.getProvider(ragConfig)
        loadVectorIndexIfNeeded(provider)
        val plan = createReindexPlan(kbIds, visibleDocs)
        val failures = mutableMapOf<String, String>()
        val total = plan.documents.size
        Logger.i("RagService", "KB 重索引开始:共 $total 篇文档(kbIds=$kbIds)")
        for ((idx, doc) in plan.documents.withIndex()) {
            try {
                reindexDocument(doc, ragConfig, plan.allowDimensionChange, sourceContentProvider)
            } catch (e: Throwable) {
                Logger.w("RagService", "KB 重索引:文档 ${doc.id} 失败: ${e.message}", e)
                failures[doc.id] = e.message ?: e.javaClass.simpleName
            }
            onProgress(idx + 1, total)
        }
        if (vectorIndexNeedsFullRebuild && plan.coversAllIndexedDocuments && failures.isEmpty()) {
            vectorIndexFingerprint = embeddingFingerprint(provider)
            vectorIndexNeedsFullRebuild = false
            vectorEmbeddingsNeedReindex = false
            saveVectorIndex(vectorIndexFingerprint)
        }
        Logger.i("RagService", "KB 重索引完成:成功 ${total - failures.size}/$total,失败 ${failures.size}")
        return failures
    }

    /**
     * 重新索引单个文档。
     *
     * [KnowledgeDocEntity.content] 可能只是预览；当 [contentHash] 与预览哈希不一致时，
     * 必须通过 [sourceContentProvider] 读取原始内容，禁止把预览覆盖成新的完整索引。
     */
    suspend fun reindexDocument(
        docId: String,
        ragConfig: RagConfig,
        sourceContentProvider: (suspend (KnowledgeDocEntity) -> Flow<String>?)? = null,
    ): Int {
        val document = docDao.getById(docId) ?: error("Knowledge document not found: $docId")
        val provider = embeddingService.getProvider(ragConfig)
        loadVectorIndexIfNeeded(provider)
        reindexDocument(
            document = document,
            ragConfig = ragConfig,
            allowDimensionChange = false,
            sourceContentProvider = sourceContentProvider,
        )
        return docDao.getById(docId)?.chunkCount ?: 0
    }

    private data class ReindexPlan(
        val documents: List<KnowledgeDocEntity>,
        val coversAllIndexedDocuments: Boolean,
        val allowDimensionChange: Boolean,
    )

    private suspend fun createReindexPlan(kbIds: List<String>, visibleDocs: List<KnowledgeDocEntity>): ReindexPlan {
        val targetKbIds = kbIds.toSet()
        val allDocsInTargetKbs = resultOf { docDao.getAll() }
            .getOrNull()
            ?.filter { it.kbId in targetKbIds }
        val indexedChunks = resultOf { chunkDao.getAllWithEmbedding() }.getOrNull()
        val targetDocIds = allDocsInTargetKbs?.map { it.id }?.toSet()
        val coversAllIndexedDocuments = indexedChunks != null &&
            targetDocIds != null &&
            indexedChunks.all { it.docId in targetDocIds }
        val documents = if (vectorIndexNeedsFullRebuild && coversAllIndexedDocuments) {
            allDocsInTargetKbs.orEmpty()
        } else {
            visibleDocs
        }
        val allowDimensionChange = coversAllIndexedDocuments &&
            (vectorEmbeddingsNeedReindex || vectorIndexNeedsFullRebuild)
        return ReindexPlan(documents, coversAllIndexedDocuments, allowDimensionChange)
    }

    private suspend fun reindexDocument(
        document: KnowledgeDocEntity,
        ragConfig: RagConfig,
        allowDimensionChange: Boolean,
        sourceContentProvider: (suspend (KnowledgeDocEntity) -> Flow<String>?)?,
    ) {
        val storedContentHash = computeContentHash(document.content)
        val inlineContentComplete = if (document.contentHash.isNotBlank()) {
            document.contentHash == storedContentHash
        } else {
            document.content.length < REINDEX_PREVIEW_CHARS
        }
        if (inlineContentComplete) {
            val chunkCount = if (document.content.isBlank()) {
                clearDocumentChunks(document.id)
                0
            } else {
                indexDocumentInternal(
                    document.id,
                    document.content,
                    ragConfig,
                    allowDimensionChange = allowDimensionChange,
                    onProgress = { _, _ -> },
                )
            }
            if (chunkCount > 0) {
                docDao.upsert(
                    document.copy(
                        chunkCount = chunkCount,
                        embeddingModel = RagConfig.embeddingModelKey(ragConfig),
                        contentHash = document.contentHash.ifBlank { storedContentHash },
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            } else {
                clearDocumentChunks(document.id)
            }
            return
        }

        // Document.content is a preview; stage the original source completely before the existing index is touched.
        val source = sourceContentProvider?.invoke(document)
            ?: error("Document source is truncated or unavailable; re-import the original file before reindexing")
        val staged = stageReindexSource(source)
        try {
            val chunkCount = indexDocumentStreamed(
                docId = document.id,
                textPieces = stagedReindexFlow(staged.file),
                ragConfig = ragConfig,
            )
            check(chunkCount > 0) { "Original source produced no indexable chunks" }
            docDao.upsert(
                document.copy(
                    content = staged.preview,
                    chunkCount = chunkCount,
                    embeddingModel = RagConfig.embeddingModelKey(ragConfig),
                    contentHash = staged.contentHash,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        } finally {
            staged.file.delete()
        }
    }

    private suspend fun clearDocumentChunks(docId: String) {
        removeDocChunksFromVectorIndex(docId)
        withFtsSelfHeal { ftsDao.deleteByDoc(docId) }
        chunkDao.deleteByDoc(docId)
        docDao.clearIndexStatus(docId)
        vectorSearch.invalidateCache()
        invalidateTitlesCache()
    }

    private fun createChunkEntities(
        docId: String,
        chunks: List<TextChunker.Chunk>,
        embeddings: List<FloatArray>,
        chunker: TextChunker,
        createdAt: Long,
    ): List<KnowledgeChunkEntity> = chunks.mapIndexed { index, chunk ->
        KnowledgeChunkEntity(
            id = "chunk-$docId-$index",
            docId = docId,
            content = chunk.content,
            embedding = "",
            embeddingBlob = VectorSearchService.floatArrayToBlob(embeddings[index]),
            chunkIndex = index,
            tokenCount = chunker.estimateTokens(chunk.content),
            metadataJson = encodeMetadata(chunk.metadata),
            createdAt = createdAt,
        )
    }

    private fun shouldRejectDimensionChange(existingDim: Int?, newDim: Int, allowDimensionChange: Boolean): Boolean {
        return when {
            allowDimensionChange -> false
            existingDim == null -> false
            existingDim <= 0 -> false
            else -> existingDim != newDim
        }
    }

    private fun throwDimensionMismatch(docId: String, existingDim: Int, newDim: Int): Nothing {
        Logger.e(
            "RagService",
            "Embedding 维度不匹配:库中已有 chunk 维度=$existingDim,新文档 $docId 维度=$newDim。" +
                "请到知识库管理页点击\"重新索引全部\",用同一 embedding 模型重建索引。",
        )
        throw IllegalStateException(
            "Embedding dimension mismatch: existing=$existingDim, new=$newDim (doc=$docId). " +
                "Run 'reindex all' with the same embedding model.",
        )
    }

    /**
     * R-DB-02/R-UI-01: 修复 knowledge_chunks_fts 并重索引全部用户文档。
     * 先清影子表重建 FTS,再按文档所属 KB 全量重索引;失败文档返回 map。
     */
    suspend fun repairKnowledgeFtsIndex(
        ragConfig: RagConfig,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<String, String> {
        KnowledgeChunkFtsSelfHealer.repair()
        val kbIds = resultOf { docDao.getAll() }
            .onError { msg, e -> Logger.e("RagService", "修复 FTS:加载文档失败: $msg", e) }
            .getOrNull()
            ?.map { it.kbId }
            ?.distinct()
            .orEmpty()
        return reindexAllInKbs(kbIds, ragConfig, onProgress = onProgress)
    }

    fun invalidateVectorCache() = vectorSearch.invalidateCache()

    suspend fun indexedChunkCount(): Int = chunkDao.countIndexed()

    /**
     * v1.134: 根据当前 [RagConfig] 解析实际使用的 Rerank Provider。
     *
     * - rerankEnabled=false → null(不重排)
     * - rerankProvider="onnx" + onnxRerankProvider 可用 → OnnxRerankProvider
     * - rerankProvider="onnx" + onnxRerankProvider 不可用 → 降级 rerankProvider(LocalRerankProvider)
     * - 其他 → rerankProvider(LocalRerankProvider)
     */
    private fun resolveRerankProvider(ragConfig: RagConfig): RerankProvider? {
        if (!ragConfig.rerankEnabled) return null
        if (ragConfig.rerankProvider == "onnx" && onnxRerankProvider != null && onnxRerankProvider.isAvailable()) {
            return onnxRerankProvider
        }
        return rerankProvider
    }

    /**
     * 指纹组成:provider id + 模型名 + 维度。
     *
     * v2.6.0 (AUD-RAG-F3): 维度改用 [EmbeddingProvider.fingerprintDimension] ——
     * [EmbeddingProvider.dimension] 在首次 embed 前可能是猜测值，与真实维度不一致
     * 会让指纹在「猜测→真实」之间跳变，每次冷启动都判定指纹变化并重建 HNSW 索引。
     */
    private fun embeddingFingerprint(provider: EmbeddingProvider): String =
        "${provider.id}\u0000${provider.modelName}\u0000${provider.fingerprintDimension}"

    /**
     * v1.133: 解析 @mention 文本为 docId 列表(@mention 定向检索用)。
     *
     * 流程:
     *  1. 从 [text] 中提取所有 @xxx 标记(去掉 @ 前缀)
     *  2. 对每个 mention 关键词调用 [KnowledgeDocDao.findByTitleKeyword] 模糊匹配
     *  3. 合并所有命中文档的 id(去重)
     *
     * @return 去重后的 docId 列表;空列表表示未命中任何文档(调用方应走全库检索)
     */
    suspend fun resolveMentionToDocIds(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val mentions = MENTION_REGEX.findAll(text).map { it.value.removePrefix("@").trim() }
            .filter { it.isNotBlank() }.toList()
        if (mentions.isEmpty()) return emptyList()
        val docIds = linkedSetOf<String>()
        for (mention in mentions) {
            val escaped = KnowledgeDocDao.escapeLikeQuery(mention)
            val docs = resultOf { docDao.findByTitleKeyword(escaped) }
                .onError { msg, e -> Logger.w("RagService", "@mention 解析失败 '$mention': $msg", e) }
                .getOrNull() ?: emptyList()
            docs.forEach { docIds.add(it.id) }
        }
        Logger.d("RagService", "@mention 解析: ${mentions.size} 个 mention → ${docIds.size} 个 docId")
        return docIds.toList()
    }

    /**
     * 把助手绑定的知识库 id 展开为可检索文档 id。
     * 空列表明确表示未绑定任何知识库,调用方应继续使用全局检索语义。
     */
    suspend fun resolveKnowledgeBaseDocIds(knowledgeBaseIds: List<String>): List<String> {
        val ids = knowledgeBaseIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyList()
        return resultOf { docDao.getByKbIds(ids).map { it.id }.distinct() }
            .onError { msg, throwable ->
                Logger.w("RagService", "助手绑定知识库展开失败: ${throwable?.message ?: msg}", throwable)
            }
            .getOrNull()
            ?: emptyList()
    }

    /**
     * v2.4.6: 按「知识库 + 文件夹路径」展开可检索文档 id。
     *
     * 世界观:知识库(KB)= 根,文件夹 = 根下的子目录。一个文件夹就是一个独立检索域,
     * [folderPath] 为 KB 内相对路径,空串表示整库。展开规则 = 路径前缀匹配:
     *  - 路径空 → 该 KB 全部用户文档;
     *  - 路径 `合同` → folderPath 为 `合同` 或 `合同/...` 的文档(含子目录)。
     * 这保证不同文件夹的检索结果互不串。
     *
     * @param scopes 形如 (kbId, folderPath) 的绑定列表
     * @return 命中的文档 id 集合;空集合表示未绑定(调用方回退全局检索)
     */
    suspend fun resolveKbFolderScopes(scopes: List<Pair<String, String>>): List<String> {
        val normalized = scopes
            .map { it.first.trim() to io.zer0.muse.rag.KnowledgeFolderBrowser.normalize(it.second) }
            .filter { it.first.isNotBlank() }
            .distinct()
        val kbIds = normalized.map { it.first }.distinct()
        val allDocs = if (kbIds.isEmpty()) {
            emptyList()
        } else {
            resultOf { docDao.getByKbIds(kbIds) }
                .onError { msg, throwable ->
                    Logger.w("RagService", "KB+文件夹作用域展开失败: ${throwable?.message ?: msg}", throwable)
                }
                .getOrNull()
                .orEmpty()
        }
        return if (normalized.isEmpty() || allDocs.isEmpty()) {
            emptyList()
        } else {
            allDocs.asSequence()
                .filter { doc ->
                    normalized.any { (kbId, path) ->
                        doc.kbId == kbId && pathHitsFolder(doc, path)
                    }
                }
                .map { it.id }
                .distinct()
                .toList()
        }
    }

    /** 判断文档是否落在某文件夹路径下(空路径 = 整库;否则路径前缀匹配,含子目录)。 */
    private fun pathHitsFolder(doc: KnowledgeDocEntity, path: String): Boolean {
        if (path.isEmpty()) return true
        val p = io.zer0.muse.rag.KnowledgeFolderBrowser.folderPath(doc)
        return p == path || p.startsWith("$path/")
    }

    /** v1.133: @mention 提取正则(与 ChatViewModel.KNOWLEDGE_MENTION_REGEX 同义,RagService 自用)。 */
    companion object {
        val MENTION_REGEX = Regex("@[^\\s@]+")

        /** docTitle 缓存 TTL:5 分钟(文档增删时主动失效,无需等过期)。 */
        const val TITLES_TTL_MS = 5L * 60 * 1000

        /** v1.55: HNSW 索引自动保存阈值(累计新增 SAVE_INTERVAL 个 chunk 后触发一次 save)。 */
        const val SAVE_INTERVAL = 50

        /** v2.x: HNSW 落盘最小间隔 — 即使达到 SAVE_INTERVAL,两次落盘间也不少于该间隔。 */
        const val SAVE_THROTTLE_MS = 15_000L

        /** v2.x: 流式索引滑窗大小(字符)。窗口越大内存峰值越高、窗口间切分越少。 */
        const val STREAM_WINDOW_CHARS = 200_000

        private const val REINDEX_PREVIEW_CHARS = 500_000

        /** B-35: chunkMetaCache LRU 上限条数(约 20MB 量级,防止全量 content 常驻内存)。 */
        const val MAX_CHUNK_META = 2000

        /** v1.0.47: embedding 熔断时长 — 失败后 5 分钟内 retrieve 直接返回空,降级本地搜索。 */
        const val EMBEDDING_CIRCUIT_BREAKER_MS = 5L * 60 * 1000

        /** v1.133: content SHA-256 used by all knowledge import entry points. */
        fun computeContentHash(content: String): String = resultOf {
            val md = MessageDigest.getInstance("SHA-256")
            md.digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: ""
    }

    /** v1.133: 把 chunk metadata Map 序列化为 JSON。 */
    private fun encodeMetadata(meta: Map<String, String>): String =
        resultOf { json.encodeToString(MapSerializer(String.serializer(), String.serializer()), meta) }
            .getOrNull() ?: "{}"
}
