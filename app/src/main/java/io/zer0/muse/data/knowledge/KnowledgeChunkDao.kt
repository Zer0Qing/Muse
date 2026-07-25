package io.zer0.muse.data.knowledge

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * v1.54: 知识库分块 Dao。
 *
 * v1.133 改造:
 *  - 新增 [getAllWithEmbeddingBlob] / [getPageWithEmbeddingBlob]:优先 BLOB,无 BLOB fallback JSON。
 *  - 新增 [getByDocIds]:按 docIds 过滤(助手绑定 KB / @mention 定向检索用)。
 *  - 检索方法不再过滤 internal(internal 过滤在 [KnowledgeDocDao] 层做)。
 */
@Dao
interface KnowledgeChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<KnowledgeChunkEntity>)

    @Query("SELECT * FROM knowledge_chunks WHERE doc_id = :docId ORDER BY chunk_index ASC")
    suspend fun getByDoc(docId: String): List<KnowledgeChunkEntity>

    /**
     * v1.133: 加载全部已生成 embedding 的分块(BLOB 优先,JSON 兼容)。
     * 条件:embedding_blob NOT NULL OR embedding != '' AND embedding != '[]'。
     */
    @Query(
        """
        SELECT * FROM knowledge_chunks
        WHERE embedding_blob IS NOT NULL
           OR (embedding != '' AND embedding != '[]')
        """,
    )
    suspend fun getAllWithEmbedding(): List<KnowledgeChunkEntity>

    /** v1.133 别名:语义同 [getAllWithEmbedding],供新代码使用更明确的名字。 */
    suspend fun getAllWithEmbeddingBlob(): List<KnowledgeChunkEntity> = getAllWithEmbedding()

    /**
     * v1.133: 按 docIds 过滤的已索引分块(助手绑定 KB / @mention 定向检索用)。
     * docIds 为空时返回空列表(避免误全表扫)。
     */
    @Query(
        """
        SELECT * FROM knowledge_chunks
        WHERE doc_id IN (:docIds)
          AND (embedding_blob IS NOT NULL OR (embedding != '' AND embedding != '[]'))
        ORDER BY created_at ASC
        """,
    )
    suspend fun getByDocIds(docIds: List<String>): List<KnowledgeChunkEntity>

    /**
     * M-KC1: 分页加载已生成 embedding 的分块(大规模数据用)。
     */
    @Query(
        """
        SELECT * FROM knowledge_chunks
        WHERE embedding_blob IS NOT NULL OR (embedding != '' AND embedding != '[]')
        ORDER BY created_at ASC LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getPageWithEmbedding(limit: Int, offset: Int): List<KnowledgeChunkEntity>

    /** v1.133 别名:语义同 [getPageWithEmbedding]。 */
    suspend fun getPageWithEmbeddingBlob(limit: Int, offset: Int): List<KnowledgeChunkEntity> =
        getPageWithEmbedding(limit, offset)

    /** 按文档 ID 删除全部分块。 */
    @Query("DELETE FROM knowledge_chunks WHERE doc_id = :docId")
    suspend fun deleteByDoc(docId: String)

    @Transaction
    suspend fun reindex(docId: String, chunks: List<KnowledgeChunkEntity>) {
        deleteByDoc(docId)
        insertAll(chunks)
    }

    /** v1.133: 批量重新索引(批量重索引 Worker 用)。 */
    @Transaction
    suspend fun reindexBatch(chunksByDoc: Map<String, List<KnowledgeChunkEntity>>) {
        chunksByDoc.forEach { (docId, chunks) ->
            deleteByDoc(docId)
            insertAll(chunks)
        }
    }

    @Query(
        """
        SELECT COUNT(*) FROM knowledge_chunks
        WHERE embedding_blob IS NOT NULL OR (embedding != '' AND embedding != '[]')
        """,
    )
    suspend fun countIndexed(): Int

    @Query("SELECT COUNT(*) FROM knowledge_chunks WHERE doc_id = :docId")
    suspend fun countByDoc(docId: String): Int

    /**
     * v1.133: 取库中第一条已索引 chunk 的 embedding 维度(BLOB 长度 / 4)。
     * 用于 [io.zer0.muse.rag.RagService.indexDocument] 维度一致性校验 —
     * 防止用户切换 embedding 模型后,新文档维度与旧文档不一致导致向量检索全部失效。
     *
     * 返回 null 表示库中无 BLOB 数据(全是旧 JSON 数据或空库),调用方应跳过校验。
     */
    @Query(
        """
        SELECT LENGTH(embedding_blob) / 4 FROM knowledge_chunks
        WHERE embedding_blob IS NOT NULL
        LIMIT 1
        """,
    )
    suspend fun getFirstIndexedEmbeddingDim(): Int?

    @Query("DELETE FROM knowledge_chunks")
    suspend fun deleteAll()

    /** v1.133: 按 KB 下的 docIds 批量删除(删 KB 时用)。 */
    @Query("DELETE FROM knowledge_chunks WHERE doc_id IN (:docIds)")
    suspend fun deleteByDocIds(docIds: List<String>)

    @Query(
        "SELECT id, doc_id, content, '' AS embedding, NULL AS embedding_blob, chunk_index, token_count, metadata_json, created_at FROM knowledge_chunks",
    )
    suspend fun getAll(): List<KnowledgeChunkEntity>
}
