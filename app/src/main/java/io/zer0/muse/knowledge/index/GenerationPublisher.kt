package io.zer0.muse.knowledge.index

import io.zer0.common.Logger
import io.zer0.muse.data.knowledge.IndexGenerationState
import io.zer0.muse.data.knowledge.KnowledgeActiveGenerationEntity
import io.zer0.muse.data.knowledge.KnowledgeGenChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeGenLexicalEntity
import io.zer0.muse.data.knowledge.KnowledgeGenerationEntity
import io.zer0.muse.data.knowledge.KnowledgeJobDao
import io.zer0.muse.data.knowledge.KnowledgeTransactionRunner
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.domain.PublishResult
import io.zer0.muse.knowledge.ingest.LocatedChunk

/**
 * R4 GenerationPublisher — 索引代发布协议(设计 §8.3;计划 R4 §「建 staging generation…事务切换活动指针」)。
 *
 * 不变式(由 GenerationPublishTest 钉住):
 *  1. 新代以 STAGING 建立,保留旧活动代;暂存 chunk/词法行在任何校验通过前**不可被查询看见**
 *     (查询必须经活动代指针,而指针在事务切换前仍指向旧代)。
 *  2. 校验(数量/locator/hash/词法可查)在切换前完成;校验失败 -> [PublishResult.ValidationFailed],
 *     **活动指针不变**,旧代仍可查。
 *  3. 只有校验通过才在**同一事务**内切换活动指针;切换时把旧活动代置 RETIRED。
 *     事务内任一步失败 -> 整体回滚,旧指针与旧代状态不变。
 *  4. 失败/取消只清理新代(STAGING),绝不清理旧活动代;非当前指针的 ACTIVE 代(崩溃残留)
 *     允许被 [abandon] 清理。
 *
 * 事务边界由 [transactionRunner] 提供:生产装配传 `MuseDb`(见次要构造),
 * 旧测试仍可只传 DAO(无外层事务,单条写入)。
 */
class GenerationPublisher(
    private val jobDao: KnowledgeJobDao,
    private val transactionRunner: KnowledgeTransactionRunner = KnowledgeTransactionRunner.direct(),
) {

    /** 生产构造:用真实 Room 事务保证「状态切换 + 指针切换 + 旧代置 RETIRED」原子。 */
    constructor(db: MuseDb) : this(
        jobDao = db.knowledgeJobDao(),
        transactionRunner = KnowledgeTransactionRunner.room(db),
    )

    /**
     * 建立 staging generation 并写入 chunk + 词法行(此时代仍未可见)。
     *
     * 代号分配用「insert-or-ignore + 重算」而不是裸 `max+1`:两个 Worker 同时为同一文档
     * staging 时,后者撞唯一索引返回 -1 后取下一个号,不会把并发变成永久失败。
     *
     * @return staging generationId;调用方继续校验后再 [publish]。
     */
    suspend fun stageGeneration(
        docId: String,
        revisionId: String,
        chunks: List<LocatedChunk>,
        embeddingSpaceId: String = "",
        createdAt: Long = System.currentTimeMillis(),
    ): String = transactionRunner.run {
        val generationId = allocateGenerationId(
            docId = docId,
            revisionId = revisionId,
            chunkCount = chunks.size,
            embeddingSpaceId = embeddingSpaceId,
            createdAt = createdAt,
        )
        if (chunks.isNotEmpty()) {
            jobDao.insertGenChunks(
                chunks.mapIndexed { index, c ->
                    KnowledgeGenChunkEntity(
                        id = "$generationId#$index",
                        generationId = generationId,
                        chunkIndex = index,
                        content = c.text,
                        locator = c.locator,
                        kind = c.kind,
                        tokenCount = estimateTokens(c.text),
                    )
                },
            )
            jobDao.insertGenLexical(
                chunks.mapIndexed { index, c ->
                    KnowledgeGenLexicalEntity(
                        id = "$generationId#$index",
                        generationId = generationId,
                        chunkIndex = index,
                        lexicalText = normalizeLexical(c.text),
                    )
                },
            )
        }
        generationId
    }

    /**
     * 分配代号:insert-or-ignore 抢占 (docId, generation_no),冲突则重算下一个号。
     * 尝试次数有上界 —— 抢不到号说明库状态异常,必须显式失败而不是无限重试。
     */
    private suspend fun allocateGenerationId(
        docId: String,
        revisionId: String,
        chunkCount: Int,
        embeddingSpaceId: String,
        createdAt: Long,
    ): String {
        var attempt = 0
        while (attempt < MAX_ALLOCATION_ATTEMPTS) {
            val nextNo = jobDao.maxGenerationNo(docId) + 1
            val generationId = "gen-$docId-$nextNo"
            val rowId = jobDao.insertGenerationIfAbsent(
                KnowledgeGenerationEntity(
                    generationId = generationId,
                    docId = docId,
                    generationNo = nextNo,
                    state = IndexGenerationState.STAGING.name,
                    revisionId = revisionId,
                    embeddingSpaceId = embeddingSpaceId,
                    chunkCount = chunkCount,
                    createdAt = createdAt,
                ),
            )
            if (rowId != -1L) return generationId
            attempt++
        }
        throw IllegalStateException("代号分配失败(doc=$docId,尝试 $MAX_ALLOCATION_ATTEMPTS 次)")
    }

    /**
     * 校验 staging 代:数量一致、locator 非空、词法记录齐全、内容 hash 与 revision 一致。
     * @return null = 校验通过;非空 = 失败原因(调用方据此返回 ValidationFailed 并清理新代)。
     */
    suspend fun validate(
        generationId: String,
        expectedRevisionId: String,
        expectedContentHash: String,
        contentHashOfChunks: (List<String>) -> String,
    ): String? {
        val gen = jobDao.getGeneration(generationId) ?: return "generation 不存在: $generationId"
        val structuralError = validateGenerationShape(gen, generationId, expectedRevisionId)
        if (structuralError != null) return structuralError
        val chunks = jobDao.getGenChunks(generationId)
        if (chunks.any { it.locator.isBlank() }) return "存在无 locator 的 chunk"
        // 内容不可变校验:chunk 文本按序拼接(去 overlap 已由分块器保证语义)后 hash 必须匹配 revision。
        val actualHash = contentHashOfChunks(chunks.map { it.content })
        return if (actualHash != expectedContentHash) {
            "内容 hash 不一致: $actualHash != $expectedContentHash"
        } else {
            null
        }
    }

    /** 结构校验:代状态、revision 一致、chunk/词法行数一致。 */
    private suspend fun validateGenerationShape(
        gen: KnowledgeGenerationEntity,
        generationId: String,
        expectedRevisionId: String,
    ): String? {
        if (gen.state != IndexGenerationState.STAGING.name) return "generation 非 STAGING: ${gen.state}"
        if (gen.revisionId != expectedRevisionId) {
            return "revision 不匹配: ${gen.revisionId} != $expectedRevisionId"
        }
        val chunkCount = jobDao.countGenChunks(generationId)
        if (chunkCount != gen.chunkCount) return "chunk 数不一致: $chunkCount != ${gen.chunkCount}"
        val lexicalCount = jobDao.countGenLexical(generationId)
        return if (lexicalCount != chunkCount) "词法行数不一致: $lexicalCount != $chunkCount" else null
    }

    /**
     * 原子发布:在同一事务内完成「本代置 ACTIVE → 活动指针 upsert → 旧活动代置 RETIRED」。
     *
     * 事务内任一步失败即整体回滚:旧指针、旧代状态都不变;回滚后仅清理本次新代(仍是 STAGING),
     * 绝不触碰旧活动代。
     */
    suspend fun publish(generationId: String, expectedRevisionId: String): PublishResult {
        val outcome = try {
            transactionRunner.run<PublishResult> {
                val gen = jobDao.getGeneration(generationId)
                    ?: return@run PublishResult.ValidationFailed("generation 不存在: $generationId")
                if (gen.state != IndexGenerationState.STAGING.name) {
                    return@run PublishResult.ValidationFailed("generation 非 STAGING: ${gen.state}")
                }
                if (gen.revisionId != expectedRevisionId) {
                    return@run PublishResult.Conflict(
                        "generation revision(${gen.revisionId}) 与期望($expectedRevisionId) 不符,活动代不变",
                    )
                }
                val previousPointer = jobDao.getActiveGeneration(gen.docId)
                jobDao.updateGenerationState(generationId, IndexGenerationState.ACTIVE.name)
                jobDao.upsertActiveGeneration(
                    KnowledgeActiveGenerationEntity(
                        docId = gen.docId,
                        generationId = generationId,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                // 旧活动代退役:查询侧只认指针,状态同步为 RETIRED 以便协调器按保留期回收。
                if (previousPointer != null && previousPointer != generationId) {
                    jobDao.updateGenerationState(previousPointer, IndexGenerationState.RETIRED.name)
                }
                PublishResult.Published(generationId = generationId, docId = gen.docId)
            }
        } catch (e: Exception) {
            Logger.w("GenerationPublisher", "publish 事务失败,整体回滚(gen=$generationId): ${e.message}", e)
            cleanupAfterFailedPublish(generationId)
            return PublishResult.ValidationFailed("publish 异常: ${e.message}")
        }
        return outcome
    }

    /**
     * 事务回滚后的补偿清理:只有本代仍是 STAGING 才清理。
     * 若本代已是 ACTIVE(事务实际已提交),说明指针已切走,不得删行。
     */
    private suspend fun cleanupAfterFailedPublish(generationId: String) {
        // 读不到代行 = 事务已把它一并回滚,没有需要补偿的东西。
        val current = runCatching { jobDao.getGeneration(generationId) }.getOrNull() ?: return
        if (current.state != IndexGenerationState.STAGING.name) {
            Logger.w(
                "GenerationPublisher",
                "publish 失败后本代状态为 ${current.state},跳过清理(gen=$generationId)",
            )
            return
        }
        runCatching {
            transactionRunner.run {
                jobDao.updateGenerationState(generationId, IndexGenerationState.FAILED.name)
                jobDao.deleteGenChunks(generationId)
                jobDao.deleteGenLexical(generationId)
            }
        }.onFailure { t ->
            Logger.w("GenerationPublisher", "回退清理失败(gen=$generationId): ${t.message}", t)
        }
    }

    /**
     * R5 向量批次:把向量写入 staging 代的 chunk 行,并校验数量/维度/NaN/Infinity/零向量。
     * 任一条失败:清空本代已写向量并返回失败原因,**不标 vectorReady**,不改变活动指针。
     *
     * @return null = 全部写入且标记 vectorReady;非空 = 失败原因。
     */
    suspend fun stageVectors(generationId: String, vectors: List<FloatArray>, expectedDimension: Int, embeddingSpaceId: String): String? {
        val gen = jobDao.getGeneration(generationId) ?: return "generation 不存在: $generationId"
        val error = when {
            gen.state != IndexGenerationState.STAGING.name -> "generation 非 STAGING: ${gen.state}"
            else -> validateVectorPreconditions(generationId, vectors, embeddingSpaceId)
                ?: EmbeddingSpaceRegistry().validateVectorBatch(vectors, expectedDimension)
        }
        return if (error != null) {
            clearStagedVectors(generationId)
            error
        } else {
            transactionRunner.run {
                val rows = jobDao.getGenChunks(generationId)
                rows.forEachIndexed { index, row ->
                    jobDao.updateGenChunkEmbedding(row.id, vectors[index].toBytes())
                }
                jobDao.markVectorReady(generationId)
            }
            null
        }
    }

    /** 向量批次前置校验:空间 id、向量数与 chunk 数一致。 */
    private suspend fun validateVectorPreconditions(generationId: String, vectors: List<FloatArray>, embeddingSpaceId: String): String? {
        if (embeddingSpaceId.isBlank()) return "embeddingSpaceId 为空"
        val chunkCount = jobDao.countGenChunks(generationId)
        return if (vectors.size != chunkCount) {
            "向量数(${vectors.size})与 chunk 数($chunkCount)不一致"
        } else {
            null
        }
    }

    /** 校验失败时清理本代已写入向量(本代仍为 staging,未发布)。 */
    private suspend fun clearStagedVectors(generationId: String) {
        jobDao.getGenChunks(generationId).forEach { row ->
            jobDao.updateGenChunkEmbedding(row.id, ByteArray(0))
        }
    }

    /**
     * R5 HybridReady 原子发布:要求词法已完整可查 + 向量批已验证就绪,才切活动代。
     * vectorReady=false 时返回 ValidationFailed,**绝不冒充 HybridReady**。
     */
    suspend fun publishHybrid(generationId: String, expectedRevisionId: String, embeddingSpaceId: String): PublishResult {
        val gen = jobDao.getGeneration(generationId)
            ?: return PublishResult.ValidationFailed("generation 不存在: $generationId")
        val rejection: PublishResult? = when {
            gen.state != IndexGenerationState.STAGING.name ->
                PublishResult.ValidationFailed("generation 非 STAGING: ${gen.state}")
            jobDao.isVectorReady(generationId) != 1 ->
                PublishResult.ValidationFailed("向量批未就绪,不得发布 HybridReady")
            gen.embeddingSpaceId.isNotBlank() && gen.embeddingSpaceId != embeddingSpaceId ->
                PublishResult.Conflict("空间身份不一致: ${gen.embeddingSpaceId} != $embeddingSpaceId")
            else -> null
        }
        return rejection ?: publish(generationId, expectedRevisionId)
    }

    /**
     * 失败/取消时清理新代(STAGING)。
     *
     * 当前活动指针指向的代一律拒绝;若本代状态为 ACTIVE 但**不是**当前指针(发布后指针被
     * 后续发布替换、或崩溃留下的陈旧 ACTIVE),允许清理 —— 它已不服务任何查询。
     */
    suspend fun abandon(generationId: String) {
        val gen = jobDao.getGeneration(generationId) ?: return
        val pointer = jobDao.getActiveGeneration(gen.docId)
        if (gen.state == IndexGenerationState.ACTIVE.name && pointer == generationId) {
            // 正在服务的活动代不允许通过 abandon 清理(防止误删)。
            Logger.w("GenerationPublisher", "拒绝 abandon 当前活动代(gen=$generationId)")
            return
        }
        transactionRunner.run {
            jobDao.updateGenerationState(generationId, IndexGenerationState.FAILED.name)
            jobDao.deleteGenChunks(generationId)
            jobDao.deleteGenLexical(generationId)
        }
    }

    companion object {
        /**
         * 代号抢占的最大尝试次数:超过说明库状态异常,显式失败而非无限重试。
         * 并发 N 个 Worker 时最后一个最多需要 N 次重算,留足余量。
         */
        private const val MAX_ALLOCATION_ATTEMPTS = 16
        private val WHITESPACE_REGEX = Regex("\\s+")

        /** 词法规范化:小写 + 空白折叠。与 R6 真实 FTS4 规范化的版本化契约(此处为 R4 判定面)。 */
        fun normalizeLexical(text: String): String = text.lowercase().replace(WHITESPACE_REGEX, " ").trim()

        /** 粗估 token(按字符数/4,与既有 TextChunker 口径一致;真实计数归 R5/R6)。 */
        fun estimateTokens(text: String): Int = (text.length + 3) / 4

        /** FloatArray → 字节(BIG_ENDIAN,与既有 knowledge_chunks.embedding_blob 口径一致)。 */
        fun FloatArray.toBytes(): ByteArray {
            val buffer = java.nio.ByteBuffer.allocate(size * 4).order(java.nio.ByteOrder.BIG_ENDIAN)
            for (value in this) buffer.putFloat(value)
            return buffer.array()
        }
    }
}
