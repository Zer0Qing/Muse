package io.zer0.muse.data.knowledge

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * v1.133: 知识库(KB)实体 — 支持多知识库与助手绑定。
 *
 * 一个 KB 包含若干 [KnowledgeDocEntity],通过 [KnowledgeDocEntity.kbId] 关联。
 * 助手通过 [io.zer0.muse.data.assistant.AssistantEntity.knowledgeBaseIdsJson] 绑定一个或多个 KB。
 *
 * 默认 KB:id="default",name="默认知识库"。MIGRATION_38_39 会自动插入。
 */
@Serializable
@Entity(
    tableName = "knowledge_bases",
    indices = [Index(value = ["updated_at"])],
)
data class KnowledgeBaseEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "") val description: String = "",
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    /**
     * 该 KB 下的文档数 — 读取时由子查询实时计算(不再依赖双写维护,
     * 避免导入/删除路径漏更新导致列表显示旧值)。写入列仅作旧库迁移兼容。
     */
    @ColumnInfo(name = "doc_count", defaultValue = "0") val docCount: Int = 0,

    // ── P2-2（审计报告）: 按 KB 覆盖检索参数（null = 继承全局）──
    // 旧实现 RAG 配置是全局一份，不同库不能有不同 chunk/topK/阈值，
    // 也不能给不同库配不同 embedding/rerank。这里以**可空覆盖字段**实现，
    // 为空时完全保留原行为，不影响存量数据。
    @ColumnInfo(name = "chunk_size_override") val chunkSizeOverride: Int? = null,
    @ColumnInfo(name = "chunk_overlap_override") val chunkOverlapOverride: Int? = null,
    @ColumnInfo(name = "top_k_override") val topKOverride: Int? = null,
    @ColumnInfo(name = "threshold_override") val thresholdOverride: Float? = null,
    @ColumnInfo(name = "embedding_model_override", defaultValue = "") val embeddingModelOverride: String = "",
)

@Dao
interface KnowledgeBaseDao {
    @Query(
        """
        SELECT id, name, description, created_at, updated_at,
           (SELECT COUNT(*) FROM knowledge_docs d WHERE d.kb_id = knowledge_bases.id AND d.is_internal = 0)
           AS doc_count,
           chunk_size_override, chunk_overlap_override, top_k_override,
           threshold_override, embedding_model_override
        FROM knowledge_bases ORDER BY updated_at DESC
        """,
    )
    fun observeAll(): Flow<List<KnowledgeBaseEntity>>

    @Query(
        """
        SELECT id, name, description, created_at, updated_at,
           (SELECT COUNT(*) FROM knowledge_docs d WHERE d.kb_id = knowledge_bases.id AND d.is_internal = 0)
           AS doc_count,
           chunk_size_override, chunk_overlap_override, top_k_override,
           threshold_override, embedding_model_override
        FROM knowledge_bases ORDER BY updated_at DESC
        """,
    )
    suspend fun getAll(): List<KnowledgeBaseEntity>

    @Query("SELECT * FROM knowledge_bases WHERE id = :id")
    suspend fun getById(id: String): KnowledgeBaseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(kb: KnowledgeBaseEntity)

    @Query("DELETE FROM knowledge_bases WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM knowledge_bases")
    suspend fun count(): Int

    /** P0-10: 备份恢复用 — 全量清空。 */
    @Query("DELETE FROM knowledge_bases")
    suspend fun deleteAll()
}
