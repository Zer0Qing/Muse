package io.zer0.muse.knowledge

import io.zer0.muse.rag.VectorSearchService

/**
 * R0 基线回归夹具 —— 全部为手工合成的内存数据,不含任何用户私有正文或秘密。
 *
 * 只供测试调用;生产类不得依赖本对象(测试专用工具,不是生产 API)。
 * 两个知识库各一个块,向量与查询同向(余弦恒为 1.0),因此结果集合只由
 * scope / metadata 决定,不受相似度阈值干扰 —— 与 MetadataFilterSearchTest 同一手法。
 */
object KnowledgeRegressionFixtures {
    const val DIM = 4

    /** 所有块共用同一向量,且与查询向量同向 → 余弦相似度恒为 1.0。 */
    val sharedVector: FloatArray = FloatArray(DIM) { 0.5f }

    const val ALPHA_DOC = "doc-kb-alpha"
    const val BETA_DOC = "doc-kb-beta"
    const val LEGAL_TAG = "legal"

    data class FixtureChunk(
        val chunkId: String,
        val docId: String,
        val docTitle: String,
        val metadataJson: String,
    )

    /** 两个知识库各一个块,均带 legal tag —— beta 库用于「越界必须被剔除」样本。 */
    val twoLibraryChunks: List<FixtureChunk> = listOf(
        FixtureChunk("alpha-1", ALPHA_DOC, "Alpha 库文档", """{"tag":"legal","source":"upload/alpha"}"""),
        FixtureChunk("beta-1", BETA_DOC, "Beta 库文档", """{"tag":"legal","source":"upload/beta"}"""),
    )

    private fun toChunkWithDoc(c: FixtureChunk) = VectorSearchService.ChunkWithDoc(
        chunkId = c.chunkId,
        docId = c.docId,
        docTitle = c.docTitle,
        content = "content-${c.chunkId}",
        embedding = "",
        embeddingBlob = VectorSearchService.floatArrayToBlob(sharedVector),
        chunkIndex = 0,
        metadataJson = c.metadataJson,
        createdAt = 0L,
    )

    /**
     * 按生产 [io.zer0.muse.rag.RagService] 的接线方式构造 [VectorSearchService]:
     * 三个 provider 全部注入。只注入全量 provider 会走内存兜底路径,反而掩盖
     * 「metadata provider 路径忽略 scopeDocIds」的真实缺陷,故必须接齐三路。
     *
     * metadata provider 的签名只有 (filter, limit, offset),拿不到调用方另传的
     * scopeDocIds —— 这正是生产缺陷的根源:SQL 层只按 metadata 过滤,不感知范围。
     */
    fun vectorServiceLikeProduction(
        chunks: List<FixtureChunk> = twoLibraryChunks,
    ): VectorSearchService {
        val all = chunks.map(::toChunkWithDoc)
        return VectorSearchService(
            chunkPageProvider = { limit, offset -> all.drop(offset).take(limit) },
            chunkCountProvider = { all.size },
            chunkPageByDocIdsProvider = { docIds, limit, offset ->
                all.filter { it.docId in docIds }.drop(offset).take(limit)
            },
            chunkPageByMetadataProvider = { filter, limit, offset ->
                all.filter { filter.matches(it.docId, it.docTitle, it.metadataJson, it.createdAt) }
                    .drop(offset).take(limit)
            },
        )
    }
}
