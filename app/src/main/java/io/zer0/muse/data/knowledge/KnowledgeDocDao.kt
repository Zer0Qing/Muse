package io.zer0.muse.data.knowledge

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDocDao {
    @Query("SELECT * FROM knowledge_docs ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<KnowledgeDocEntity>>

    /**
     * v1.133: 按知识库 ID 列出文档(默认排除 internal 文档)。
     * kbId 为 "default" 时即用户主库。
     */
    @Query(
        "SELECT * FROM knowledge_docs WHERE kb_id = :kbId AND is_internal = 0 ORDER BY updated_at DESC",
    )
    fun observeByKb(kbId: String): Flow<List<KnowledgeDocEntity>>

    /** v1.133: 列出所有非 internal 文档(用于跨 KB 检索时的候选集筛选)。 */
    @Query("SELECT * FROM knowledge_docs WHERE is_internal = 0 ORDER BY updated_at DESC")
    fun observeAllUser(): Flow<List<KnowledgeDocEntity>>

    /**
     * v0.23: 全文搜索 — 标题或内容包含 query(大小写不敏感)。
     *
     * M-KB1/M-KB2: 使用 ESCAPE '\' 子句,调用方需先用 [escapeLikeQuery] 转义 query 中的
     * LIKE 通配符(% _ \),否则用户输入的 % _ 会被当作通配符。
     * 知识库数据量小,LIKE 可接受;若后续需要真正的 FTS,可引入 KnowledgeFtsEntity + MATCH。
     */
    @Query(
        """
        SELECT * FROM knowledge_docs
        WHERE (title LIKE '%' || :query || '%' ESCAPE '\' OR content LIKE '%' || :query || '%' ESCAPE '\')
          AND (:includeInternal = 1 OR is_internal = 0)
        ORDER BY updated_at DESC
        """,
    )
    fun search(query: String, includeInternal: Boolean = false): Flow<List<KnowledgeDocEntity>>

    @Query("SELECT * FROM knowledge_docs WHERE id = :id")
    suspend fun getById(id: String): KnowledgeDocEntity?

    /**
     * v1.133: 按 id 批量查询文档(含 internal 字段)。
     *
     * 用于 [io.zer0.muse.rag.RagService.retrieve] 在向量检索结果回填 isInternal 标记,
     * 替代原 `docId.startsWith("devdoc-")` 硬编码。
     * ids 为空时返回空列表(避免误全表扫)。
     */
    @Query("SELECT * FROM knowledge_docs WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<KnowledgeDocEntity>

    /** v1.133: 按 kb_id 批量查询(用于助手绑定的 KB 文档汇总)。 */
    @Query("SELECT * FROM knowledge_docs WHERE kb_id IN (:kbIds) AND is_internal = 0")
    suspend fun getByKbIds(kbIds: List<String>): List<KnowledgeDocEntity>

    /** v2.4.x: 列出全部内部文档(devdoc)，供 seeder 增量建索引。 */
    @Query("SELECT * FROM knowledge_docs WHERE is_internal = 1")
    suspend fun getInternalDocs(): List<KnowledgeDocEntity>

    /** v1.133: 按 content_hash 查询(增量更新判断)。 */
    @Query("SELECT * FROM knowledge_docs WHERE content_hash = :hash LIMIT 1")
    suspend fun findByContentHash(hash: String): KnowledgeDocEntity?

    /** v1.133: 按 title 模糊匹配(@mention 解析 docId 用)。 */
    @Query(
        "SELECT * FROM knowledge_docs WHERE title LIKE '%' || :titleKeyword || '%' ESCAPE '\\' AND is_internal = 0 LIMIT 5",
    )
    suspend fun findByTitleKeyword(titleKeyword: String): List<KnowledgeDocEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(doc: KnowledgeDocEntity)

    /** Mark a document as unindexed after an interrupted replacement. */
    @Query("UPDATE knowledge_docs SET chunk_count = 0, embedding_model = '' WHERE id = :id")
    suspend fun clearIndexStatus(id: String)

    /**
     * P0-3（审计报告）: 索引替换中途失败后标记为「索引已丢，需重建」。
     *
     * 旧实现失败后调 [clearIndexStatus] 把 chunk_count 清零、embedding_model 清空，
     * UI 看起来只是「未索引」，用户并不知道该文档原本建过索引、现在数据没了。
     *
     * 这里复用 embedding_model 字段写入哨兵值（不新增列，零迁移）：
     *  UI 侧识别 [INDEX_BROKEN_SENTINEL] 后显示可操作的「需重新索引」提示。
     */
    @Query("UPDATE knowledge_docs SET chunk_count = 0, embedding_model = :sentinel WHERE id = :id")
    suspend fun markIndexBroken(id: String, sentinel: String = INDEX_BROKEN_SENTINEL)

    @Query("DELETE FROM knowledge_docs WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * v1.133: 删除指定 KB 下所有文档(连带 chunks 通过外键 ON DELETE CASCADE 或应用层清理)。
     * 调用前应确保已删除所有相关 chunks。
     */
    @Query("DELETE FROM knowledge_docs WHERE kb_id = :kbId")
    suspend fun deleteByKb(kbId: String)

    @Query("DELETE FROM knowledge_chunks WHERE doc_id = :docId")
    suspend fun deleteChunksByDoc(docId: String)

    @Transaction
    suspend fun deleteDocWithChunks(id: String) {
        deleteChunksByDoc(id)
        delete(id)
    }

    @Query("SELECT COUNT(*) FROM knowledge_docs")
    suspend fun count(): Int

    /** 统计用户可见文档数(排除 is_internal=true 的内部开发文档)。 */
    @Query("SELECT COUNT(*) FROM knowledge_docs WHERE is_internal = 0")
    suspend fun countUserVisible(): Int

    /** v1.133: 统计 KB 下文档数(用于 KB 列表显示)。 */
    @Query("SELECT COUNT(*) FROM knowledge_docs WHERE kb_id = :kbId AND is_internal = 0")
    suspend fun countByKb(kbId: String): Int

    @Query("SELECT * FROM knowledge_docs")
    suspend fun getAll(): List<KnowledgeDocEntity>

    @Query("DELETE FROM knowledge_docs")
    suspend fun deleteAll()

    companion object {
        /** 索引失败哨兵值（写入 embedding_model）。不可能是真实模型 key，不会撞车。 */
        const val INDEX_BROKEN_SENTINEL = "__index_broken__"

        fun escapeLikeQuery(query: String): String = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
