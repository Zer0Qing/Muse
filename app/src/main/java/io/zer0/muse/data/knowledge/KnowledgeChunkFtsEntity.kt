package io.zer0.muse.data.knowledge

import androidx.room.Dao
import androidx.room.Query
import androidx.room.SkipQueryVerification

/**
 * v1.133: knowledge_chunks 的 FTS4 虚拟表 — 用于 BM25 全文检索(混合检索的一支)。
 *
 * 设计:
 *  - **不**作为 Room @Entity 注册到 @Database entities 列表。
 *    原因:Room KSP 编译期 schema 验证用的 sqlite-jdbc 内存数据库对独立 FTS4 vtable
 *    (无 contentEntity)的 CREATE VIRTUAL TABLE 构造报错 "vtable constructor failed"。
 *    改为完全 raw SQL 模式 — 由 [io.zer0.muse.data.session.MuseDb.MIGRATION_38_39] 创建,
 *    DAO 所有方法加 @SkipQueryVerification,Room 不感知该表存在。
 *  - chunkId / docId 作为普通列(用于按文档/分块删除时定位)。
 *  - 检索用 MATCH 操作符,BM25 排序用 `bm25(knowledge_chunks_fts)` 函数(SQLite 3.x 内置)。
 *
 * 同步策略(由 [io.zer0.muse.rag.RagService] 触发):
 *  - 新增 chunk → [insert] 同步插入
 *  - 删除 chunk → [deleteByDoc] / [deleteByChunkIds] 同步删除
 *  - 重建索引 → 先 [deleteAll] 再批量 [insert]
 *  - "upsert" 语义:FTS rowid 自动生成,REPLACE 无意义,调用方需先 delete 再 insert
 *
 * R-DB-02: 单条写操作捕获 no such table 后调用 [KnowledgeChunkFtsSelfHealer.repair] 自愈重试。
 * 批量 [insertAll] 仍在 Room 事务内,由 [io.zer0.muse.rag.RagService] 在事务外做自愈重试,
 * 避免 DDL 与事务连接互相等待。
 */
@Dao
interface KnowledgeChunkFtsDao {

    @SkipQueryVerification
    @Query(
        """
        INSERT INTO knowledge_chunks_fts(chunkId, doc_id, text_content)
        VALUES (:chunkId, :docId, :content)
        """,
    )
    suspend fun insertRaw(chunkId: String, docId: String, content: String)

    @SkipQueryVerification
    @Query("DELETE FROM knowledge_chunks_fts WHERE doc_id = :docId")
    suspend fun deleteByDocRaw(docId: String)

    @SkipQueryVerification
    @Query("DELETE FROM knowledge_chunks_fts WHERE chunkId IN (:chunkIds)")
    suspend fun deleteByChunkIdsRaw(chunkIds: List<String>)

    @SkipQueryVerification
    @Query("DELETE FROM knowledge_chunks_fts")
    suspend fun deleteAllRaw()

    /** 批量插入 — 用事务保证原子性。 */
    @androidx.room.Transaction
    suspend fun insertAll(rows: List<KnowledgeChunkFtsRow>) {
        for (r in rows) {
            insertRaw(r.chunkId, r.docId, r.content)
        }
    }

    /**
     * 单行插入。@Insert 需要 entity 类型绑定,这里改用 @Query raw SQL。
     * OnConflictStrategy.REPLACE 在 FTS 表上基于 rowid(自动生成),
     * 实际是 INSERT,不会按 chunkId 替换。调用方需先 [deleteByChunkIds] 再 [insert] 实现 upsert 语义。
     */
    suspend fun insert(chunkId: String, docId: String, content: String) = withFtsSelfHeal { insertRaw(chunkId, docId, content) }

    suspend fun deleteByDoc(docId: String) = withFtsSelfHeal { deleteByDocRaw(docId) }

    suspend fun deleteByChunkIds(chunkIds: List<String>) = withFtsSelfHeal { deleteByChunkIdsRaw(chunkIds) }

    suspend fun deleteAll() = withFtsSelfHeal { deleteAllRaw() }

    /**
     * v1.133: BM25 全文检索 — 返回匹配的 chunkId 及分数。
     *
     * R1 FTS 排名契约修正:表是 FTS4 虚拟表,`bm25()` 是 FTS5 辅助函数,在 FTS4 上下文
     * 执行必然抛 "unable to use function bm25 in the requested context"(R0 基线 C 待验项的
     * 桌面 SQLite 合同观测);此前该错误被上游 resultOf 吞掉,导致 BM25 召回支静默清零、
     * 混合检索退化为纯向量。过渡修复:SELECT 不调用任何 FTS5 专用函数,FTS4 MATCH 命中
     * 按 rowid 序返回(FTS4 无原生 BM25 排名;应用层 matchinfo('pcx') 排名由 R6 统一查询
     * 计划落地,真实设备排名行为由 R10 证明)。score 恒为 0:融合层(HybridSearchService)
     * 对同分命中按命中序稳定排序,不依赖绝对分数。
     *
     * query 需用 FTS4 语法:单词空格分隔为 AND,短语用双引号。
     * 例:"合同 违约" → 同时包含"合同"和"违约";"\"违约责任\"" → 完整短语。
     *
     * 返回:List<FTS命中 chunkId>(score 保留字段兼容现有调用方,恒为 0.0)
     */
    @SkipQueryVerification
    @Query(
        """
        SELECT chunkId, 0.0 AS score
        FROM knowledge_chunks_fts
        WHERE text_content MATCH :query
        LIMIT :limit
        """,
    )
    suspend fun searchBm25(query: String, limit: Int): List<KnowledgeChunkFtsHit>

    /** Search wrapper with the same missing-table self-healing as writes. */
    suspend fun searchBm25Safe(query: String, limit: Int): List<KnowledgeChunkFtsHit> = withFtsSelfHeal { searchBm25(query, limit) }

    private suspend fun <T> withFtsSelfHeal(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: RuntimeException) {
            if (e.message?.contains("no such table: knowledge_chunks_fts") != true) throw e
            KnowledgeChunkFtsSelfHealer.repair()
            return block()
        }
    }
}

/** FTS BM25 检索结果行。 */
data class KnowledgeChunkFtsHit(
    val chunkId: String,
    /** SQLite bm25() 返回负值,越小越相关;调用方取反作为正向权重。 */
    val score: Double,
)

/** FTS 插入行的轻量载体(非 entity,仅供 [KnowledgeChunkFtsDao.insertAll] 传参用)。 */
data class KnowledgeChunkFtsRow(
    val chunkId: String,
    val docId: String,
    val content: String,
)
