package io.zer0.muse.data.knowledge

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.rag.RagConfig
import io.zer0.muse.rag.RagService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Seeds the hidden assistant-facing documentation used by knowledge_search.
 *
 * Startup kicks this off in the application scope, but the search tool can also
 * await the same idempotent operation so the first user request cannot race it.
 *
 * v2.4.x: 除 seed 元数据外，另提供 [ensureIndexed] 为内部文档补建向量索引。
 * 此前 devdoc 只写 knowledge_docs、不建 chunk，`knowledge_search(include_internal=true)`
 * 的向量优先路径对内置文档实际失效，只能退到关键词 LIKE。
 */
class BuiltInKnowledgeDocSeeder(
    private val context: Context,
    private val dao: KnowledgeDocDao,
    /** v2.4.x: 内部文档建索引用（可选；缺省时只 seed 不建索引）。 */
    private val ragService: RagService? = null,
    /** v2.4.x: 取当前 RAG 配置（返回 null 表示暂不可用，跳过索引）。 */
    private val ragConfigProvider: (suspend () -> RagConfig?)? = null,
) {
    private val seedMutex = Mutex()
    private val indexMutex = Mutex()
    private var seeded = false
    private var indexed = false

    /**
     * 幂等 seed：内容未变且已索引的文档直接跳过，避免每次启动重写并丢索引元数据。
     */
    suspend fun ensureSeeded() = seedMutex.withLock {
        if (seeded) return@withLock
        val names =
            context.assets.list(ASSET_DIR)
                ?.filter { it.endsWith(".md", ignoreCase = true) }
                .orEmpty()
        if (names.isEmpty()) {
            Logger.w(TAG, "内置功能文档目录为空，跳过 seed")
            return@withLock
        }
        val existing = resultOf { dao.getInternalDocs() }.getOrNull().orEmpty().associateBy { it.id }
        val now = System.currentTimeMillis()
        var failed = false
        names.forEach { name ->
            val seedResult = resultOf {
                val content =
                    context.assets.open("$ASSET_DIR/$name").use { input ->
                        input.bufferedReader().readText()
                    }
                val id = "devdoc-" + name.substringBeforeLast(".")
                val hash = RagService.computeContentHash(content)
                val previous = existing[id]
                // 内容未变且已有索引：保持现状（不重写，保住 chunk_count / embedding_model）
                if (previous == null || previous.contentHash != hash || previous.chunkCount <= 0) {
                    val title =
                        content.lineSequence()
                            .firstOrNull { it.startsWith("#") }
                            ?.removePrefix("#")
                            ?.trim()
                            ?.ifBlank { name.substringBeforeLast(".") }
                            ?: name.substringBeforeLast(".")
                    dao.upsert(
                        KnowledgeDocEntity(
                            id = id,
                            title = title,
                            content = content,
                            filePath = "assets/$ASSET_DIR/$name",
                            fileType = "devdoc",
                            isInternal = true,
                            createdAt = previous?.createdAt ?: now,
                            updatedAt = now,
                            chunkCount = 0,
                            embeddingModel = "",
                            contentHash = hash,
                        ),
                    )
                }
            }
            seedResult.onError { msg, error ->
                failed = true
                Logger.w(TAG, "seed 内置功能文档失败: $name: $msg", error)
            }
        }
        if (!failed) seeded = true
    }

    /**
     * v2.4.x: 为内部文档补建向量索引（增量、幂等、失败静默）。
     *
     * - 只处理未索引（chunkCount <= 0）或内容哈希不一致的文档；
     * - embedding 不可用/失败时保留关键词 LIKE 兜底，不影响检索可用性；
     * - 由启动流程后台调用，不阻塞 [ensureSeeded] 的等待方。
     */
    suspend fun ensureIndexed() = indexMutex.withLock {
        if (indexed) return@withLock
        val rag = ragService ?: return@withLock
        val provider = ragConfigProvider ?: return@withLock
        val config = resultOf { provider() }.getOrNull() ?: return@withLock
        val docs = resultOf { dao.getInternalDocs() }.getOrNull().orEmpty()
        val pending = docs.filter { doc -> needsIndexing(doc, config) }
        if (pending.isEmpty()) {
            indexed = true
            return@withLock
        }
        var failed = false
        pending.forEach { doc ->
            val chunks =
                resultOf { rag.indexDocument(doc.id, doc.content, config) }
                    .onError { msg, error ->
                        failed = true
                        Logger.w(TAG, "内部文档索引失败: ${doc.id}: $msg", error)
                    }
                    .getOrNull()
            if (chunks != null && chunks > 0) {
                dao.upsert(
                    doc.copy(
                        chunkCount = chunks,
                        embeddingModel = RagConfig.embeddingModelKey(config),
                        contentHash = RagService.computeContentHash(doc.content),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            } else if (chunks != null) {
                failed = true
                Logger.w(TAG, "内部文档分块为空: ${doc.id}")
            }
        }
        if (!failed) indexed = true
    }

    /**
     * 单文档是否需要（重新）索引。
     *
     * v2.6.0 (AUD-RAG-F1): 必须比对 embedding 模型指纹 —— 此前只看
     * chunkCount/contentHash，换 embedding 模型（尤其同维度）后新旧向量
     * 混存在同一文档下，检索质量静默劣化。
     */
    private fun needsIndexing(doc: KnowledgeDocEntity, config: RagConfig): Boolean {
        val fingerprintMatches =
            doc.embeddingModel.isNotBlank() &&
                doc.embeddingModel == RagConfig.embeddingModelKey(config)
        val contentMatches =
            doc.contentHash.isNotBlank() &&
                doc.contentHash == RagService.computeContentHash(doc.content)
        return doc.chunkCount <= 0 || !contentMatches || !fingerprintMatches
    }

    private companion object {
        const val ASSET_DIR = "devdocs"
        const val TAG = "BuiltInKnowledgeDocSeeder"
    }
}
