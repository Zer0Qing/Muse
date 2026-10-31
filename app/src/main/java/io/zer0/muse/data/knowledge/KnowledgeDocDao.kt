package io.zer0.muse.data.knowledge

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * v2.6.x: 文档标题映射用的轻量投影。
 *
 * 检索链路只要 id→title,用 [KnowledgeDocDao.observeAll] 会把 content(整篇正文)一并读进内存,
 * 且该读取在分页循环里每页都会发生一次。
 */
data class KnowledgeDocTitleRow(
    val id: String,
    val title: String,
)

/**
 * 文档列表用的轻量投影:不读正文,只带字节数与索引状态。
 *
 * [sizeBytes] 由 SQL 现算 `LENGTH(CAST(content AS BLOB))`;把整篇正文读回内存只为量一下
 * 大小,在知识库页第一屏就要卡住。
 */
data class KnowledgeDocListRow(
    val id: String,
    val title: String,
    @ColumnInfo(name = "chunk_count") val chunkCount: Int,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "embedding_model") val embeddingModel: String,
)

@Dao
interface KnowledgeDocDao {
    @Query("SELECT * FROM knowledge_docs ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<KnowledgeDocEntity>>

    /**
     * v2.6.x: id→title 的单次快照(只投影 id/title 两列,不读 content)。
     *
     * 排序与 [observeAll] 一致(updated_at 降序),调用方 `associate` 后得到的映射内容与顺序不变;
     * 语义仍是「取一次」,不返回 Flow、不持续观察。
     */
    @Query("SELECT id, title FROM knowledge_docs ORDER BY updated_at DESC")
    suspend fun getDocTitles(): List<KnowledgeDocTitleRow>

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
     * 按知识库分页列出文档(不含 internal 文档),排序与 [observeByKb] 一致。
     *
     * keyset 分页:`cursor` 传上一页最后一条的 id,只取排在它后面的行
     * (`updated_at` 更早,同一时间戳则 id 更小)。`cursor` 找不到(文档已删、库被换过)
     * 时子查询给 NULL、比较不成立,本页为空 —— 宁可回空页也不静默跳回第一页。
     */
    @Query(
        """
        SELECT id, title, chunk_count,
               LENGTH(CAST(content AS BLOB)) AS size_bytes,
               created_at, embedding_model
        FROM knowledge_docs
        WHERE kb_id = :kbId AND is_internal = 0
          AND (
            :cursor IS NULL
            OR updated_at < (SELECT updated_at FROM knowledge_docs WHERE id = :cursor)
            OR (
                updated_at = (SELECT updated_at FROM knowledge_docs WHERE id = :cursor)
                AND id < :cursor
            )
          )
        ORDER BY updated_at DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getPageByKb(kbId: String, cursor: String?, limit: Int): List<KnowledgeDocListRow>

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
